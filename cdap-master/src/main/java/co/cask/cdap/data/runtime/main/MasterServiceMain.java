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

package co.cask.cdap.data.runtime.main;

import co.cask.cdap.api.metrics.MetricsCollectionService;
import co.cask.cdap.app.guice.AppFabricServiceRuntimeModule;
import co.cask.cdap.app.guice.AuthorizationModule;
import co.cask.cdap.app.guice.ProgramRunnerRuntimeModule;
import co.cask.cdap.app.guice.ServiceStoreModules;
import co.cask.cdap.app.store.ServiceStore;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.guice.ConfigModule;
import co.cask.cdap.common.guice.DiscoveryRuntimeModule;
import co.cask.cdap.common.guice.IOModule;
import co.cask.cdap.common.guice.KafkaClientModule;
import co.cask.cdap.common.guice.LocationRuntimeModule;
import co.cask.cdap.common.guice.TwillModule;
import co.cask.cdap.common.guice.ZKClientModule;
import co.cask.cdap.common.io.URLConnections;
import co.cask.cdap.common.kerberos.SecurityUtil;
import co.cask.cdap.common.runtime.DaemonMain;
import co.cask.cdap.common.service.RetryOnStartFailureService;
import co.cask.cdap.common.service.RetryStrategies;
import co.cask.cdap.common.twill.HadoopClassExcluder;
import co.cask.cdap.common.utils.DirUtils;
import co.cask.cdap.data.runtime.DataFabricModules;
import co.cask.cdap.data.runtime.DataSetServiceModules;
import co.cask.cdap.data.runtime.DataSetsModules;
import co.cask.cdap.data.stream.StreamAdminModules;
import co.cask.cdap.data.view.ViewAdminModules;
import co.cask.cdap.data2.audit.AuditModule;
import co.cask.cdap.data2.datafabric.dataset.service.DatasetService;
import co.cask.cdap.data2.util.hbase.ConfigurationTable;
import co.cask.cdap.data2.util.hbase.HBaseTableUtil;
import co.cask.cdap.data2.util.hbase.HBaseTableUtilFactory;
import co.cask.cdap.explore.client.ExploreClient;
import co.cask.cdap.explore.guice.ExploreClientModule;
import co.cask.cdap.explore.service.ExploreServiceUtils;
import co.cask.cdap.hive.ExploreUtils;
import co.cask.cdap.internal.app.services.AppFabricServer;
import co.cask.cdap.logging.appender.LogAppenderInitializer;
import co.cask.cdap.logging.guice.LoggingModules;
import co.cask.cdap.metrics.guice.MetricsClientRuntimeModule;
import co.cask.cdap.metrics.guice.MetricsStoreModule;
import co.cask.cdap.notifications.feeds.guice.NotificationFeedServiceRuntimeModule;
import co.cask.cdap.notifications.guice.NotificationServiceRuntimeModule;
import co.cask.cdap.proto.Id;
import co.cask.cdap.security.TokenSecureStoreUpdater;
import co.cask.cdap.security.authorization.AuthorizerInstantiator;
import co.cask.cdap.store.guice.NamespaceStoreModule;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Closeables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.twill.api.ElectionHandler;
import org.apache.twill.api.TwillApplication;
import org.apache.twill.api.TwillController;
import org.apache.twill.api.TwillPreparer;
import org.apache.twill.api.TwillRunnerService;
import org.apache.twill.api.logging.LogEntry;
import org.apache.twill.api.logging.LogHandler;
import org.apache.twill.api.logging.PrinterLogHandler;
import org.apache.twill.common.Cancellable;
import org.apache.twill.common.Threads;
import org.apache.twill.internal.ServiceListenerAdapter;
import org.apache.twill.internal.zookeeper.LeaderElection;
import org.apache.twill.kafka.client.KafkaClientService;
import org.apache.twill.zookeeper.ZKClient;
import org.apache.twill.zookeeper.ZKClientService;
import org.apache.twill.zookeeper.ZKOperations;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Driver class for starting all master services.
 * AppFabricHttpService
 * TwillRunnables: MetricsProcessor, MetricsHttp, LogSaver, TransactionService, StreamHandler.
 */
