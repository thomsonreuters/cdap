/*
 * Copyright © 2014-2016 Cask Data, Inc.
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

package co.cask.cdap.data2.dataset2.lib.table.inmemory;

import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.dataset.DataSetException;
import co.cask.cdap.api.dataset.DatasetContext;
import co.cask.cdap.api.dataset.DatasetSpecification;
import co.cask.cdap.api.dataset.table.ConflictDetection;
import co.cask.cdap.api.dataset.table.Filter;
import co.cask.cdap.api.dataset.table.Scan;
import co.cask.cdap.api.dataset.table.Scanner;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.data2.dataset2.lib.table.BufferingTable;
import co.cask.cdap.data2.dataset2.lib.table.FuzzyRowFilter;
import co.cask.cdap.data2.dataset2.lib.table.Update;
import co.cask.tephra.Transaction;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.util.Map;
import java.util.NavigableMap;
import javax.annotation.Nullable;

/**
 *
 */
public class InMemoryTable extends BufferingTable {
  private static final long NO_TX_VERSION = 0L;

  private Transaction tx;

  /**
   * To be used in tests which do not need namespaces
   */
  @VisibleForTesting
  public InMemoryTable(String name) {
    this(name, ConflictDetection.ROW);
  }

  /**
   * To be used in tests that do not need namespaces
   */
  public InMemoryTable(String name, ConflictDetection level) {
    super(name, level);
  }

  /**
   * To be used in tests that need namespaces
   */
  public InMemoryTable(DatasetContext datasetContext, String name, CConfiguration cConf) {
    this(datasetContext, name, ConflictDetection.ROW, cConf);
  }

  /**
   * To be used in tests that need namespaces
   */
  public InMemoryTable(DatasetContext datasetContext, String name, ConflictDetection level, CConfiguration cConf) {
    super(PrefixedNamespaces.namespace(cConf, datasetContext.getNamespaceId(), name), level);
  }

  public InMemoryTable(DatasetContext datasetContext, DatasetSpecification spec, CConfiguration cConf) {
    super(PrefixedNamespaces.namespace(cConf, datasetContext.getNamespaceId(), spec.getName()),
          false, spec.getProperties());
  }

  @Override
  public void startTx(Transaction tx) {
    super.startTx(tx);
    this.tx = tx;
  }

  @Override
  public void increment(byte[] row, byte[][] columns, long[] amounts) {
    // for in-memory use, no need to do fancy read-less increments
    incrementAndGet(row, columns, amounts);
  }

  @Override
  protected void persist(NavigableMap<byte[], NavigableMap<byte[], Update>> buff) {
    // split up the increments and puts
    InMemoryTableService.merge(getTableName(), buff, tx.getWritePointer());
  }

  @Override
  protected void undo(NavigableMap<byte[], NavigableMap<byte[], Update>> persisted) {
    // NOTE: we could just use merge and pass the changes with all values = null, but separate method is more efficient
    InMemoryTableService.undo(getTableName(), persisted, tx.getWritePointer());
  }

  @Override
  protected NavigableMap<byte[], byte[]> getPersisted(byte[] row, byte[] startColumn, byte[] stopColumn, int limit)
    throws Exception {

    NavigableMap<byte[], byte[]> rowMap = getInternal(row, null);
    if (rowMap == null) {
      return EMPTY_ROW_MAP;
    }
    return getRange(rowMap, startColumn, stopColumn, limit);
  }

  @Override
  protected NavigableMap<byte[], byte[]> getPersisted(byte[] row, @Nullable byte[][] columns) throws Exception {
    return getInternal(row, columns);
  }

  @Override
  protected Scanner scanPersisted(Scan scan) {
    // todo: a lot of inefficient copying from one map to another
    byte[] startRow = scan.getStartRow();
    byte[] stopRow = scan.getStopRow();
    NavigableMap<byte[], NavigableMap<byte[], NavigableMap<Long, byte[]>>> rowRange =
      InMemoryTableService.getRowRange(getTableName(), startRow, stopRow,
          tx == null ? null : tx);
    NavigableMap<byte[], NavigableMap<byte[], byte[]>> visibleRowRange = getLatestNotExcludedRows(rowRange, tx);
    NavigableMap<byte[], NavigableMap<byte[], byte[]>> rows = unwrapDeletesForRows(visibleRowRange);

    rows = applyFilter(rows, scan.getFilter());

    return new InMemoryScanner(rows.entrySet().iterator());
  }

