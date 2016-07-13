/*
 * Copyright © 2016 Cask Data, Inc.
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

package co.cask.cdap.app.runtime.spark;

import co.cask.cdap.api.metrics.MetricsCollectionService;
import co.cask.cdap.app.guice.DistributedProgramRunnableModule;
import co.cask.cdap.app.program.DefaultProgram;
import co.cask.cdap.app.program.Program;
import co.cask.cdap.app.program.ProgramDescriptor;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.io.Locations;
import co.cask.cdap.common.lang.ClassLoaders;
import co.cask.cdap.common.lang.ProgramClassLoader;
import co.cask.cdap.data.stream.StreamCoordinatorClient;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.data2.metadata.writer.ProgramContextAware;
import co.cask.cdap.data2.transaction.stream.StreamAdmin;
import co.cask.cdap.internal.app.runtime.plugin.PluginInstantiator;
import co.cask.cdap.internal.app.runtime.workflow.NameMappedDatasetFramework;
import co.cask.cdap.internal.app.runtime.workflow.WorkflowProgramInfo;
import co.cask.cdap.logging.appender.LogAppenderInitializer;
import co.cask.cdap.proto.Id;
import co.cask.tephra.TransactionSystemClient;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Service;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.hadoop.conf.Configuration;
import org.apache.twill.discovery.DiscoveryServiceClient;
import org.apache.twill.internal.Services;
import org.apache.twill.kafka.client.KafkaClientService;
import org.apache.twill.zookeeper.ZKClientService;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Helper class for locating or creating {@link SparkRuntimeContext} from the execution context.
 */
public final class SparkRuntimeContextProvider {

  // Constants defined for file names used for files localization done by the SparkRuntimeService.
  // They are needed for recreating the SparkRuntimeContext in this class.
  static final String CCONF_FILE_NAME = "cConf.xml";
  static final String HCONF_FILE_NAME = "hConf.xml";
  // The suffix has to be .jar, otherwise YARN don't expand it
  static final String PROGRAM_JAR_EXPANDED_NAME = "program.expanded.jar";
  static final String PROGRAM_JAR_NAME = "program.jar";

  private static volatile SparkRuntimeContext sparkRuntimeContext;

  /**
   * Returns the current {@link SparkRuntimeContext}.
   */
  public static SparkRuntimeContext get() {
    if (sparkRuntimeContext != null) {
      return sparkRuntimeContext;
    }

    // Try to find it from the context classloader
    SparkClassLoader sparkClassLoader = ClassLoaders.find(Thread.currentThread().getContextClassLoader(),
                                                          SparkClassLoader.class);
    if (sparkClassLoader != null) {
      // Shouldn't set the sparkContext field. It is because in Standalone, the SparkContext instance will be different
      // for different runs, hence it shouldn't be cached in the static field.
      // In distributed mode, in the driver, the SparkContext will always come from ClassLoader (like in Standalone).
      // Although it can be cached, finding it from ClassLoader with a very minimal performance impact is ok.
      // In the executor process, the static field will be set through the createIfNotExists() call
      // when get called for the first time.
      return sparkClassLoader.getRuntimeContext();
    }
    return createIfNotExists();
  }