public class MasterServiceMain extends DaemonMain {
  private static final Logger LOG = LoggerFactory.getLogger(MasterServiceMain.class);

  private static final long MAX_BACKOFF_TIME_MS = TimeUnit.MILLISECONDS.convert(10, TimeUnit.MINUTES);
  private static final long SUCCESSFUL_RUN_DURATON_MS = TimeUnit.MILLISECONDS.convert(20, TimeUnit.MINUTES);

  // Maximum time to try looking up the existing twill application
  private static final long LOOKUP_ATTEMPT_TIMEOUT_MS = 2000;

  private final CConfiguration cConf;
  private final Configuration hConf;
  private final ZKClientService zkClient;
  private final LeaderElection leaderElection;
  private final LogAppenderInitializer logAppenderInitializer;

  private volatile boolean stopped;

  static {
    try {
      // Workaround for release of file descriptors opened by URLClassLoader - https://issues.cask.co/browse/CDAP-2841
      URLConnections.setDefaultUseCaches(false);
    } catch (IOException e) {
      LOG.error("Could not disable caching of URLJarFiles. This may lead to 'too many open files` exception.", e);
    }
  }

  public static void main(final String[] args) throws Exception {
    LOG.info("Starting {}", MasterServiceMain.class.getSimpleName());
    new MasterServiceMain().doMain(args);
  }

  public MasterServiceMain() {
    CConfiguration cConf = CConfiguration.create();
    cConf.set(Constants.Dataset.Manager.ADDRESS, getLocalHost().getCanonicalHostName());

    // Note: login has to happen before any objects that need Kerberos credentials are instantiated.
    login(cConf);

    Configuration hConf = HBaseConfiguration.create();

    Injector injector = createProcessInjector(cConf, hConf);
    this.cConf = injector.getInstance(CConfiguration.class);
    this.hConf = injector.getInstance(Configuration.class);
    this.logAppenderInitializer = injector.getInstance(LogAppenderInitializer.class);
    this.zkClient = injector.getInstance(ZKClientService.class);
    this.leaderElection = createLeaderElection();
    // leader election will normally stay running. Will only stop if there was some issue starting up.
    this.leaderElection.addListener(new ServiceListenerAdapter() {
      @Override
      public void terminated(Service.State from) {
        if (!stopped) {
          LOG.error("CDAP Master failed to start");
          System.exit(1);
        }
      }

      @Override
      public void failed(Service.State from, Throwable failure) {
        if (!stopped) {
          LOG.error("CDAP Master failed to start");
          System.exit(1);
        }
      }
    }, MoreExecutors.sameThreadExecutor());
  }

  @Override
  public void init(String[] args) {
    cleanupTempDir();
    checkExploreRequirements();
  }

  @Override
  public void start() {
    logAppenderInitializer.initialize();

    createSystemHBaseNamespace();
    updateConfigurationTable();

    zkClient.startAndWait();
    // Tries to create the ZK root node (which can be namespaced through the zk connection string)
    Futures.getUnchecked(ZKOperations.ignoreError(zkClient.create("/", null, CreateMode.PERSISTENT),
                                                  KeeperException.NodeExistsException.class, null));

    leaderElection.startAndWait();
  }

  @Override
  public void stop() {
    LOG.info("Stopping {}", Constants.Service.MASTER_SERVICES);
    stopped = true;

    // if leader election failed to start, its listener will stop the master.
    // In that case, we don't want to try stopping it again, as it will log confusing exceptions
    if (leaderElection.isRunning()) {
      stopQuietly(leaderElection);
    }
    stopQuietly(zkClient);
  }

  @Override
  public void destroy() {
    // no-op
  }

  /**
   * Gets an instance of the given {@link Service} class from the given {@link Injector}, start the service and
   * returns it.
   */
  private <T extends Service> T getAndStart(Injector injector, Class<T> cls) {
    T service = injector.getInstance(cls);
    LOG.info("Starting service in master {}", service);
    service.startAndWait();
    return service;
  }