  private NavigableMap<byte[], NavigableMap<byte[], byte[]>> applyFilter(
                                                    NavigableMap<byte[], NavigableMap<byte[], byte[]>> map,
                                                    @Nullable Filter filter) {

    if (filter == null) {
      return map;
    }

    // todo: currently we support only FuzzyRowFilter as an experimental feature
    if (filter instanceof FuzzyRowFilter) {
      NavigableMap<byte[], NavigableMap<byte[], byte[]>> result = Maps.newTreeMap(Bytes.BYTES_COMPARATOR);
      for (Map.Entry<byte[], NavigableMap<byte[], byte[]>> entry : map.entrySet()) {
        if (FuzzyRowFilter.ReturnCode.INCLUDE == ((FuzzyRowFilter) filter).filterRow(entry.getKey())) {
          result.put(entry.getKey(), entry.getValue());
        }
      }
      return result;
    } else {
      throw new DataSetException("Unknown filter type: " + filter);
    }
  }

  private NavigableMap<byte[], byte[]> getInternal(byte[] row, @Nullable byte[][] columns) throws IOException {
    // no tx logic needed
    if (tx == null) {
      NavigableMap<byte[], NavigableMap<Long, byte[]>> rowMap =
        InMemoryTableService.get(getTableName(), row, tx);

      return unwrapDeletes(filterByColumns(getLatest(rowMap), columns));
    }

    NavigableMap<byte[], NavigableMap<Long, byte[]>> rowMap =
      InMemoryTableService.get(getTableName(), row, tx);

    if (rowMap == null) {
      return EMPTY_ROW_MAP;
    }

    // if exclusion list is empty, do simple "read last" value call todo: explain
    if (!tx.hasExcludes()) {
      return unwrapDeletes(filterByColumns(getLatest(rowMap), columns));
    }

    NavigableMap<byte[], byte[]> result = filterByColumns(getLatestNotExcluded(rowMap, tx), columns);
    return unwrapDeletes(result);
  }

  private NavigableMap<byte[], byte[]> filterByColumns(NavigableMap<byte[], byte[]> rowMap,
                                                       @Nullable byte[][] columns) {
    if (columns == null) {
      return rowMap;
    }
    NavigableMap<byte[], byte[]> result = Maps.newTreeMap(Bytes.BYTES_COMPARATOR);
    for (byte[] column : columns) {
      byte[] val = rowMap.get(column);
      if (val != null) {
        result.put(column, val);
      }
    }
    return result;

  }

  private NavigableMap<byte[], byte[]> getLatest(NavigableMap<byte[], NavigableMap<Long, byte[]>> rowMap) {
    if (rowMap == null) {
      return EMPTY_ROW_MAP;
    }

    NavigableMap<byte[], byte[]> result = Maps.newTreeMap(Bytes.BYTES_COMPARATOR);
    for (Map.Entry<byte[], NavigableMap<Long, byte[]>> column : rowMap.entrySet()) {
      // latest go first
      result.put(column.getKey(), column.getValue().firstEntry().getValue());
    }
    return result;
  }

  protected static NavigableMap<byte[], byte[]> getLatestNotExcluded(
    NavigableMap<byte[], NavigableMap<Long, byte[]>> rowMap, Transaction tx) {

    // todo: for some subclasses it is ok to do changes in place...
    NavigableMap<byte[], byte[]> result = Maps.newTreeMap(Bytes.BYTES_COMPARATOR);
    for (Map.Entry<byte[], NavigableMap<Long, byte[]>> column : rowMap.entrySet()) {
      // NOTE: versions map already sorted, first comes latest version
      // todo: not cool to rely on external implementation specifics
      for (Map.Entry<Long, byte[]> versionAndValue : column.getValue().entrySet()) {
        // NOTE: we know that excluded versions are ordered
        if (tx == null || tx.isVisible(versionAndValue.getKey())) {
          result.put(column.getKey(), versionAndValue.getValue());
          break;
        }
      }
    }

    return result;
  }

  protected static NavigableMap<byte[], NavigableMap<byte[], byte[]>> getLatestNotExcludedRows(
    NavigableMap<byte[], NavigableMap<byte[], NavigableMap<Long, byte[]>>> rows, Transaction tx) {
    NavigableMap<byte[], NavigableMap<byte[], byte[]>> result = Maps.newTreeMap(Bytes.BYTES_COMPARATOR);

    for (Map.Entry<byte[], NavigableMap<byte[], NavigableMap<Long, byte[]>>> rowMap : rows.entrySet()) {
      NavigableMap<byte[], byte[]> visibleRowMap = getLatestNotExcluded(rowMap.getValue(), tx);
      if (visibleRowMap.size() > 0) {
        result.put(rowMap.getKey(), visibleRowMap);
      }
    }

    return result;
  }
}
