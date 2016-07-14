/*
 * Copyright © 2014-2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package co.cask.cdap.data2.transaction.queue.coprocessor.hbase10;

import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.queue.QueueName;
import co.cask.cdap.data2.transaction.coprocessor.DefaultTransactionStateCacheSupplier;
import co.cask.cdap.data2.transaction.queue.ConsumerEntryState;
import co.cask.cdap.data2.transaction.queue.QueueEntryRow;
import co.cask.cdap.data2.transaction.queue.hbase.HBaseQueueAdmin;
import co.cask.cdap.data2.transaction.queue.hbase.SaltedHBaseQueueStrategy;
import co.cask.cdap.data2.transaction.queue.hbase.coprocessor.CConfigurationReader;
import co.cask.cdap.data2.transaction.queue.hbase.coprocessor.ConsumerConfigCache;
import co.cask.cdap.data2.transaction.queue.hbase.coprocessor.ConsumerInstance;
import co.cask.cdap.data2.transaction.queue.hbase.coprocessor.QueueConsumerConfig;
import co.cask.cdap.data2.util.TableId;
import co.cask.cdap.data2.util.hbase.HBaseTableId;
import co.cask.cdap.data2.util.hbase.HTable10NameConverter;
import co.cask.tephra.coprocessor.TransactionStateCache;
import co.cask.tephra.persist.TransactionVisibilityState;
import com.google.common.base.Supplier;
import com.google.common.io.InputSupplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.coprocessor.BaseRegionObserver;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.ScanType;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionRequest;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * RegionObserver for queue table. This class should only have JSE and HBase classes dependencies only.
 * It can also has dependencies on CDAP classes provided that all the transitive dependencies stay within
 * the mentioned scope.
 *
 * This region observer does queue eviction during flush time and compact time by using queue consumer state
 * information to determine if a queue entry row can be omitted during flush/compact.
 */
public final class HBaseQueueRegionObserver extends BaseRegionObserver {

  private static final Log LOG = LogFactory.getLog(HBaseQueueRegionObserver.class);

  private TableName configTableName;
  private CConfigurationReader cConfReader;
  TransactionStateCache txStateCache;
  private Supplier<TransactionVisibilityState> txSnapshotSupplier;
  private ConsumerConfigCache configCache;

  private int prefixBytes;
  private String namespaceId;
  private String appName;
  private String flowName;

  @Override
  public void start(CoprocessorEnvironment env) {
    if (env instanceof RegionCoprocessorEnvironment) {
      HTableDescriptor tableDesc = ((RegionCoprocessorEnvironment) env).getRegion().getTableDesc();
      String hTableName = tableDesc.getNameAsString();

      String prefixBytes = tableDesc.getValue(HBaseQueueAdmin.PROPERTY_PREFIX_BYTES);
      try {
        // Default to SALT_BYTES for the older salted queue implementation.
        this.prefixBytes = prefixBytes == null ? SaltedHBaseQueueStrategy.SALT_BYTES : Integer.parseInt(prefixBytes);
      } catch (NumberFormatException e) {
        // Shouldn't happen for table created by cdap.
        LOG.error("Unable to parse value of '" + HBaseQueueAdmin.PROPERTY_PREFIX_BYTES + "' property. " +
                    "Default to " + SaltedHBaseQueueStrategy.SALT_BYTES, e);
        this.prefixBytes = SaltedHBaseQueueStrategy.SALT_BYTES;
      }

      HTable10NameConverter nameConverter = new HTable10NameConverter();
      namespaceId = nameConverter.from(tableDesc).getHbaseNamespace();
      appName = HBaseQueueAdmin.getApplicationName(hTableName);
      flowName = HBaseQueueAdmin.getFlowName(hTableName);

      Configuration conf = env.getConfiguration();
      String hbaseNamespacePrefix = tableDesc.getValue(Constants.Dataset.TABLE_PREFIX);
      TableId queueConfigTableId = HBaseQueueAdmin.getConfigTableId(namespaceId);
      final String sysConfigTablePrefix = nameConverter.getSysConfigTablePrefix(hbaseNamespacePrefix);
      txStateCache = new DefaultTransactionStateCacheSupplier(sysConfigTablePrefix, conf).get();
      txSnapshotSupplier = new Supplier<TransactionVisibilityState>() {
        @Override
        public TransactionVisibilityState get() {
          return txStateCache.getLatestState();
        }
      };
      configTableName = nameConverter.toTableName(hbaseNamespacePrefix, new HBaseTableId(
        queueConfigTableId.getNamespace().getId(), queueConfigTableId.getTableName()));
      cConfReader = new CConfigurationReader(conf, sysConfigTablePrefix);
      configCache = createConfigCache(env);
    }
  }