  /**
   * Stops a guava {@link Service}. No exception will be thrown even stopping failed.
   */
  private void stopQuietly(@Nullable Service service) {
    try {
      if (service != null) {
        LOG.info("Stopping service in master: {}", service);
        service.stopAndWait();
      }
    } catch (Exception e) {
      LOG.warn("Exception when stopping service {}", service, e);
    }
  }

  /**
   * Stops a guava {@link Service}. No exception will be thrown even stopping failed.
   */
  private void stopQuietly(@Nullable TwillRunnerService service) {
    try {
      if (service != null) {
        service.stop();
      }
    } catch (Exception e) {
      LOG.warn("Exception when stopping service {}", service, e);
    }
  }

  private InetAddress getLocalHost() {
    try {
      return InetAddress.getLocalHost();
    } catch (UnknownHostException e) {
      LOG.error("Error obtaining localhost address", e);
      throw Throwables.propagate(e);
    }
  }

  /**
   * Returns a map from system service name to a map from property to configuration key.
   */
  private Map<String, Map<String, String>> getConfigKeys() {
    Map<String, Map<String, String>> configKeys = Maps.newHashMap();

    configKeys.put(Constants.Service.LOGSAVER,
                   ImmutableMap.of("default", Constants.LogSaver.NUM_INSTANCES,
                                   "max", Constants.LogSaver.MAX_INSTANCES));
    configKeys.put(Constants.Service.TRANSACTION,
                   ImmutableMap.of("default", Constants.Transaction.Container.NUM_INSTANCES,
                                   "max", Constants.Transaction.Container.MAX_INSTANCES));
    configKeys.put(Constants.Service.METRICS_PROCESSOR,
                   ImmutableMap.of("default", Constants.MetricsProcessor.NUM_INSTANCES,
                                   "max", Constants.MetricsProcessor.MAX_INSTANCES));
    configKeys.put(Constants.Service.METRICS,
                   ImmutableMap.of("default", Constants.Metrics.NUM_INSTANCES,
                                   "max", Constants.Metrics.MAX_INSTANCES));
    configKeys.put(Constants.Service.STREAMS,
                   ImmutableMap.of("default", Constants.Stream.CONTAINER_INSTANCES,
                                   "max", Constants.Stream.MAX_INSTANCES));
    configKeys.put(Constants.Service.DATASET_EXECUTOR,
                   ImmutableMap.of("default", Constants.Dataset.Executor.CONTAINER_INSTANCES,
                                   "max", Constants.Dataset.Executor.MAX_INSTANCES));
    configKeys.put(Constants.Service.EXPLORE_HTTP_USER_SERVICE,
                   ImmutableMap.of("default", Constants.Explore.CONTAINER_INSTANCES,
                                   "max", Constants.Explore.MAX_INSTANCES));
    return configKeys;
  }

  private Map<String, Integer> getSystemServiceInstances(ServiceStore serviceStore) {
    Map<String, Integer> instanceCountMap = new HashMap<>();
    for (Map.Entry<String, Map<String, String>> entry : getConfigKeys().entrySet()) {
      String service = entry.getKey();
      Map<String, String> configKeys = entry.getValue();
      try {
        int maxCount = cConf.getInt(configKeys.get("max"));

        Integer savedCount = serviceStore.getServiceInstance(service);
        if (savedCount == null || savedCount == 0) {
          savedCount = Math.min(maxCount, cConf.getInt(configKeys.get("default")));
        } else {
          // If the max value is smaller than the saved instance count, update the store to the max value.
          if (savedCount > maxCount) {
            savedCount = maxCount;
          }
        }

        serviceStore.setServiceInstance(service, savedCount);
        instanceCountMap.put(service, savedCount);
        LOG.info("Setting instance count of {} Service to {}", service, savedCount);
      } catch (Exception e) {
        LOG.error("Couldn't retrieve instance count {}: {}", service, e.getMessage(), e);
      }
    }
    return instanceCountMap;
  }


  /**
   * Creates an unstarted {@link LeaderElection} for the master service.
   */
  private LeaderElection createLeaderElection() {
    String electionPath = "/election/" + Constants.Service.MASTER_SERVICES;
    return new LeaderElection(zkClient, electionPath, new MasterLeaderElectionHandler(cConf, hConf, zkClient));
  }

