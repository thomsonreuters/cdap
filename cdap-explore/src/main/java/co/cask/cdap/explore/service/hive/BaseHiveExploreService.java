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

package co.cask.cdap.explore.service.hive;

import co.cask.cdap.api.dataset.Dataset;
import co.cask.cdap.api.dataset.DatasetSpecification;
import co.cask.cdap.api.dataset.lib.PartitionDetail;
import co.cask.cdap.api.dataset.lib.TimePartitionedFileSet;
import co.cask.cdap.app.runtime.scheduler.SchedulerQueueResolver;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.ConfigurationUtil;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.namespace.NamespaceQueryAdmin;
import co.cask.cdap.data.dataset.SystemDatasetInstantiator;
import co.cask.cdap.data.dataset.SystemDatasetInstantiatorFactory;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.data2.transaction.stream.StreamAdmin;
import co.cask.cdap.data2.transaction.stream.StreamConfig;
import co.cask.cdap.explore.service.Explore;
import co.cask.cdap.explore.service.ExploreException;
import co.cask.cdap.explore.service.ExploreService;
import co.cask.cdap.explore.service.ExploreServiceUtils;
import co.cask.cdap.explore.service.ExploreTableManager;
import co.cask.cdap.explore.service.HandleNotFoundException;
import co.cask.cdap.explore.service.HiveStreamRedirector;
import co.cask.cdap.explore.service.MetaDataInfo;
import co.cask.cdap.explore.service.TableNotFoundException;
import co.cask.cdap.explore.utils.ExploreTableNaming;
import co.cask.cdap.hive.context.CConfCodec;
import co.cask.cdap.hive.context.ContextManager;
import co.cask.cdap.hive.context.HConfCodec;
import co.cask.cdap.hive.context.TxnCodec;
import co.cask.cdap.hive.datasets.DatasetStorageHandler;
import co.cask.cdap.hive.stream.StreamStorageHandler;
import co.cask.cdap.proto.ColumnDesc;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.QueryHandle;
import co.cask.cdap.proto.QueryInfo;
import co.cask.cdap.proto.QueryResult;
import co.cask.cdap.proto.QueryStatus;
import co.cask.cdap.proto.TableInfo;
import co.cask.cdap.proto.TableNameInfo;
import co.cask.cdap.store.NamespaceStore;
import co.cask.tephra.Transaction;
import co.cask.tephra.TransactionSystemClient;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Closeables;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.gson.Gson;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hive.service.auth.HiveAuthFactory;
import org.apache.hive.service.cli.CLIService;
import org.apache.hive.service.cli.ColumnDescriptor;
import org.apache.hive.service.cli.FetchOrientation;
import org.apache.hive.service.cli.GetInfoType;
import org.apache.hive.service.cli.GetInfoValue;
import org.apache.hive.service.cli.HiveSQLException;
import org.apache.hive.service.cli.OperationHandle;
import org.apache.hive.service.cli.SessionHandle;
import org.apache.hive.service.cli.TableSchema;
import org.apache.hive.service.cli.thrift.TColumnValue;
import org.apache.thrift.TException;
import org.apache.twill.common.Threads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import javax.annotation.Nullable;

/**
 * Defines common functionality used by different HiveExploreServices. The common functionality includes
 * starting/stopping transactions, serializing configuration and saving operation information.
 *
 * Overridden {@link co.cask.cdap.explore.service.Explore} methods also call {@code startAndWait()},
 * which effectively allows this {@link com.google.common.util.concurrent.Service} to not have to start
 * until the first call to the explore methods is made. This is used for {@link Constants.Explore#START_ON_DEMAND},
 * which, if true, does not start the {@link ExploreService} when the explore HTTP services are started.
 */
public abstract class BaseHiveExploreService extends AbstractIdleService implements ExploreService {

  private static final Logger LOG = LoggerFactory.getLogger(BaseHiveExploreService.class);
  private static final Gson GSON = new Gson();
  private static final int PREVIEW_COUNT = 5;
  private static final long METASTORE_CLIENT_CLEANUP_PERIOD = 60;
  public static final String HIVE_METASTORE_TOKEN_KEY = "hive.metastore.token.signature";
  public static final String SPARK_YARN_DIST_FILES = "spark.yarn.dist.files";

  private final CConfiguration cConf;
  private final Configuration hConf;
  private final TransactionSystemClient txClient;
  private final SchedulerQueueResolver schedulerQueueResolver;

  // Handles that are running, or not yet completely fetched, they have longer timeout
  private final Cache<QueryHandle, OperationInfo> activeHandleCache;
  // Handles that don't have any more results to be fetched, they can be timed out aggressively.
  private final Cache<QueryHandle, InactiveOperationInfo> inactiveHandleCache;

  private final CLIService cliService;
  private final ScheduledExecutorService scheduledExecutorService;
  private final long cleanupJobSchedule;
  private final File previewsDir;
  private final ScheduledExecutorService metastoreClientsExecutorService;
  private final StreamAdmin streamAdmin;
  private final DatasetFramework datasetFramework;
  private final ExploreTableManager exploreTableManager;
  private final SystemDatasetInstantiatorFactory datasetInstantiatorFactory;
  private final ExploreTableNaming tableNaming;
  private final NamespaceQueryAdmin namespaceQueryAdmin;

  private final ThreadLocal<Supplier<IMetaStoreClient>> metastoreClientLocal;

  // The following two fields are for tracking GC'ed metastore clients and be able to call close on them.
  private final Map<Reference<? extends Supplier<IMetaStoreClient>>, IMetaStoreClient> metastoreClientReferences;
  private final ReferenceQueue<Supplier<IMetaStoreClient>> metastoreClientReferenceQueue;

  private final Map<String, String> sparkConf = new HashMap<>();

  protected abstract QueryStatus doFetchStatus(OperationHandle handle) throws HiveSQLException, ExploreException,
    HandleNotFoundException;
  protected abstract OperationHandle doExecute(SessionHandle sessionHandle, String statement)
    throws HiveSQLException, ExploreException;

  protected BaseHiveExploreService(TransactionSystemClient txClient, DatasetFramework datasetFramework,
                                   CConfiguration cConf, Configuration hConf,
                                   File previewsDir, StreamAdmin streamAdmin, NamespaceStore store,
                                   SystemDatasetInstantiatorFactory datasetInstantiatorFactory,
                                   ExploreTableNaming tableNaming, NamespaceQueryAdmin namespaceQueryAdmin) {
    this.cConf = cConf;
    this.hConf = hConf;
    this.schedulerQueueResolver = new SchedulerQueueResolver(cConf, store);
    this.previewsDir = previewsDir;
    this.metastoreClientLocal = new ThreadLocal<>();
    this.metastoreClientReferences = Maps.newConcurrentMap();
    this.metastoreClientReferenceQueue = new ReferenceQueue<>();
    this.datasetFramework = datasetFramework;
    this.streamAdmin = streamAdmin;
    this.exploreTableManager = new ExploreTableManager(this, datasetInstantiatorFactory,
                                                       new ExploreTableNaming(), hConf);
    this.datasetInstantiatorFactory = datasetInstantiatorFactory;
    this.tableNaming = tableNaming;
    this.namespaceQueryAdmin = namespaceQueryAdmin;

    // Create a Timer thread to periodically collect metastore clients that are no longer in used and call close on them
    this.metastoreClientsExecutorService =
      Executors.newSingleThreadScheduledExecutor(Threads.createDaemonThreadFactory("metastore-client-gc"));

    this.scheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor(Threads.createDaemonThreadFactory("explore-handle-timeout"));

    this.activeHandleCache =
      CacheBuilder.newBuilder()
        .expireAfterWrite(cConf.getLong(Constants.Explore.ACTIVE_OPERATION_TIMEOUT_SECS), TimeUnit.SECONDS)
        .removalListener(new ActiveOperationRemovalHandler(this, scheduledExecutorService))
        .build();
    this.inactiveHandleCache =
      CacheBuilder.newBuilder()
        .expireAfterWrite(cConf.getLong(Constants.Explore.INACTIVE_OPERATION_TIMEOUT_SECS), TimeUnit.SECONDS)
        .build();

    this.cliService = createCLIService();

    this.txClient = txClient;
    ContextManager.saveContext(datasetFramework, streamAdmin, datasetInstantiatorFactory);

    cleanupJobSchedule = cConf.getLong(Constants.Explore.CLEANUP_JOB_SCHEDULE_SECS);

    LOG.info("Active handle timeout = {} secs", cConf.getLong(Constants.Explore.ACTIVE_OPERATION_TIMEOUT_SECS));
    LOG.info("Inactive handle timeout = {} secs", cConf.getLong(Constants.Explore.INACTIVE_OPERATION_TIMEOUT_SECS));
    LOG.info("Cleanup job schedule = {} secs", cleanupJobSchedule);
  }