  @Override
  public InternalScanner preFlush(ObserverContext<RegionCoprocessorEnvironment> e,
                                  Store store, InternalScanner scanner) throws IOException {
    if (!e.getEnvironment().getRegion().isAvailable()) {
      return scanner;
    }

    LOG.info("preFlush, creates EvictionInternalScanner");
    return new EvictionInternalScanner("flush", e.getEnvironment(), scanner);
  }

  @Override
  public InternalScanner preCompact(ObserverContext<RegionCoprocessorEnvironment> e, Store store,
                                    InternalScanner scanner, ScanType type,
                                    CompactionRequest request) throws IOException {
    if (!e.getEnvironment().getRegion().isAvailable()) {
      return scanner;
    }

    LOG.info("preCompact, creates EvictionInternalScanner");
    return new EvictionInternalScanner("compaction", e.getEnvironment(), scanner);
  }

  // needed for queue unit-test
  @SuppressWarnings("unused")
  private void updateCache() throws IOException {
    ConsumerConfigCache configCache = this.configCache;
    if (configCache != null) {
      configCache.updateCache();
    }
  }

  private ConsumerConfigCache getConfigCache(CoprocessorEnvironment env) {
    if (!configCache.isAlive()) {
      configCache = createConfigCache(env);
    }
    return configCache;
  }

  private ConsumerConfigCache createConfigCache(final CoprocessorEnvironment env) {
    return ConsumerConfigCache.getInstance(configTableName, cConfReader,
                                           txSnapshotSupplier, new InputSupplier<HTableInterface>() {
      @Override
      public HTableInterface getInput() throws IOException {
        return env.getTable(configTableName);
      }
    });
  }

  // need for queue unit-test
  private TransactionStateCache getTxStateCache() {
    return txStateCache;
  }

  /**
   * An {@link InternalScanner} that will skip queue entries that are safe to be evicted.
   */
  private final class EvictionInternalScanner implements InternalScanner {

    private final String triggeringAction;
    private final RegionCoprocessorEnvironment env;
    private final InternalScanner scanner;
    // This is just for object reused to reduce objects creation.
    private final ConsumerInstance consumerInstance;
    private byte[] currentQueue;
    private byte[] currentQueueRowPrefix;
    private QueueConsumerConfig consumerConfig;
    private long totalRows = 0;
    private long rowsEvicted = 0;
    // couldn't be evicted due to incomplete view of row
    private long skippedIncomplete = 0;

    private EvictionInternalScanner(String action, RegionCoprocessorEnvironment env, InternalScanner scanner) {
      this.triggeringAction = action;
      this.env = env;
      this.scanner = scanner;
      this.consumerInstance = new ConsumerInstance(0, 0);
    }

    @Override
    public boolean next(List<Cell> results) throws IOException {
      return next(results, -1);
    }

    @Override
    public boolean next(List<Cell> results, int limit) throws IOException {
      boolean hasNext = scanner.next(results, limit);

      while (!results.isEmpty()) {
        totalRows++;
        // Check if it is eligible for eviction.
        Cell cell = results.get(0);

        // If current queue is unknown or the row is not a queue entry of current queue,
        // it either because it scans into next queue entry or simply current queue is not known.
        // Hence needs to find the currentQueue
        if (currentQueue == null || !QueueEntryRow.isQueueEntry(currentQueueRowPrefix, prefixBytes, cell.getRowArray(),
                                                                cell.getRowOffset(), cell.getRowLength())) {
          // If not eligible, it either because it scans into next queue entry or simply current queue is not known.
          currentQueue = null;
        }

        // This row is a queue entry. If currentQueue is null, meaning it's a new queue encountered during scan.
        if (currentQueue == null) {
          QueueName queueName = QueueEntryRow.getQueueName(namespaceId, appName, flowName, prefixBytes,
                                                           cell.getRowArray(), cell.getRowOffset(),
                                                           cell.getRowLength());
          currentQueue = queueName.toBytes();
          currentQueueRowPrefix = QueueEntryRow.getQueueRowPrefix(queueName);
          consumerConfig = getConfigCache(env).getConsumerConfig(currentQueue);
        }

        if (consumerConfig == null) {
          // no config is present yet, so cannot evict
          return hasNext;
        }

        if (canEvict(consumerConfig, results)) {
          rowsEvicted++;
          results.clear();
          hasNext = scanner.next(results, limit);
        } else {
          break;
        }
      }

      return hasNext;
    }