  /**
   * Cleanup the cdap system temp directory.
   */
  private void cleanupTempDir() {
    File tmpDir = new File(cConf.get(Constants.CFG_LOCAL_DATA_DIR),
                           cConf.get(Constants.AppFabric.TEMP_DIR)).getAbsoluteFile();

    if (!tmpDir.isDirectory()) {
      return;
    }

    try {
      DirUtils.deleteDirectoryContents(tmpDir, true);
    } catch (IOException e) {
      // It's ok not able to cleanup temp directory.
      LOG.debug("Failed to cleanup temp directory {}", tmpDir, e);
    }
  }

  /**
   * Check that if Explore is enabled, the correct jars are present on master node,
   * and that the distribution of Hive is supported.
   */
  private void checkExploreRequirements() {
    if (cConf.getBoolean(Constants.Explore.EXPLORE_ENABLED)) {
      // This check will throw an exception if Hive is not present or if it's distribution is unsupported
      ExploreServiceUtils.checkHiveSupport();
    }
  }

  /**
   * Performs kerbose login if security is enabled.
   */
  private void login(CConfiguration cConf) {
    try {
      SecurityUtil.loginForMasterService(cConf);
    } catch (Exception e) {
      LOG.error("Failed to login as CDAP user", e);
      throw Throwables.propagate(e);
    }
  }