  protected CLIService createCLIService() {
    return new CLIService(null);
  }

  protected HiveConf getHiveConf() {
    HiveConf conf = new HiveConf();
    // Read delegation token if security is enabled.
    if (UserGroupInformation.isSecurityEnabled()) {
      conf.set(HIVE_METASTORE_TOKEN_KEY, HiveAuthFactory.HS2_CLIENT_TOKEN);

      // mapreduce.job.credentials.binary is added by Hive only if Kerberos credentials are present and impersonation
      // is enabled. However, in our case we don't have Kerberos credentials for Explore service.
      // Hence it will not be automatically added by Hive, instead we have to add it ourselves.
      // TODO: When Explore does secure impersonation this has to be the tokens of the user,
      // TODO: ... and not the tokens of the service itself.
      String hadoopAuthToken = System.getenv(UserGroupInformation.HADOOP_TOKEN_FILE_LOCATION);
      if (hadoopAuthToken != null) {
        conf.set("mapreduce.job.credentials.binary", hadoopAuthToken);
        if ("tez".equals(conf.get("hive.execution.engine"))) {
          // Add token file location property for tez if engine is tez
          conf.set("tez.credentials.path", hadoopAuthToken);
        }
      }
    }

    // Since we use delegation token in HIVE, unset the SPNEGO authentication if it is
    // enabled. Please see CDAP-3452 for details.
    conf.unset("hive.server2.authentication.spnego.keytab");
    conf.unset("hive.server2.authentication.spnego.principal");
    return conf;
  }

  protected CLIService getCliService() {
    return cliService;
  }

  private IMetaStoreClient getMetaStoreClient() throws ExploreException {
    if (metastoreClientLocal.get() == null) {
      try {
        IMetaStoreClient client = new HiveMetaStoreClient(getHiveConf());
        Supplier<IMetaStoreClient> supplier = Suppliers.ofInstance(client);
        metastoreClientLocal.set(supplier);

        // We use GC of the supplier as a signal for us to know that a thread is gone
        // The supplier is set into the thread local, which will get GC'ed when the thread is gone.
        // Since we use a weak reference key to the supplier that points to the client
        // (in the metastoreClientReferences map), it won't block GC of the supplier instance.
        // We can use the weak reference, which is retrieved through polling the ReferenceQueue,
        // to get back the client and call close() on it.
        metastoreClientReferences.put(
          new WeakReference<>(supplier, metastoreClientReferenceQueue), client);
      } catch (MetaException e) {
        throw new ExploreException("Error initializing Hive Metastore client", e);
      }
    }
    return metastoreClientLocal.get().get();
  }

  private void closeMetastoreClient(IMetaStoreClient client) {
    try {
      client.close();
    } catch (Throwable t) {
      LOG.error("Exception raised in closing Metastore client", t);
    }
  }

  @Override
  protected void startUp() throws Exception {
    LOG.info("Starting {}...", BaseHiveExploreService.class.getSimpleName());

    HiveConf hiveConf = getHiveConf();
    if (ExploreServiceUtils.isSparkEngine(hiveConf)) {
      LOG.info("Engine is spark");
      setupSparkConf();
    }

    cliService.init(hiveConf);
    cliService.start();

    metastoreClientsExecutorService.scheduleWithFixedDelay(
      new Runnable() {
        @Override
        public void run() {
          Reference<? extends Supplier<IMetaStoreClient>> ref = metastoreClientReferenceQueue.poll();
          while (ref != null) {
            IMetaStoreClient client = metastoreClientReferences.remove(ref);
            if (client != null) {
              closeMetastoreClient(client);
            }
            ref = metastoreClientReferenceQueue.poll();
          }
        }
      },
      METASTORE_CLIENT_CLEANUP_PERIOD, METASTORE_CLIENT_CLEANUP_PERIOD, TimeUnit.SECONDS);

    // Schedule the cache cleanup
    scheduledExecutorService.scheduleWithFixedDelay(new Runnable() {
                                                      @Override
                                                      public void run() {
                                                        runCacheCleanup();
                                                      }
                                                    }, cleanupJobSchedule, cleanupJobSchedule, TimeUnit.SECONDS
    );
  }

  @Override
  protected void shutDown() throws Exception {
    LOG.info("Stopping {}...", BaseHiveExploreService.class.getSimpleName());

    // By this time we should not get anymore new requests, since HTTP service has already been stopped.
    // Close all handles
    if (!activeHandleCache.asMap().isEmpty()) {
      LOG.info("Timing out active handles...");
    }
    activeHandleCache.invalidateAll();
    // Make sure the cache entries get expired.
    runCacheCleanup();

    // Wait for all cleanup jobs to complete
    scheduledExecutorService.awaitTermination(10, TimeUnit.SECONDS);
    scheduledExecutorService.shutdown();

    metastoreClientsExecutorService.shutdownNow();
    // Go through all non-cleanup'ed clients and call close() upon them
    for (IMetaStoreClient client : metastoreClientReferences.values()) {
      closeMetastoreClient(client);
    }

    cliService.stop();
  }

  private void setupSparkConf() {
    // Copy over hadoop configuration as spark properties since we don't localize hadoop conf dirs due to CDAP-5019
    for (Map.Entry<String, String> entry : hConf) {
      sparkConf.put("spark.hadoop." + entry.getKey(), hConf.get(entry.getKey()));
    }

    // don't localize config, we pass all hadoop configuration in spark properties
    sparkConf.put("spark.yarn.localizeConfig", "false");

    // Setup files to be copied over to spark containers
    sparkConf.put(BaseHiveExploreService.SPARK_YARN_DIST_FILES,
                  System.getProperty(BaseHiveExploreService.SPARK_YARN_DIST_FILES));

    if (UserGroupInformation.isSecurityEnabled()) {
      // define metastore token key name
      sparkConf.put("spark.hadoop." + HIVE_METASTORE_TOKEN_KEY, HiveAuthFactory.HS2_CLIENT_TOKEN);

      // tokens are already provided for spark client
      sparkConf.put("spark.yarn.security.tokens.hive.enabled", "false");
      sparkConf.put("spark.yarn.security.tokens.hbase.enabled", "false");

      // Hive needs to ignore security settings while running spark job
      sparkConf.put(HiveConf.ConfVars.HIVE_SERVER2_AUTHENTICATION.toString(), "NONE");
      sparkConf.put(HiveConf.ConfVars.HIVE_SERVER2_ENABLE_DOAS.toString(), "false");
    }
  }