    @Override
    public void close() throws IOException {
      LOG.info("Region " + env.getRegion().getRegionNameAsString() + " " + triggeringAction +
                 ", rows evicted: " + rowsEvicted + " / " + totalRows + ", skipped incomplete: " + skippedIncomplete);
      scanner.close();
    }

    /**
     * Determines the given queue entry row can be evicted.
     * @param result All KeyValues of a queue entry row.
     * @return true if it can be evicted, false otherwise.
     */
    private boolean canEvict(QueueConsumerConfig consumerConfig, List<Cell> result) {
      // If no consumer group, this queue is dead, should be ok to evict.
      if (consumerConfig.getNumGroups() == 0) {
        return true;
      }

      // If unknown consumer config (due to error), keep the queue.
      if (consumerConfig.getNumGroups() < 0) {
        return false;
      }

      // TODO (terence): Right now we can only evict if we see all the data columns.
      // It's because it's possible that in some previous flush, only the data columns are flush,
      // then consumer writes the state columns. In the next flush, it'll only see the state columns and those
      // should not be evicted otherwise the entry might get reprocessed, depending on the consumer start row state.
      // This logic is not perfect as if flush happens after enqueue and before dequeue, that entry may never get
      // evicted (depends on when the next compaction happens, whether the queue configuration has been change or not).

      // There are two data columns, "d" and "m".
      // If the size == 2, it should not be evicted as well,
      // as state columns (dequeue) always happen after data columns (enqueue).
      if (result.size() <= 2) {
        skippedIncomplete++;
        return false;
      }

      // "d" and "m" columns always comes before the state columns, prefixed with "s".
      Iterator<Cell> iterator = result.iterator();
      Cell cell = iterator.next();
      if (!QueueEntryRow.isDataColumn(cell.getQualifierArray(), cell.getQualifierOffset())) {
        skippedIncomplete++;
        return false;
      }
      cell = iterator.next();
      if (!QueueEntryRow.isMetaColumn(cell.getQualifierArray(), cell.getQualifierOffset())) {
        skippedIncomplete++;
        return false;
      }

      // Need to determine if this row can be evicted iff all consumer groups have committed process this row.
      int consumedGroups = 0;
      // Inspect each state column
      while (iterator.hasNext()) {
        cell = iterator.next();
        if (!QueueEntryRow.isStateColumn(cell.getQualifierArray(), cell.getQualifierOffset())) {
          continue;
        }
        // If any consumer has a state != PROCESSED, it should not be evicted
        if (!isProcessed(cell, consumerInstance)) {
          break;
        }
        // If it is PROCESSED, check if this row is smaller than the consumer instance startRow.
        // Essentially a loose check of committed PROCESSED.
        byte[] startRow = consumerConfig.getStartRow(consumerInstance);
        if (startRow != null && compareRowKey(cell, startRow) < 0) {
          consumedGroups++;
        }
      }

      // It can be evicted if from the state columns, it's been processed by all consumer groups
      // Otherwise, this row has to be less than smallest among all current consumers.
      // The second condition is for handling consumer being removed after it consumed some entries.
      // However, the second condition alone is not good enough as it's possible that in hash partitioning,
      // only one consumer is keep consuming when the other consumer never proceed.
      return consumedGroups == consumerConfig.getNumGroups()
        || compareRowKey(result.get(0), consumerConfig.getSmallestStartRow()) < 0;
    }

    private int compareRowKey(Cell cell, byte[] row) {
      return Bytes.compareTo(cell.getRowArray(), cell.getRowOffset() + prefixBytes,
                             cell.getRowLength() - prefixBytes, row, 0, row.length);
    }

    /**
     * Returns {@code true} if the given {@link KeyValue} has a {@link ConsumerEntryState#PROCESSED} state and
     * also put the consumer information into the given {@link ConsumerInstance}.
     * Otherwise, returns {@code false} and the {@link ConsumerInstance} is left untouched.
     */
    private boolean isProcessed(Cell cell, ConsumerInstance consumerInstance) {
      int stateIdx = cell.getValueOffset() + cell.getValueLength() - 1;
      boolean processed = cell.getValueArray()[stateIdx] == ConsumerEntryState.PROCESSED.getState();

      if (processed) {
        // Column is "s<groupId>"
        long groupId = Bytes.toLong(cell.getQualifierArray(), cell.getQualifierOffset() + 1);
        // Value is "<writePointer><instanceId><state>"
        int instanceId = Bytes.toInt(cell.getValueArray(), cell.getValueOffset() + Bytes.SIZEOF_LONG);
        consumerInstance.setGroupInstance(groupId, instanceId);
      }
      return processed;
    }
  }
}