  /**
   * Creates a singleton {@link SparkRuntimeContext}.
   * It has assumption on file location that are localized by the SparkRuntimeService.
   */
  private static synchronized SparkRuntimeContext createIfNotExists() {
    if (sparkRuntimeContext != null) {
      return sparkRuntimeContext;
    }

    try {
      CConfiguration cConf = createCConf();
      Configuration hConf = createHConf();

      SparkRuntimeContextConfig contextConfig = new SparkRuntimeContextConfig(hConf);

      // Should be yarn only and only for executor node, not the driver node.
      Preconditions.checkState(!contextConfig.isLocal() && Boolean.parseBoolean(System.getenv("SPARK_YARN_MODE")),
                               "SparkContextProvider.getSparkContext should only be called in Spark executor process.");

      // Create the program
      Program program = createProgram(cConf, contextConfig);

      Injector injector = createInjector(cConf, hConf);

      final Service logAppenderService = new LogAppenderService(injector.getInstance(LogAppenderInitializer.class));
      final ZKClientService zkClientService = injector.getInstance(ZKClientService.class);
      final KafkaClientService kafkaClientService = injector.getInstance(KafkaClientService.class);
      final MetricsCollectionService metricsCollectionService = injector.getInstance(MetricsCollectionService.class);
      final StreamCoordinatorClient streamCoordinatorClient = injector.getInstance(StreamCoordinatorClient.class);

      // Use the shutdown hook to shutdown services, since this class should only be loaded from System classloader
      // of the spark executor, hence there should be exactly one instance only.
      // The problem with not shutting down nicely is that some logs/metrics might be lost
      Services.chainStart(logAppenderService, zkClientService,
                          kafkaClientService, metricsCollectionService, streamCoordinatorClient);
      Runtime.getRuntime().addShutdownHook(new Thread() {
        @Override
        public void run() {
          // The logger may already been shutdown. Use System.out/err instead
          System.out.println("Shutting SparkClassLoader services");
          Future<List<ListenableFuture<Service.State>>> future = Services.chainStop(logAppenderService,
                                                                                    streamCoordinatorClient,
                                                                                    metricsCollectionService,
                                                                                    kafkaClientService,
                                                                                    zkClientService);
          try {
            List<ListenableFuture<Service.State>> futures = future.get(5, TimeUnit.SECONDS);
            System.out.println("SparkClassLoader services shutdown completed: " + futures);
          } catch (Exception e) {
            System.err.println("Exception when shutting down services");
            e.printStackTrace(System.err);
          }
        }
      });

      // Constructor the DatasetFramework
      DatasetFramework datasetFramework = injector.getInstance(DatasetFramework.class);
      WorkflowProgramInfo workflowInfo = contextConfig.getWorkflowProgramInfo();
      DatasetFramework programDatasetFramework = workflowInfo == null ?
        datasetFramework :
        NameMappedDatasetFramework.createFromWorkflowProgramInfo(datasetFramework, workflowInfo,
                                                                 contextConfig.getApplicationSpecification());

      // Setup dataset framework context, if required
      if (programDatasetFramework instanceof ProgramContextAware) {
        Id.Run id = new Id.Run(contextConfig.getProgramId().toId(), contextConfig.getRunId().getId());
        ((ProgramContextAware) programDatasetFramework).initContext(id);
      }

      PluginInstantiator pluginInstantiator = createPluginInstantiator(cConf, hConf, program.getClassLoader());

      // Create the context object
      sparkRuntimeContext = new SparkRuntimeContext(
        contextConfig.getConfiguration(),
        program, contextConfig.getRunId(), contextConfig.getArguments(),
        injector.getInstance(TransactionSystemClient.class),
        programDatasetFramework,
        injector.getInstance(DiscoveryServiceClient.class),
        metricsCollectionService,
        injector.getInstance(StreamAdmin.class),
        contextConfig.getWorkflowProgramInfo(),
        pluginInstantiator
      );
      return sparkRuntimeContext;
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  private static CConfiguration createCConf() throws MalformedURLException {
    return CConfiguration.create(new File(CCONF_FILE_NAME));
  }

  private static Configuration createHConf() throws MalformedURLException {
    Configuration hConf = new Configuration();
    hConf.clear();
    hConf.addResource(new File(HCONF_FILE_NAME).toURI().toURL());
    return hConf;
  }

  private static Program createProgram(CConfiguration cConf,
                                       SparkRuntimeContextConfig contextConfig) throws IOException {
    File programJar = new File(PROGRAM_JAR_NAME);
    File programDir = new File(PROGRAM_JAR_EXPANDED_NAME);
    ProgramClassLoader classLoader = SparkRuntimeUtils.createProgramClassLoader(
      cConf, programDir, SparkRuntimeContextProvider.class.getClassLoader());
    return new DefaultProgram(new ProgramDescriptor(contextConfig.getProgramId(),
                                                    contextConfig.getApplicationSpecification()),
                              Locations.toLocation(programJar), classLoader);
  }

  @Nullable
  private static PluginInstantiator createPluginInstantiator(CConfiguration cConf, Configuration hConf,
                                                             ClassLoader parentClassLoader) {
    String pluginArchive = hConf.get(Constants.Plugin.ARCHIVE);
    if (pluginArchive == null) {
      return null;
    }
    return new PluginInstantiator(cConf, parentClassLoader, new File(pluginArchive));
  }

  private static Injector createInjector(CConfiguration cConf, Configuration hConf) {
    return Guice.createInjector(new DistributedProgramRunnableModule(cConf, hConf).createModule());
  }

  /**
   * A guava {@link Service} implementation for starting and stopping {@link LogAppenderInitializer}.
   */
  private static final class LogAppenderService extends AbstractService {

    private final LogAppenderInitializer initializer;

    private LogAppenderService(LogAppenderInitializer initializer) {
      this.initializer = initializer;
    }

    @Override
    protected void doStart() {
      try {
        initializer.initialize();
        notifyStarted();
      } catch (Throwable t) {
        notifyFailed(t);
      }
    }

    @Override
    protected void doStop() {
      try {
        initializer.close();
        notifyStopped();
      } catch (Throwable t) {
        notifyFailed(t);
      }
    }
  }

  private SparkRuntimeContextProvider() {
    // no-op
  }
}