  @Override
  public QueryHandle getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern)
    throws ExploreException, SQLException {
    startAndWait();

    try {
      SessionHandle sessionHandle = null;
      OperationHandle operationHandle = null;
      Map<String, String> sessionConf = startSession();

      try {
        sessionHandle = openHiveSession(sessionConf);

        String database = getHiveDatabase(schemaPattern);
        operationHandle = cliService.getColumns(sessionHandle, catalog, database,
                                                tableNamePattern, columnNamePattern);
        QueryHandle handle = saveReadOnlyOperation(operationHandle, sessionHandle, sessionConf, "", database);
        LOG.trace("Retrieving columns: catalog {}, schemaPattern {}, tableNamePattern {}, columnNamePattern {}",
                  catalog, database, tableNamePattern, columnNamePattern);
        return handle;
      } catch (Throwable e) {
        closeInternal(getQueryHandle(sessionConf),
                      new ReadOnlyOperationInfo(sessionHandle, operationHandle, sessionConf, "", ""));
        throw e;
      }
    } catch (HiveSQLException e) {
      throw getSqlException(e);
    } catch (Throwable e) {
      throw new ExploreException(e);
    }
  }

  @Override
  public QueryHandle getCatalogs() throws ExploreException, SQLException {
    startAndWait();

    try {
      SessionHandle sessionHandle = null;
      OperationHandle operationHandle = null;
      Map<String, String> sessionConf = startSession();

      try {
        sessionHandle = openHiveSession(sessionConf);
        operationHandle = cliService.getCatalogs(sessionHandle);
        QueryHandle handle = saveReadOnlyOperation(operationHandle, sessionHandle, sessionConf, "", "");

        LOG.trace("Retrieving catalogs");
        return handle;
      } catch (Throwable e) {
        closeInternal(getQueryHandle(sessionConf),
                      new ReadOnlyOperationInfo(sessionHandle, operationHandle, sessionConf, "", ""));
        throw e;
      }
    } catch (HiveSQLException e) {
      throw getSqlException(e);
    } catch (Throwable e) {
      throw new ExploreException(e);
    }
  }

  @Override
  public QueryHandle getSchemas(String catalog, String schemaPattern) throws ExploreException, SQLException {
    startAndWait();

    try {
      SessionHandle sessionHandle = null;
      OperationHandle operationHandle = null;
      Map<String, String> sessionConf = startSession();

      try {
        sessionHandle = openHiveSession(sessionConf);
        String database = getHiveDatabase(schemaPattern);
        operationHandle = cliService.getSchemas(sessionHandle, catalog, database);
        QueryHandle handle = saveReadOnlyOperation(operationHandle, sessionHandle, sessionConf, "", database);
        LOG.trace("Retrieving schemas: catalog {}, schema {}", catalog, database);
        return handle;
      } catch (Throwable e) {
        closeInternal(getQueryHandle(sessionConf),
                      new ReadOnlyOperationInfo(sessionHandle, operationHandle, sessionConf, "", ""));
        throw e;
      }
    } catch (HiveSQLException e) {
      throw getSqlException(e);
    } catch (Throwable e) {
      throw new ExploreException(e);
    }
  }

  @Override
  public QueryHandle getFunctions(String catalog, String schemaPattern, String functionNamePattern)
    throws ExploreException, SQLException {
    startAndWait();

    try {
      SessionHandle sessionHandle = null;
      OperationHandle operationHandle = null;
      Map<String, String> sessionConf = startSession();

      try {
        sessionHandle = openHiveSession(sessionConf);
        String database = getHiveDatabase(schemaPattern);
        operationHandle = cliService.getFunctions(sessionHandle, catalog,
                                                  database, functionNamePattern);
        QueryHandle handle = saveReadOnlyOperation(operationHandle, sessionHandle, sessionConf, "", database);
        LOG.trace("Retrieving functions: catalog {}, schema {}, function {}",
                  catalog, database, functionNamePattern);
        return handle;
      } catch (Throwable e) {
        closeInternal(getQueryHandle(sessionConf),
                      new ReadOnlyOperationInfo(sessionHandle, operationHandle, sessionConf, "", ""));
        throw e;
      }
    } catch (HiveSQLException e) {
      throw getSqlException(e);
    } catch (Throwable e) {
      throw new ExploreException(e);
    }
  }

  @Override
  public MetaDataInfo getInfo(MetaDataInfo.InfoType infoType) throws ExploreException, SQLException {
    startAndWait();

    try {
      MetaDataInfo ret = infoType.getDefaultValue();
      if (ret != null) {
        return ret;
      }

      SessionHandle sessionHandle = null;
      Map<String, String> sessionConf = startSession();
      try {
        sessionHandle = openHiveSession(sessionConf);
        // Convert to GetInfoType
        GetInfoType hiveInfoType = null;
        for (GetInfoType t : GetInfoType.values()) {
          if (t.name().equals("CLI_" + infoType.name())) {
            hiveInfoType = t;
            break;
          }
        }
        if (hiveInfoType == null) {
          // Should not come here, unless there is a mismatch between Explore and Hive info types.
          LOG.warn("Could not find Hive info type %s", infoType);
          return null;
        }
        GetInfoValue val = cliService.getInfo(sessionHandle, hiveInfoType);
        LOG.trace("Retrieving info: {}, got value {}", infoType, val);
        return new MetaDataInfo(val.getStringValue(), val.getShortValue(), val.getIntValue(), val.getLongValue());
      } finally {
        closeInternal(getQueryHandle(sessionConf), new ReadOnlyOperationInfo(sessionHandle, null, sessionConf, "", ""));
      }
    } catch (HiveSQLException e) {
      throw getSqlException(e);
    } catch (Throwable e) {
      throw new ExploreException(e);
    }
  }

  @Override
  public QueryHandle getTables(String catalog, String schemaPattern, String tableNamePattern,
                               List<String> tableTypes) throws ExploreException, SQLException {
    startAndWait();

    try {
      SessionHandle sessionHandle = null;
      OperationHandle operationHandle = null;
      Map<String, String> sessionConf = startSession();

      try {
        sessionHandle = openHiveSession(sessionConf);
        String database = getHiveDatabase(schemaPattern);
        operationHandle = cliService.getTables(sessionHandle, catalog, database,
                                               tableNamePattern, tableTypes);
        QueryHandle handle = saveReadOnlyOperation(operationHandle, sessionHandle, sessionConf, "", database);
        LOG.trace("Retrieving tables: catalog {}, schemaNamePattern {}, tableNamePattern {}, tableTypes {}",
                  catalog, database, tableNamePattern, tableTypes);
        return handle;
      } catch (Throwable e) {
        closeInternal(getQueryHandle(sessionConf),
                      new ReadOnlyOperationInfo(sessionHandle, operationHandle, sessionConf, "", ""));
        throw e;
      }
    } catch (HiveSQLException e) {
      throw getSqlException(e);
    } catch (Throwable e) {
      throw new ExploreException(e);
    }
  }

  @Override
  public List<TableNameInfo> getTables(@Nullable final String database) throws ExploreException {
    startAndWait();

    // TODO check if the database user is allowed to access if security is enabled
    try {
      List<String> databases;
      if (database == null) {
        databases = getMetaStoreClient().getAllDatabases();
      } else {
        databases = ImmutableList.of(getHiveDatabase(database));
      }
      ImmutableList.Builder<TableNameInfo> builder = ImmutableList.builder();
      for (String db : databases) {
        List<String> tables = getMetaStoreClient().getAllTables(db);
        for (String table : tables) {
          builder.add(new TableNameInfo(db, table));
        }
      }
      return builder.build();
    } catch (TException e) {
      throw new ExploreException("Error connecting to Hive metastore", e);
    }
  }

  @Override
  public TableInfo getTableInfo(@Nullable String database, String table)
    throws ExploreException, TableNotFoundException {
    startAndWait();

    // TODO check if the database user is allowed to access if security is enabled
    try {
      String db = database == null ? "default" : getHiveDatabase(database);

      Table tableInfo = getMetaStoreClient().getTable(db, table);
      List<FieldSchema> tableFields = tableInfo.getSd().getCols();
      // for whatever reason, it seems like the table columns for partitioned tables are not present
      // in the storage descriptor. If columns are missing, do a separate call for schema.
      if (tableFields == null || tableFields.isEmpty()) {
        // don't call .getSchema()... class not found exception if we do in the thrift code...
        tableFields = getMetaStoreClient().getFields(db, table);
      }

      ImmutableList.Builder<TableInfo.ColumnInfo> schemaBuilder = ImmutableList.builder();
      Set<String> fieldNames = Sets.newHashSet();
      for (FieldSchema column : tableFields) {
        schemaBuilder.add(new TableInfo.ColumnInfo(column.getName(), column.getType(), column.getComment()));
        fieldNames.add(column.getName());
      }

      ImmutableList.Builder<TableInfo.ColumnInfo> partitionKeysBuilder = ImmutableList.builder();
      for (FieldSchema column : tableInfo.getPartitionKeys()) {
        TableInfo.ColumnInfo columnInfo = new TableInfo.ColumnInfo(column.getName(), column.getType(),
                                                                   column.getComment());
        partitionKeysBuilder.add(columnInfo);
        // add partition keys to the schema if they are not already there,
        // since they show up when you do a 'describe <table>' command.
        if (!fieldNames.contains(column.getName())) {
          schemaBuilder.add(columnInfo);
        }
      }

      // its a cdap generated table if it uses our storage handler, or if a property is set on the table.
      String cdapName = null;
      Map<String, String> tableParameters = tableInfo.getParameters();
      if (tableParameters != null) {
        cdapName = tableParameters.get(Constants.Explore.CDAP_NAME);
      }
      // tables created after CDAP 2.6 should set the "cdap.name" property, but older ones
      // do not. So also check if it uses a cdap storage handler.
      String storageHandler = tableInfo.getParameters().get("storage_handler");
      boolean isDatasetTable = cdapName != null ||
        DatasetStorageHandler.class.getName().equals(storageHandler) ||
        StreamStorageHandler.class.getName().equals(storageHandler);

      return new TableInfo(tableInfo.getTableName(), tableInfo.getDbName(), tableInfo.getOwner(),
                           (long) tableInfo.getCreateTime() * 1000, (long) tableInfo.getLastAccessTime() * 1000,
                           tableInfo.getRetention(), partitionKeysBuilder.build(), tableInfo.getParameters(),
                           tableInfo.getTableType(), schemaBuilder.build(), tableInfo.getSd().getLocation(),
                           tableInfo.getSd().getInputFormat(), tableInfo.getSd().getOutputFormat(),
                           tableInfo.getSd().isCompressed(), tableInfo.getSd().getNumBuckets(),
                           tableInfo.getSd().getSerdeInfo().getSerializationLib(),
                           tableInfo.getSd().getSerdeInfo().getParameters(), isDatasetTable);
    } catch (NoSuchObjectException e) {
      throw new TableNotFoundException(e);
    } catch (TException e) {
      throw new ExploreException(e);
    }
  }

  @Override
  public QueryHandle getTableTypes() throws ExploreException, SQLException {
    startAndWait();

    try {
      SessionHandle sessionHandle = null;
      OperationHandle operationHandle = null;
      Map<String, String> sessionConf = startSession();

      try {
        sessionHandle = openHiveSession(sessionConf);
        operationHandle = cliService.getTableTypes(sessionHandle);
        QueryHandle handle = saveReadOnlyOperation(operationHandle, sessionHandle, sessionConf, "", "");
        LOG.trace("Retrieving table types");
        return handle;
      } catch (Throwable e) {
        closeInternal(getQueryHandle(sessionConf),
                      new ReadOnlyOperationInfo(sessionHandle, operationHandle, sessionConf, "", ""));
        throw e;
      }
    } catch (HiveSQLException e) {
      throw getSqlException(e);
    } catch (Throwable e) {
      throw new ExploreException(e);
    }
  }

  @Override
  public QueryHandle getTypeInfo() throws ExploreException, SQLException {
    startAndWait();

    try {
      SessionHandle sessionHandle = null;
      OperationHandle operationHandle = null;
      Map<String, String> sessionConf = startSession();

      try {
        sessionHandle = openHiveSession(sessionConf);
        operationHandle = cliService.getTypeInfo(sessionHandle);
        QueryHandle handle = saveReadOnlyOperation(operationHandle, sessionHandle, sessionConf, "", "");
        LOG.trace("Retrieving type info");
        return handle;
      } catch (Throwable e) {
        closeInternal(getQueryHandle(sessionConf),
                      new ReadOnlyOperationInfo(sessionHandle, operationHandle, sessionConf, "", ""));
        throw e;
      }
    } catch (HiveSQLException e) {
      throw getSqlException(e);
    } catch (Throwable e) {
      throw new ExploreException(e);
    }
  }

  @Override
  public QueryHandle createNamespace(Id.Namespace namespace) throws ExploreException, SQLException {
    startAndWait();

    try {
      // Even with the "IF NOT EXISTS" in the create command, Hive still logs a non-fatal warning internally
      // when attempting to create the "default" namespace (since it already exists in Hive).
      // This check prevents the extra warn log.
      if (Id.Namespace.DEFAULT.equals(namespace)) {
        return QueryHandle.NO_OP;
      }

      Map<String, String> sessionConf = startSession();
      SessionHandle sessionHandle = null;
      OperationHandle operationHandle = null;

      try {
        sessionHandle = cliService.openSession("", "", sessionConf);

        String database = getHiveDatabase(namespace.getId());
        // "IF NOT EXISTS" so that this operation is idempotent.
        String statement = String.format("CREATE DATABASE IF NOT EXISTS %s", database);
        operationHandle = doExecute(sessionHandle, statement);
        QueryHandle handle = saveReadOnlyOperation(operationHandle, sessionHandle, sessionConf, statement, database);
        LOG.info("Creating database {} with handle {}", namespace, handle);
        return handle;
      } catch (Throwable e) {
        closeInternal(getQueryHandle(sessionConf),
                      new ReadOnlyOperationInfo(sessionHandle, operationHandle, sessionConf, "", ""));
        throw e;
      }
    } catch (HiveSQLException e) {
      throw getSqlException(e);
    } catch (Throwable e) {
      throw new ExploreException(e);
    }
  }

  @Override
  public QueryHandle deleteNamespace(Id.Namespace namespace) throws ExploreException, SQLException {
    startAndWait();

    try {
      SessionHandle sessionHandle = null;
      OperationHandle operationHandle = null;
      Map<String, String> sessionConf = startSession();

      try {
        sessionHandle = openHiveSession(sessionConf);

        String database = getHiveDatabase(namespace.getId());
        String statement = String.format("DROP DATABASE %s", database);
        operationHandle = doExecute(sessionHandle, statement);
        QueryHandle handle = saveReadOnlyOperation(operationHandle, sessionHandle, sessionConf, statement, database);
        LOG.info("Deleting database {} with handle {}", database, handle);
        return handle;
      } catch (Throwable e) {
        closeInternal(getQueryHandle(sessionConf),
                      new ReadOnlyOperationInfo(sessionHandle, operationHandle, sessionConf, "", ""));
        throw e;
      }
    } catch (HiveSQLException e) {
      throw getSqlException(e);
    } catch (Throwable e) {
      throw new ExploreException(e);
    }
  }

  @Override
  public QueryHandle execute(Id.Namespace namespace, String statement) throws ExploreException, SQLException {
    startAndWait();

    try {
      SessionHandle sessionHandle = null;
      OperationHandle operationHandle = null;
      LOG.trace("Got statement: {}", statement);
      Map<String, String> sessionConf = startSession(namespace);
      try {
        sessionHandle = openHiveSession(sessionConf);
        String database = getHiveDatabase(namespace.getId());
        // Switch database to the one being passed in.
        setCurrentDatabase(database);

        operationHandle = doExecute(sessionHandle, statement);
        QueryHandle handle = saveReadWriteOperation(operationHandle, sessionHandle, sessionConf,
                                                    statement, database);
        LOG.trace("Executing statement: {} with handle {}", statement, handle);
        return handle;
      } catch (Throwable e) {
        closeInternal(getQueryHandle(sessionConf),
                      new ReadWriteOperationInfo(sessionHandle, operationHandle, sessionConf, "", ""));
        throw e;
      }
    } catch (HiveSQLException e) {
      throw getSqlException(e);
    } catch (Throwable e) {
      throw new ExploreException(e);
    }
  }

  @Override
  public QueryStatus getStatus(QueryHandle handle) throws ExploreException, HandleNotFoundException, SQLException {
    startAndWait();

    InactiveOperationInfo inactiveOperationInfo = inactiveHandleCache.getIfPresent(handle);
    if (inactiveOperationInfo != null) {
      // Operation has been made inactive, so return the saved status.
      LOG.trace("Returning saved status for inactive handle {}", handle);
      return inactiveOperationInfo.getStatus();
    }

    try {
      // Fetch status from Hive
      QueryStatus status = fetchStatus(getOperationInfo(handle));
      LOG.trace("Status of handle {} is {}", handle, status);

      // No results or error, so can be timed out aggressively
      if (status.getStatus() == QueryStatus.OpStatus.FINISHED && !status.hasResults()) {
        // In case of a query that writes to a Dataset, we will always fall into this condition,
        // and timing out aggressively will also close the transaction and make the writes visible
        timeoutAggressively(handle, getResultSchema(handle), status);
      } else if (status.getStatus() == QueryStatus.OpStatus.ERROR) {
        // getResultSchema will fail if the query is in error
        timeoutAggressively(handle, ImmutableList.<ColumnDesc>of(), status);
      }
      return status;
    } catch (HiveSQLException e) {
      throw getSqlException(e);
    }
  }

  @Override
  public List<QueryResult> nextResults(QueryHandle handle, int size)
    throws ExploreException, HandleNotFoundException, SQLException {
    startAndWait();

    InactiveOperationInfo inactiveOperationInfo = inactiveHandleCache.getIfPresent(handle);
    if (inactiveOperationInfo != null) {
      // Operation has been made inactive, so all results should have been fetched already - return empty list.
      LOG.trace("Returning empty result for inactive handle {}", handle);
      return ImmutableList.of();
    }

    try {
      List<QueryResult> results = fetchNextResults(handle, size);
      QueryStatus status = getStatus(handle);
      if (results.isEmpty() && status.getStatus() == QueryStatus.OpStatus.FINISHED) {
        // Since operation has fetched all the results, handle can be timed out aggressively.
        timeoutAggressively(handle, getResultSchema(handle), status);
      }
      return results;
    } catch (HiveSQLException e) {
      throw getSqlException(e);
    }
  }

  protected abstract List<QueryResult> doFetchNextResults(OperationHandle handle,
                                                          FetchOrientation fetchOrientation,
                                                          int size) throws Exception;

  @SuppressWarnings("unchecked")
  protected List<QueryResult> fetchNextResults(QueryHandle handle, int size)
    throws HiveSQLException, ExploreException, HandleNotFoundException {
    startAndWait();

    Lock nextLock = getOperationInfo(handle).getNextLock();
    nextLock.lock();
    try {
      // Fetch results from Hive
      LOG.trace("Getting results for handle {}", handle);
      OperationHandle operationHandle = getOperationHandle(handle);
      if (operationHandle.hasResultSet()) {
        return doFetchNextResults(operationHandle, FetchOrientation.FETCH_NEXT, size);
      } else {
        return Collections.emptyList();
      }
    } catch (Exception e) {
      throw Throwables.propagate(e);
    } finally {
      nextLock.unlock();
    }
  }

  @Override
  public List<QueryResult> previewResults(QueryHandle handle)
    throws ExploreException, HandleNotFoundException, SQLException {
    startAndWait();

    if (inactiveHandleCache.getIfPresent(handle) != null) {
      throw new HandleNotFoundException("Query is inactive.", true);
    }

    OperationInfo operationInfo = getOperationInfo(handle);
    Lock previewLock = operationInfo.getPreviewLock();
    previewLock.lock();
    try {
      File previewFile = operationInfo.getPreviewFile();
      if (previewFile != null) {
        try {
          Reader reader = com.google.common.io.Files.newReader(previewFile, Charsets.UTF_8);
          try {
            return GSON.fromJson(reader, new TypeToken<List<QueryResult>>() { }.getType());
          } finally {
            Closeables.closeQuietly(reader);
          }
        } catch (FileNotFoundException e) {
          LOG.error("Could not retrieve preview result file {}", previewFile, e);
          throw new ExploreException(e);
        }
      }

      try {
        // Create preview results for query
        previewFile = new File(previewsDir, handle.getHandle());
        try (FileWriter fileWriter = new FileWriter(previewFile)) {
          List<QueryResult> results = fetchNextResults(handle, PREVIEW_COUNT);
          GSON.toJson(results, fileWriter);
          operationInfo.setPreviewFile(previewFile);
          return results;
        }
      } catch (IOException e) {
        LOG.error("Could not write preview results into file", e);
        throw new ExploreException(e);
      }
    } finally {
      previewLock.unlock();
    }

  }

  @Override
  public List<ColumnDesc> getResultSchema(QueryHandle handle)
    throws ExploreException, HandleNotFoundException, SQLException {
    startAndWait();

    try {
      InactiveOperationInfo inactiveOperationInfo = inactiveHandleCache.getIfPresent(handle);
      if (inactiveOperationInfo != null) {
        // Operation has been made inactive, so return saved schema.
        LOG.trace("Returning saved schema for inactive handle {}", handle);
        return inactiveOperationInfo.getSchema();
      }

      // Fetch schema from hive
      LOG.trace("Getting schema for handle {}", handle);
      OperationHandle operationHandle = getOperationHandle(handle);
      return getResultSchemaInternal(operationHandle);
    } catch (HiveSQLException e) {
      throw getSqlException(e);
    }
  }

  protected List<ColumnDesc> getResultSchemaInternal(OperationHandle operationHandle) throws SQLException {
    ImmutableList.Builder<ColumnDesc> listBuilder = ImmutableList.builder();
    if (operationHandle.hasResultSet()) {
      TableSchema tableSchema = cliService.getResultSetMetadata(operationHandle);
      for (ColumnDescriptor colDesc : tableSchema.getColumnDescriptors()) {
        listBuilder.add(new ColumnDesc(colDesc.getName(), colDesc.getTypeName(),
                                       colDesc.getOrdinalPosition(), colDesc.getComment()));
      }
    }
    return listBuilder.build();
  }

  private void setCurrentDatabase(String dbName) throws Throwable {
    SessionState.get().setCurrentDatabase(dbName);
  }

  /**
   * Cancel a running Hive operation. After the operation moves into a {@link QueryStatus.OpStatus#CANCELED},
   * {@link #close(QueryHandle)} needs to be called to release resources.
   *
   * @param handle handle returned by {@link Explore#execute(Id.Namespace, String)}.
   * @throws ExploreException on any error cancelling operation.
   * @throws HandleNotFoundException when handle is not found.
   * @throws SQLException if there are errors in the SQL statement.
   */
  void cancelInternal(QueryHandle handle) throws ExploreException, HandleNotFoundException, SQLException {
    try {
      InactiveOperationInfo inactiveOperationInfo = inactiveHandleCache.getIfPresent(handle);
      if (inactiveOperationInfo != null) {
        // Operation has been made inactive, so no point in cancelling it.
        LOG.trace("Not running cancel for inactive handle {}", handle);
        return;
      }

      LOG.trace("Cancelling operation {}", handle);
      cliService.cancelOperation(getOperationHandle(handle));
    } catch (HiveSQLException e) {
      throw getSqlException(e);
    }
  }

  @Override
  public void close(QueryHandle handle) throws ExploreException, HandleNotFoundException {
    startAndWait();
    inactiveHandleCache.invalidate(handle);
    activeHandleCache.invalidate(handle);
  }

  @Override
  public List<QueryInfo> getQueries(Id.Namespace namespace) throws ExploreException, SQLException {
    startAndWait();

    List<QueryInfo> result = Lists.newArrayList();
    String namespaceHiveDb = getHiveDatabase(namespace.getId());
    for (Map.Entry<QueryHandle, OperationInfo> entry : activeHandleCache.asMap().entrySet()) {
      try {
        if (entry.getValue().getNamespace().equals(namespaceHiveDb)) {
          // we use empty query statement for get tables, get schemas, we don't need to return it this method call.
          if (!entry.getValue().getStatement().isEmpty()) {
            QueryStatus status = getStatus(entry.getKey());
            result.add(new QueryInfo(entry.getValue().getTimestamp(), entry.getValue().getStatement(),
                                     entry.getKey(), status, true));
          }
        }
      } catch (HandleNotFoundException e) {
        // ignore the handle not found exception. this method returns all queries and handle, if the
        // handle is removed from the internal cache, then there is no point returning them from here.
      }
    }

    for (Map.Entry<QueryHandle, InactiveOperationInfo> entry : inactiveHandleCache.asMap().entrySet()) {
      InactiveOperationInfo inactiveOperationInfo = entry.getValue();
      if (inactiveOperationInfo.getNamespace().equals(getHiveDatabase(namespace.getId()))) {
        // we use empty query statement for get tables, get schemas, we don't need to return it this method call.
        if (!inactiveOperationInfo.getStatement().isEmpty()) {
          if (inactiveOperationInfo.getStatus() == null) {
            LOG.error("Null status for query {}, handle {}", inactiveOperationInfo.getStatement(), entry.getKey());
          }
          result.add(new QueryInfo(inactiveOperationInfo.getTimestamp(),
                                   inactiveOperationInfo.getStatement(), entry.getKey(),
                                   inactiveOperationInfo.getStatus(), false));
        }
      }
    }
    Collections.sort(result);
    return result;
  }

  @Override
  public int getActiveQueryCount(Id.Namespace namespace) throws ExploreException {
    startAndWait();

    int count = 0;
    String namespaceHiveDb = getHiveDatabase(namespace.getId());
    for (Map.Entry<QueryHandle, OperationInfo> entry : activeHandleCache.asMap().entrySet()) {
      if (entry.getValue().getNamespace().equals(namespaceHiveDb)) {
        // we use empty query statement for get tables, get schemas, we don't need to return it this method call.
        if (!entry.getValue().getStatement().isEmpty()) {
          count++;
        }
      }
    }
    return count;
  }

  // this upgrade code is for upgrading CDAP v2.6 to v2.8 and above.
  @Override
  public void upgrade() throws Exception {
    // all old CDAP tables used to be in the default database
    LOG.info("Checking for tables that need upgrade...");
    List<TableNameInfo> tables = getTables("default");

    for (TableNameInfo tableNameInfo : tables) {
      String tableName = tableNameInfo.getTableName();
      TableInfo tableInfo = getTableInfo(tableNameInfo.getDatabaseName(), tableName);
      if (!requiresUpgrade(tableInfo)) {
        continue;
      }

      // wait for dataset service to come up. it will be needed when creating tables
      waitForDatasetService(600);

      String storageHandler = tableInfo.getParameters().get("storage_handler");
      if (StreamStorageHandler.class.getName().equals(storageHandler) && tableName.startsWith("cdap_")) {
        LOG.info("Upgrading stream table {}", tableName);
        upgradeStreamTable(tableInfo);
      } else if (DatasetStorageHandler.class.getName().equals(storageHandler) && tableName.startsWith("cdap_")) {
        LOG.info("Upgrading record scannable dataset table {}.", tableName);
        upgradeRecordScannableTable(tableInfo);
      } else if (tableName.startsWith("cdap_")) {
        LOG.info("Upgrading file set table {}.", tableName);
        // handle filesets differently since they can have partitions,
        // and dropping the table will remove all partitions
        upgradeFilesetTable(tableInfo);
      }
    }
  }

  private void waitForDatasetService(int secondsToWait) throws InterruptedException {
    int count = 0;
    LOG.info("Waiting for dataset service to come up before upgrading Explore.");
    while (count < secondsToWait) {
      try {
        datasetFramework.getInstances(Id.Namespace.DEFAULT);
        LOG.info("Dataset service is up and running, proceeding with explore upgrade.");
        return;
      } catch (Exception e) {
        count++;
        TimeUnit.SECONDS.sleep(1);
      }
    }
    LOG.error("Timed out waiting for dataset service to come up. Restart CDAP Master to upgrade old Hive tables.");
  }

  private void upgradeFilesetTable(TableInfo tableInfo) throws Exception {
    // these were only available starting from CDAP 2.7, which has the cdap name in table properties
    String dsName = tableInfo.getParameters().get(Constants.Explore.CDAP_NAME);
    // except the name was always prefixed by cdap.user.<name>
    dsName = dsName.substring("cdap.user.".length(), dsName.length());
    Id.DatasetInstance datasetID = Id.DatasetInstance.from(Id.Namespace.DEFAULT, dsName);
    DatasetSpecification spec = datasetFramework.getDatasetSpec(datasetID);

    // enable the new table
    enableDataset(datasetID, spec);

    try (SystemDatasetInstantiator datasetInstantiator = datasetInstantiatorFactory.create()) {
      Dataset dataset = datasetInstantiator.getDataset(datasetID);

      // if this is a time partitioned file set, we need to add all partitions
      if (dataset instanceof TimePartitionedFileSet) {
        TimePartitionedFileSet tpfs = (TimePartitionedFileSet) dataset;
        Set<PartitionDetail> partitionDetails = tpfs.getPartitions(null);
        if (!partitionDetails.isEmpty()) {
          QueryHandle handle = exploreTableManager.addPartitions(datasetID, partitionDetails);
          QueryStatus status = waitForCompletion(handle);
          // if add partitions failed, stop
          if (status.getStatus() != QueryStatus.OpStatus.FINISHED) {
            throw new ExploreException("Failed to add all partitions to dataset " + datasetID);
          }
        }
      }
    }

    // now it is safe to drop the old table
    dropTable(tableInfo.getTableName());
  }

  private void upgradeRecordScannableTable(TableInfo tableInfo) throws Exception {
    // get the dataset name from the serde properties.
    Map<String, String> serdeProperties = tableInfo.getSerdeParameters();
    String datasetName = serdeProperties.get(Constants.Explore.DATASET_NAME);
    // except the name was always prefixed by cdap.user.<name>
    datasetName = datasetName.substring("cdap.user.".length(), datasetName.length());
    Id.DatasetInstance datasetID = Id.DatasetInstance.from(Id.Namespace.DEFAULT, datasetName);
    DatasetSpecification spec = datasetFramework.getDatasetSpec(datasetID);

    // if there are no partitions, we can just enable the new table and drop the old one.
    enableDataset(datasetID, spec);
    dropTable(tableInfo.getTableName());
  }

  private void enableDataset(Id.DatasetInstance datasetID, DatasetSpecification spec) throws Exception {
    LOG.info("Enabling exploration on dataset {}", datasetID);
    QueryHandle enableHandle = exploreTableManager.enableDataset(datasetID, spec);
    // wait until enable is done
    QueryStatus status = waitForCompletion(enableHandle);
    // if enable failed, stop
    if (status.getStatus() != QueryStatus.OpStatus.FINISHED) {
      throw new ExploreException("Failed to enable exploration of dataset " + datasetID);
    }
  }

  private void dropTable(String tableName) throws Exception {
    LOG.info("Dropping old upgraded table {}", tableName);
    QueryHandle disableHandle = execute(Id.Namespace.DEFAULT, "DROP TABLE IF EXISTS " + tableName);
    // make sure disable finished
    QueryStatus status = waitForCompletion(disableHandle);
    if (status.getStatus() != QueryStatus.OpStatus.FINISHED) {
      throw new ExploreException("Failed to disable old Hive table " + tableName);
    }
  }

  private void upgradeStreamTable(TableInfo tableInfo) throws Exception {
    // get the stream name from the serde properties.
    Map<String, String> serdeProperties = tableInfo.getSerdeParameters();
    String streamName = serdeProperties.get(Constants.Explore.STREAM_NAME);
    Id.Stream streamID = Id.Stream.from(Id.Namespace.DEFAULT, streamName);

    // enable the table in the default namespace
    LOG.info("Enabling exploration on stream {}", streamID);
    StreamConfig streamConfig = streamAdmin.getConfig(streamID);
    QueryHandle enableHandle = exploreTableManager.enableStream(
      tableNaming.getTableName(streamID), streamID, streamConfig.getFormat());
    // wait til enable is done
    QueryStatus status = waitForCompletion(enableHandle);
    // if enable failed, stop
    if (status.getStatus() != QueryStatus.OpStatus.FINISHED) {
      throw new ExploreException("Failed to enable exploration of stream " + streamID);
    }

    // safe to disable old table now
    dropTable(tableInfo.getTableName());
  }

  private QueryStatus waitForCompletion(QueryHandle handle) throws HandleNotFoundException, SQLException,
    ExploreException, InterruptedException {
    QueryStatus status = getStatus(handle);
    while (!status.getStatus().isDone()) {
      TimeUnit.SECONDS.sleep(1);
      status = getStatus(handle);
    }
    return status;
  }

  private boolean requiresUpgrade(TableInfo tableInfo) {
    // if this is a cdap dataset.
    if (tableInfo.isBackedByDataset()) {
      String cdapVersion = tableInfo.getParameters().get(Constants.Explore.CDAP_VERSION);
      // for now, good enough to check if it contains the version or not.
      // In the future we can actually do version comparison with ProjectInfo.Version
      return cdapVersion == null;
    }
    return false;
  }

  void closeInternal(QueryHandle handle, OperationInfo opInfo)
    throws ExploreException, SQLException {
    try {
      LOG.trace("Closing operation {}", handle);
      if (opInfo.getOperationHandle() != null) {
        cliService.closeOperation(opInfo.getOperationHandle());
      }
    } catch (HiveSQLException e) {
      throw getSqlException(e);
    } finally {
      try {
        if (opInfo.getSessionHandle() != null) {
          closeHiveSession(opInfo.getSessionHandle());
        }
      } finally {
        cleanUp(handle, opInfo);
      }
    }
  }

  private SessionHandle openHiveSession(Map<String, String> sessionConf) throws HiveSQLException {
    SessionHandle sessionHandle = doOpenHiveSession(sessionConf);
    try {
      HiveStreamRedirector.redirectToLogger(SessionState.get());
    } catch (Throwable t) {
      LOG.error("Error redirecting Hive output streams to logs.", t);
    }

    return sessionHandle;
  }

  protected SessionHandle doOpenHiveSession(Map<String, String> sessionConf) throws HiveSQLException {
    return cliService.openSession("", "", sessionConf);
  }

  private void closeHiveSession(SessionHandle sessionHandle) {
    try {
      cliService.closeSession(sessionHandle);
    } catch (Throwable e) {
      LOG.error("Got error closing session", e);
    }
  }

  private String getHiveDatabase(@Nullable String namespace) {
    // null namespace implies that the operation happens across all databases
    if (namespace == null) {
      return null;
    }
    try {
      if (namespace.equals(Id.Namespace.DEFAULT.getId())) {
        return namespace;
      } else if (!Strings.isNullOrEmpty(
        namespaceQueryAdmin.get(Id.Namespace.from(namespace)).getConfig().getHiveDatabase())) {
        return namespaceQueryAdmin.get(Id.Namespace.from(namespace)).getConfig().getHiveDatabase();
      } else {
        String tablePrefix = cConf.get(Constants.Dataset.TABLE_PREFIX);
        return String.format("%s_%s", tablePrefix, namespace);
      }
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  /**
   * Starts a long running transaction, and also sets up session configuration.
   * @return configuration for a hive session that contains a transaction, and serialized CDAP configuration and
   * HBase configuration. This will be used by the map-reduce tasks started by Hive.
   * @throws IOException
   * @throws ExploreException
   */
  protected Map<String, String> startSession() throws IOException, ExploreException {
    return startSession(null);
  }

  protected Map<String, String> startSession(Id.Namespace namespace) throws IOException, ExploreException {
    Map<String, String> sessionConf = Maps.newHashMap();

    QueryHandle queryHandle = QueryHandle.generate();
    sessionConf.put(Constants.Explore.QUERY_ID, queryHandle.getHandle());

    String schedulerQueue = namespace != null ? schedulerQueueResolver.getQueue(namespace)
                                              : schedulerQueueResolver.getDefaultQueue();

    if (schedulerQueue != null && !schedulerQueue.isEmpty()) {
      sessionConf.put(JobContext.QUEUE_NAME, schedulerQueue);
    }

    Transaction tx = startTransaction();
    ConfigurationUtil.set(sessionConf, Constants.Explore.TX_QUERY_KEY, TxnCodec.INSTANCE, tx);
    ConfigurationUtil.set(sessionConf, Constants.Explore.CCONF_KEY, CConfCodec.INSTANCE, cConf);
    ConfigurationUtil.set(sessionConf, Constants.Explore.HCONF_KEY, HConfCodec.INSTANCE, hConf);

    if (ExploreServiceUtils.isSparkEngine(getHiveConf())) {
      sessionConf.putAll(sparkConf);
    }

    if (UserGroupInformation.isSecurityEnabled()) {
      // make sure RM does not cancel delegation tokens after the query is run
      sessionConf.put("mapreduce.job.complete.cancel.delegation.tokens", "false");
      sessionConf.put("spark.hadoop.mapreduce.job.complete.cancel.delegation.tokens", "false");
      // refresh delegations for the job - TWILL-170
      updateTokenStore();
    }

    return sessionConf;
  }

  /**
   * Updates the token store to be used for the hive job, based upon the Explore container's credentials.
   * This is because twill doesn't update the container_tokens on upon token refresh.
   * See: https://issues.apache.org/jira/browse/TWILL-170
   */
  private void updateTokenStore() throws IOException, ExploreException {
    String hadoopTokenFileLocation = System.getenv(UserGroupInformation.HADOOP_TOKEN_FILE_LOCATION);
    if (hadoopTokenFileLocation == null) {
      LOG.warn("Skipping update of token store due to failure to find environment variable '{}'.",
               UserGroupInformation.HADOOP_TOKEN_FILE_LOCATION);
      return;
    }

    Path credentialsFile = Paths.get(hadoopTokenFileLocation);

    FileAttribute<Set<PosixFilePermission>> originalPermissionAttributes =
      PosixFilePermissions.asFileAttribute(Files.getPosixFilePermissions(credentialsFile));

    Path tmpFile = Files.createTempFile(credentialsFile.getParent(), "credentials.store", null,
                                        originalPermissionAttributes);
    LOG.debug("Writing to temporary file: {}", tmpFile);

    try (DataOutputStream os = new DataOutputStream(Files.newOutputStream(tmpFile))) {
      Credentials credentials = UserGroupInformation.getCurrentUser().getCredentials();
      credentials.writeTokenStorageToStream(os);
    }

    Files.move(tmpFile, credentialsFile, StandardCopyOption.ATOMIC_MOVE);
    LOG.debug("Secure store saved to {}", credentialsFile);
  }

  protected QueryHandle getQueryHandle(Map<String, String> sessionConf) throws HandleNotFoundException {
    return QueryHandle.fromId(sessionConf.get(Constants.Explore.QUERY_ID));
  }

  /**
   * Returns {@link OperationHandle} associated with Explore {@link QueryHandle}.
   * @param handle explore handle.
   * @return OperationHandle.
   * @throws ExploreException
   */
  protected OperationHandle getOperationHandle(QueryHandle handle) throws ExploreException, HandleNotFoundException {
    return getOperationInfo(handle).getOperationHandle();
  }

  protected QueryStatus fetchStatus(OperationInfo operationInfo)
    throws ExploreException, HandleNotFoundException, HiveSQLException {
    QueryStatus queryStatus = doFetchStatus(operationInfo.getOperationHandle());
    operationInfo.setStatus(queryStatus);
    return queryStatus;
  }

  /**
   * Saves information associated with a Hive operation that is read-only on Datasets.
   * @param operationHandle {@link OperationHandle} of the Hive operation running.
   * @param sessionHandle {@link SessionHandle} for the Hive operation running.
   * @param sessionConf configuration for the session running the Hive operation.
   * @param statement SQL statement executed with the call.
   * @return {@link QueryHandle} that represents the Hive operation being run.
   */
  protected QueryHandle saveReadOnlyOperation(OperationHandle operationHandle, SessionHandle sessionHandle,
                                              Map<String, String> sessionConf, String statement, String namespace) {
    QueryHandle handle = QueryHandle.fromId(sessionConf.get(Constants.Explore.QUERY_ID));
    activeHandleCache.put(handle,
                          new ReadOnlyOperationInfo(sessionHandle, operationHandle, sessionConf, statement, namespace));
    return handle;
  }

  /**
   * Saves information associated with a Hive operation that writes to a Dataset.
   * @param operationHandle {@link OperationHandle} of the Hive operation running.
   * @param sessionHandle {@link SessionHandle} for the Hive operation running.
   * @param sessionConf configuration for the session running the Hive operation.
   * @param statement SQL statement executed with the call.
   * @return {@link QueryHandle} that represents the Hive operation being run.
   */
  protected QueryHandle saveReadWriteOperation(OperationHandle operationHandle, SessionHandle sessionHandle,
                                               Map<String, String> sessionConf, String statement, String namespace) {
    QueryHandle handle = QueryHandle.fromId(sessionConf.get(Constants.Explore.QUERY_ID));
    activeHandleCache.put(handle,
                          new ReadWriteOperationInfo(sessionHandle, operationHandle,
                                                     sessionConf, statement, namespace));
    return handle;
  }

  /**
   * Called after a handle has been used to fetch all its results. This handle can be timed out aggressively.
   * It also closes associated transaction.
   *
   * @param handle operation handle.
   */
  private void timeoutAggressively(QueryHandle handle, List<ColumnDesc> schema, QueryStatus status)
    throws HandleNotFoundException {
    OperationInfo opInfo = activeHandleCache.getIfPresent(handle);
    if (opInfo == null) {
      LOG.trace("Could not find OperationInfo for handle {}, it might already have been moved to inactive list",
                handle);
      return;
    }

    closeTransaction(handle, opInfo);

    LOG.trace("Timing out handle {} aggressively", handle);
    inactiveHandleCache.put(handle, new InactiveOperationInfo(opInfo, schema, status));
    activeHandleCache.invalidate(handle);
  }

  private OperationInfo getOperationInfo(QueryHandle handle) throws HandleNotFoundException {
    // First look in running handles and handles that still can be fetched.
    OperationInfo opInfo = activeHandleCache.getIfPresent(handle);
    if (opInfo != null) {
      return opInfo;
    }
    throw new HandleNotFoundException("Invalid handle provided");
  }

  /**
   * Cleans up the metadata associated with active {@link QueryHandle}. It also closes associated transaction.
   * @param handle handle of the running Hive operation.
   */
  protected void cleanUp(QueryHandle handle, OperationInfo opInfo) {
    try {
      if (opInfo.getPreviewFile() != null) {
        opInfo.getPreviewFile().delete();
      }
      closeTransaction(handle, opInfo);
    } finally {
      activeHandleCache.invalidate(handle);
    }
  }

  private Transaction startTransaction() throws IOException {
    Transaction tx = txClient.startLong();
    LOG.trace("Transaction {} started.", tx);
    return tx;
  }

  private void closeTransaction(QueryHandle handle, OperationInfo opInfo) {
    try {
      String txCommitted = opInfo.getSessionConf().get(Constants.Explore.TX_QUERY_CLOSED);
      if (txCommitted != null && Boolean.parseBoolean(txCommitted)) {
        LOG.trace("Transaction for handle {} has already been closed", handle);
        return;
      }

      Transaction tx = ConfigurationUtil.get(opInfo.getSessionConf(),
                                             Constants.Explore.TX_QUERY_KEY,
                                             TxnCodec.INSTANCE);
      LOG.trace("Closing transaction {} for handle {}", tx, handle);

      if (opInfo.isReadOnly() ||
        (opInfo.getStatus() != null && opInfo.getStatus().getStatus() == QueryStatus.OpStatus.FINISHED))  {
        if (!(txClient.commit(tx))) {
          txClient.invalidate(tx.getWritePointer());
          LOG.info("Invalidating transaction: {}", tx);
        }
      } else {
        txClient.invalidate(tx.getWritePointer());
      }
    } catch (Throwable e) {
      LOG.error("Got exception while closing transaction.", e);
    } finally {
      opInfo.getSessionConf().put(Constants.Explore.TX_QUERY_CLOSED, "true");
    }
  }

  private void runCacheCleanup() {
    LOG.trace("Running cache cleanup");
    activeHandleCache.cleanUp();
    inactiveHandleCache.cleanUp();
  }

  // Hive wraps all exceptions, including SQL exceptions in HiveSQLException. We would like to surface the SQL
  // exception to the user, and not other Hive server exceptions. We are using a heuristic to determine whether a
  // HiveSQLException is a SQL exception or not by inspecting the SQLState of HiveSQLException. If SQLState is not
  // null then we surface the SQL exception.
  private RuntimeException getSqlException(HiveSQLException e) throws ExploreException, SQLException {
    if (e.getSQLState() != null) {
      throw e;
    }
    throw new ExploreException(e);
  }

  protected Object tColumnToObject(TColumnValue tColumnValue) throws ExploreException {
    if (tColumnValue.isSetBoolVal()) {
      return tColumnValue.getBoolVal().isValue();
    } else if (tColumnValue.isSetByteVal()) {
      return tColumnValue.getByteVal().getValue();
    } else if (tColumnValue.isSetDoubleVal()) {
      return tColumnValue.getDoubleVal().getValue();
    } else if (tColumnValue.isSetI16Val()) {
      return tColumnValue.getI16Val().getValue();
    } else if (tColumnValue.isSetI32Val()) {
      return tColumnValue.getI32Val().getValue();
    } else if (tColumnValue.isSetI64Val()) {
      return tColumnValue.getI64Val().getValue();
    } else if (tColumnValue.isSetStringVal()) {
      return tColumnValue.getStringVal().getValue();
    }
    throw new ExploreException("Unknown column value encountered: " + tColumnValue);
  }
}