  /**
   * Creates HBase namespace for the cdap system namespace.
   */
  private void createSystemHBaseNamespace() {
    HBaseTableUtil tableUtil = new HBaseTableUtilFactory(cConf, null).get();
    try (HBaseAdmin admin = new HBaseAdmin(hConf)) {
      tableUtil.createNamespaceIfNotExists(admin, Id.Namespace.SYSTEM);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  /**
   * The transaction coprocessors (0.94 and 0.96 versions of {@code DefaultTransactionProcessor}) need access
   * to CConfiguration values in order to load transaction snapshots for data cleanup.
   */
  private void updateConfigurationTable() {
    try {
      new ConfigurationTable(hConf).write(ConfigurationTable.Type.DEFAULT, cConf);
    } catch (IOException ioe) {
      throw Throwables.propagate(ioe);
    }
  }

  /**
   * Creates a guice {@link Injector} used by this master service process.
   */
  @VisibleForTesting
  static Injector createProcessInjector(CConfiguration cConf, Configuration hConf) {
    return Guice.createInjector(
      new ConfigModule(cConf, hConf),
      new ZKClientModule(),
      new LoggingModules().getDistributedModules()
    );
  }

  /**
   * Creates a guice {@link Injector} to be used when this master service becomes leader.
   */
  @VisibleForTesting
  static Injector createLeaderInjector(CConfiguration cConf, Configuration hConf,
                                       final ZKClientService zkClientService) {
    return Guice.createInjector(
      new ConfigModule(cConf, hConf),
      new AbstractModule() {
        @Override
        protected void configure() {
          // Instead of using ZKClientModule that will create new instance of ZKClient, we create instance
          // binding to reuse the same ZKClient used for leader election
          bind(ZKClient.class).toInstance(zkClientService);
          bind(ZKClientService.class).toInstance(zkClientService);
        }
      },
      new LoggingModules().getDistributedModules(),
      new LocationRuntimeModule().getDistributedModules(),
      new IOModule(),
      new KafkaClientModule(),
      new DiscoveryRuntimeModule().getDistributedModules(),
      new DataSetServiceModules().getDistributedModules(),
      new DataFabricModules().getDistributedModules(),
      new DataSetsModules().getDistributedModules(),
      new MetricsClientRuntimeModule().getDistributedModules(),
      new MetricsStoreModule(),
      new ExploreClientModule(),
      new NotificationFeedServiceRuntimeModule().getDistributedModules(),
      new NotificationServiceRuntimeModule().getDistributedModules(),
      new ViewAdminModules().getDistributedModules(),
      new StreamAdminModules().getDistributedModules(),
      new NamespaceStoreModule().getDistributedModules(),
      new AuditModule().getDistributedModules(),
      new AuthorizationModule(),
      new TwillModule(),
      new ServiceStoreModules().getDistributedModules(),
      new AppFabricServiceRuntimeModule().getDistributedModules(),
      new ProgramRunnerRuntimeModule().getDistributedModules()
    );
  }

  /**
   * The {@link ElectionHandler} for handling leader election lifecycle for the master process.
   */
  private final class MasterLeaderElectionHandler implements ElectionHandler {

    private final CConfiguration cConf;
    private final Configuration hConf;
    private final ZKClientService zkClient;
    private final AtomicReference<TwillController> controller = new AtomicReference<>();
    private final List<Service> services = new ArrayList<>();

    private Injector injector;
    private Cancellable secureStoreUpdateCancellable;
    // Executor for re-running master twill app if it gets terminated.
    private ScheduledExecutorService executor;
    private AuthorizerInstantiator authorizerInstantiator;
    private TwillRunnerService twillRunner;
    private ServiceStore serviceStore;
    private TokenSecureStoreUpdater secureStoreUpdater;
    private ExploreClient exploreClient;

    private MasterLeaderElectionHandler(CConfiguration cConf, Configuration hConf, ZKClientService zkClient) {
      this.cConf = cConf;
      this.hConf = hConf;
      this.zkClient = zkClient;
    }

    @Override
    public void leader() {
      LOG.info("Became leader for master services");

      // We need to create a new injector each time becoming leader so that new instances of singleton Services
      // will be created
      injector = createLeaderInjector(cConf, hConf, zkClient);

      if (cConf.getBoolean(Constants.Explore.EXPLORE_ENABLED)) {
        exploreClient = injector.getInstance(ExploreClient.class);
      }

      authorizerInstantiator = injector.getInstance(AuthorizerInstantiator.class);

      services.add(getAndStart(injector, KafkaClientService.class));
      services.add(getAndStart(injector, MetricsCollectionService.class));
      serviceStore = getAndStart(injector, ServiceStore.class);
      services.add(serviceStore);

      twillRunner = injector.getInstance(TwillRunnerService.class);
      twillRunner.start();

      secureStoreUpdater = injector.getInstance(TokenSecureStoreUpdater.class);

      // Schedule secure store update.
      if (User.isHBaseSecurityEnabled(hConf) || UserGroupInformation.isSecurityEnabled()) {
        secureStoreUpdateCancellable = twillRunner.scheduleSecureStoreUpdate(secureStoreUpdater, 30000L,
                                                                             secureStoreUpdater.getUpdateInterval(),
                                                                             TimeUnit.MILLISECONDS);
      }

      // Create app-fabric and dataset services
      services.add(new RetryOnStartFailureService(new Supplier<Service>() {
        @Override
        public Service get() {
          return injector.getInstance(DatasetService.class);
        }
      }, RetryStrategies.exponentialDelay(200, 5000, TimeUnit.MILLISECONDS)));
      services.add(injector.getInstance(AppFabricServer.class));

      executor = Executors.newSingleThreadScheduledExecutor(Threads.createDaemonThreadFactory("master-runner"));

      // Start monitoring twill application
      monitorTwillApplication(executor, 0, controller, twillRunner, serviceStore, secureStoreUpdater);

      // Starts all services.
      for (Service service : services) {
        if (service.isRunning()) {
          // Some services already started (e.g. MetricsCollectionService, KafkaClientService)
          continue;
        }
        LOG.info("Starting service in master: {}", service);
        try {
          service.startAndWait();
        } catch (Throwable t) {
          // shut down the executor and stop the twill app,
          // then throw an exception to cause the leader election service to stop
          // leaderelection's listener will then shutdown the master
          stop(true);
          throw new RuntimeException(String.format("Unable to start service %s: %s", service, t.getMessage()));
        }
      }
      LOG.info("CDAP Master started successfully.");
    }

    @Override
    public void follower() {
      LOG.info("Became follower for master services");
      stop(stopped);
    }

    private void stop(boolean shouldTerminateApp) {
      // Shutdown the retry executor so that no re-run of the twill app will be attempted
      if (executor != null) {
        executor.shutdownNow();
      }
      // Stop secure store update
      if (secureStoreUpdateCancellable != null) {
        secureStoreUpdateCancellable.cancel();
      }
      // If the master process has been explcitly stopped, stop the twill application as well.
      if (shouldTerminateApp) {
        LOG.info("Stopping master twill application");
        TwillController twillController = controller.get();
        if (twillController != null) {
          Futures.getUnchecked(twillController.terminate());
        }
      }
      // Stop local services last since DatasetService is running locally
      // and remote services need it to preserve states.
      for (Service service : Lists.reverse(services)) {
        stopQuietly(service);
      }
      services.clear();
      stopQuietly(twillRunner);
      Closeables.closeQuietly(authorizerInstantiator);
      Closeables.closeQuietly(exploreClient);
    }


    /**
     * Monitors the twill application for master services running through Twill.
     *
     * @param executor executor for re-running the application if it gets terminated
     * @param failures number of failures in starting the application
     * @param serviceController the reference to be updated with the active {@link TwillController}
     */
    private void monitorTwillApplication(final ScheduledExecutorService executor, final int failures,
                                         final AtomicReference<TwillController> serviceController,
                                         final TwillRunnerService twillRunner, final ServiceStore serviceStore,
                                         final TokenSecureStoreUpdater secureStoreUpdater) {
      if (executor.isShutdown()) {
        return;
      }

      // Determines if the application is running. If not, starts a new one.
      final long startTime;
      TwillController controller = getCurrentTwillController(twillRunner);
      if (controller != null) {
        startTime = 0L;
      } else {
        try {
          controller = startTwillApplication(twillRunner, serviceStore, secureStoreUpdater);
        } catch (Exception e) {
          LOG.error("Failed to start master twill application", e);
          throw e;
        }
        startTime = System.currentTimeMillis();
      }

      // Monitor the application
      serviceController.set(controller);
      controller.onTerminated(new Runnable() {
        @Override
        public void run() {
          if (executor.isShutdown()) {
            return;
          }
          LOG.warn("{} was terminated; restarting with back-off", Constants.Service.MASTER_SERVICES);
          backoffRun();
        }

        private void backoffRun() {
          if (System.currentTimeMillis() - startTime > SUCCESSFUL_RUN_DURATON_MS) {
            // Restart immediately
            executor.execute(new Runnable() {
              @Override
              public void run() {
                monitorTwillApplication(executor, 0, serviceController, twillRunner, serviceStore, secureStoreUpdater);
              }
            });
            return;
          }

          long nextRunTime = Math.min(500 * (long) Math.pow(2, failures + 1), MAX_BACKOFF_TIME_MS);
          executor.schedule(new Runnable() {
            @Override
            public void run() {
              monitorTwillApplication(executor, failures + 1, serviceController,
                                      twillRunner, serviceStore, secureStoreUpdater);
            }
          }, nextRunTime, TimeUnit.MILLISECONDS);
        }
      }, Threads.SAME_THREAD_EXECUTOR);
    }

    /**
     * Returns the {@link TwillController} for the current master service or {@code null} if none is running.
     */
    @Nullable
    private TwillController getCurrentTwillController(TwillRunnerService twillRunner) {
      int count = 100;
      long sleepMs = LOOKUP_ATTEMPT_TIMEOUT_MS / count;

      // Try to lookup the existing twill application
      for (int i = 0; i < count; i++) {
        TwillController result = null;
        for (TwillController controller : twillRunner.lookup(Constants.Service.MASTER_SERVICES)) {
          if (result != null) {
            LOG.warn("Stopping one extra instance of {}", Constants.Service.MASTER_SERVICES);
            try {
              controller.terminate();
              controller.awaitTerminated();
            } catch (ExecutionException e) {
              LOG.warn("Exception while Stopping one extra instance of {} - {}", Constants.Service.MASTER_SERVICES, e);
            }
          } else {
            result = controller;
          }
        }
        if (result != null) {
          return result;
        }
        try {
          TimeUnit.MILLISECONDS.sleep(sleepMs);
        } catch (InterruptedException e) {
          break;
        }
      }
      return null;
    }

    /**
     * Starts the {@link TwillApplication} for the master services.
     *
     * @return The {@link TwillController} for the application.
     */
    private TwillController startTwillApplication(TwillRunnerService twillRunner,
                                                  ServiceStore serviceStore,
                                                  TokenSecureStoreUpdater secureStoreUpdater) {
      try {
        // Create a temp dir for the run to hold temporary files created to run the application
        Path tempPath = Files.createDirectories(new File(cConf.get(Constants.CFG_LOCAL_DATA_DIR),
                                                         cConf.get(Constants.AppFabric.TEMP_DIR)).toPath());
        final Path runDir = Files.createTempDirectory(tempPath, "master");
        try {
          Path cConfFile = saveCConf(cConf, runDir.resolve("cConf.xml"));
          Path hConfFile = saveHConf(hConf, runDir.resolve("hConf.xml"));
          Path logbackFile = saveLogbackConf(runDir.resolve("logback.xml"));

          TwillPreparer preparer = twillRunner.prepare(
            new MasterTwillApplication(cConf, cConfFile.toFile(), hConfFile.toFile(),
                                       getSystemServiceInstances(serviceStore)));

          if (cConf.getBoolean(Constants.COLLECT_CONTAINER_LOGS)) {
            if (LOG instanceof ch.qos.logback.classic.Logger) {
              preparer.addLogHandler(new LogHandler() {
                @Override
                public void onLog(LogEntry entry) {
                  ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LOG;
                  logger.callAppenders(new TwillLogEntryAdapter(entry));
                }
              });
            } else {
              LOG.warn("Unsupported logger binding ({}) for container log collection. Falling back to System.out.",
                       LOG.getClass().getName());
              preparer.addLogHandler(new PrinterLogHandler(new PrintWriter(System.out)));
            }
          } else {
            preparer.addJVMOptions("-Dtwill.disable.kafka=true");
          }

          // Add logback xml
          if (Files.exists(logbackFile)) {
            preparer.withResources().withResources(logbackFile.toUri());
          }

          // Add yarn queue name if defined
          String queueName = cConf.get(Constants.Service.SCHEDULER_QUEUE);
          if (queueName != null) {
            LOG.info("Setting scheduler queue to {} for master services", queueName);
            preparer.setSchedulerQueue(queueName);
          }

          // Add HBase dependencies
          preparer.withDependencies(injector.getInstance(HBaseTableUtil.class).getClass());

          // Add secure tokens
          if (User.isHBaseSecurityEnabled(hConf) || UserGroupInformation.isSecurityEnabled()) {
            // TokenSecureStoreUpdater.update() ignores parameters
            preparer.addSecureStore(secureStoreUpdater.update(null, null));
          }

          // add hadoop classpath to application classpath and exclude hadoop classes from bundle jar.
          String yarnAppClassPath = Joiner.on(",").join(YarnConfiguration.DEFAULT_YARN_APPLICATION_CLASSPATH);
          yarnAppClassPath = hConf.get(YarnConfiguration.YARN_APPLICATION_CLASSPATH, yarnAppClassPath);

          preparer.withApplicationClassPaths(Splitter.on(",").trimResults().split(yarnAppClassPath))
            .withBundlerClassAcceptor(new HadoopClassExcluder());

          // Add explore dependencies
          if (cConf.getBoolean(Constants.Explore.EXPLORE_ENABLED)) {
            prepareExploreContainer(preparer);
          }

          // Add a listener to delete temp files when application started/terminated.
          TwillController controller = preparer.start();
          Runnable cleanup = new Runnable() {
            @Override
            public void run() {
              try {
                File dir = runDir.toFile();
                if (dir.isDirectory()) {
                  DirUtils.deleteDirectoryContents(dir);
                }
              } catch (IOException e) {
                LOG.warn("Failed to cleanup directory {}", runDir, e);
              }
            }
          };
          controller.onRunning(cleanup, Threads.SAME_THREAD_EXECUTOR);
          controller.onTerminated(cleanup, Threads.SAME_THREAD_EXECUTOR);
          return controller;
        } catch (Exception e) {
          try {
            DirUtils.deleteDirectoryContents(runDir.toFile());
          } catch (IOException ex) {
            LOG.warn("Failed to cleanup directory {}", runDir, ex);
            e.addSuppressed(ex);
          }
          throw e;
        }
      } catch (IOException e) {
        throw Throwables.propagate(e);
      }
    }

    /**
     * Prepare the specs of the twill application for the Explore twill runnable.
     * Add jars needed by the Explore module in the classpath of the containers, and
     * add conf files (hive_site.xml, etc) as resources available for the Explore twill
     * runnable.
     */
    private TwillPreparer prepareExploreContainer(TwillPreparer preparer) {
      try {
        // Put jars needed by Hive in the containers classpath. Those jars are localized in the Explore
        // container by MasterTwillApplication, so they are available for ExploreServiceTwillRunnable
        File tempDir = DirUtils.createTempDir(new File(cConf.get(Constants.CFG_LOCAL_DATA_DIR),
                                                       cConf.get(Constants.AppFabric.TEMP_DIR)).getAbsoluteFile());
        Set<File> jars = ExploreServiceUtils.traceExploreDependencies(tempDir);
        for (File jarFile : jars) {
          LOG.trace("Adding jar file to classpath: {}", jarFile.getName());
          preparer = preparer.withClassPaths(jarFile.getName());
        }
      } catch (IOException e) {
        throw new RuntimeException("Unable to trace Explore dependencies", e);
      }

      // EXPLORE_CONF_FILES will be defined in startup scripts if Hive is installed.
      String hiveConfFiles = System.getProperty(Constants.Explore.EXPLORE_CONF_FILES);
      LOG.debug("Hive conf files = {}", hiveConfFiles);
      if (hiveConfFiles == null) {
        throw new RuntimeException("System property " + Constants.Explore.EXPLORE_CONF_FILES + " is not set");
      }

      // Add all the conf files needed by hive as resources available to containers
      File tempDir = DirUtils.createTempDir(new File(cConf.get(Constants.CFG_LOCAL_DATA_DIR),
                                                     cConf.get(Constants.AppFabric.TEMP_DIR)).getAbsoluteFile());
      Iterable<File> hiveConfFilesFiles = ExploreUtils.getClassPathJarsFiles(hiveConfFiles);
      Set<String> addedFiles = Sets.newHashSet();
      for (File file : hiveConfFilesFiles) {
        if (file.getName().matches(".*\\.xml") && !file.getName().equals("logback.xml")) {
          if (addedFiles.add(file.getName())) {
            LOG.debug("Adding config file: {}", file.getAbsolutePath());
            preparer = preparer.withResources(ExploreServiceUtils.updateConfFileForExplore(file, tempDir).toURI());
          } else {
            LOG.warn("Ignoring duplicate config file: {}", file.getAbsolutePath());
          }
        }
      }

      return preparer;
    }

    private Path saveCConf(CConfiguration cConf, Path file) throws IOException {
      CConfiguration copied = CConfiguration.copy(cConf);
      // Set the CFG_LOCAL_DATA_DIR to a relative path as the data directory for the container should be relative to the
      // container directory
      copied.set(Constants.CFG_LOCAL_DATA_DIR, "data");
      try (Writer writer = Files.newBufferedWriter(file, Charsets.UTF_8)) {
        copied.writeXml(writer);
      }
      return file;
    }

    private Path saveHConf(Configuration conf, Path file) throws IOException {
      try (Writer writer = Files.newBufferedWriter(file, Charsets.UTF_8)) {
        conf.writeXml(writer);
      }
      return file;
    }

    private Path saveLogbackConf(Path file) throws IOException {
      // Default to system logback if the container logback is not found.
      URL logbackResource = getClass().getResource("/logback-container.xml");
      if (logbackResource == null) {
        logbackResource = getClass().getResource("/logback.xml");
      }
      if (logbackResource != null) {
        try (InputStream input = logbackResource.openStream()) {
          Files.copy(input, file);
        }
      } else {
        LOG.warn("Cannot find logback.xml.");
      }

      return file;
    }
  }
}
