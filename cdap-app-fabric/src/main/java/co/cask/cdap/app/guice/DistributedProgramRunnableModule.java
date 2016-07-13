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

package co.cask.cdap.app.guice;

import co.cask.cdap.api.data.stream.StreamWriter;
import co.cask.cdap.app.store.RuntimeStore;
import co.cask.cdap.app.stream.DefaultStreamWriter;
import co.cask.cdap.app.stream.StreamWriterFactory;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.guice.ConfigModule;
import co.cask.cdap.common.guice.DiscoveryRuntimeModule;
import co.cask.cdap.common.guice.IOModule;
import co.cask.cdap.common.guice.KafkaClientModule;
import co.cask.cdap.common.guice.LocationRuntimeModule;
import co.cask.cdap.common.guice.ZKClientModule;
import co.cask.cdap.data.runtime.DataFabricModules;
import co.cask.cdap.data.runtime.DataSetsModules;
import co.cask.cdap.data.stream.StreamAdminModules;
import co.cask.cdap.data.view.ViewAdminModules;
import co.cask.cdap.data2.audit.AuditModule;
import co.cask.cdap.data2.metadata.writer.LineageWriter;
import co.cask.cdap.data2.registry.RuntimeUsageRegistry;
import co.cask.cdap.explore.guice.ExploreClientModule;
import co.cask.cdap.internal.app.queue.QueueReaderFactory;
import co.cask.cdap.internal.app.store.remote.RemoteLineageWriter;
import co.cask.cdap.internal.app.store.remote.RemotePrivilegesFetcher;
import co.cask.cdap.internal.app.store.remote.RemoteRuntimeStore;
import co.cask.cdap.internal.app.store.remote.RemoteRuntimeUsageRegistry;
import co.cask.cdap.logging.guice.LoggingModules;
import co.cask.cdap.metrics.guice.MetricsClientRuntimeModule;
import co.cask.cdap.notifications.feeds.client.NotificationFeedClientModule;
import co.cask.cdap.security.authorization.AuthorizationEnforcementService;
import co.cask.cdap.security.authorization.DefaultAuthorizationEnforcementService;
import co.cask.cdap.security.spi.authorization.AuthorizationEnforcer;
import co.cask.cdap.security.spi.authorization.PrivilegesFetcher;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.PrivateModule;
import com.google.inject.Scopes;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.google.inject.name.Names;
import com.google.inject.util.Modules;
import org.apache.hadoop.conf.Configuration;
import org.apache.twill.api.ServiceAnnouncer;
import org.apache.twill.api.TwillContext;
import org.apache.twill.common.Cancellable;

import java.net.InetAddress;

/**
 * Defines guice modules for distributed program runnables. For instance, AbstractProgramTwillRunnable, as well as
 * mapreduce tasks / spark executors.
 */
public class DistributedProgramRunnableModule {

  private final CConfiguration cConf;
  private final Configuration hConf;

  public DistributedProgramRunnableModule(CConfiguration cConf, Configuration hConf) {
    this.cConf = cConf;
    this.hConf = hConf;
  }

  // usable from any program runtime, such as mapreduce task, spark task, etc
  public Module createModule() {
    Module combined = Modules.combine(
      new ConfigModule(cConf, hConf),
      new IOModule(),
      new ZKClientModule(),
      new KafkaClientModule(),
      new MetricsClientRuntimeModule().getDistributedModules(),
      new LocationRuntimeModule().getDistributedModules(),
      new LoggingModules().getDistributedModules(),
      new DiscoveryRuntimeModule().getDistributedModules(),
      new DataFabricModules().getDistributedModules(),
      new DataSetsModules().getDistributedModules(),
      // why do we need explore here?
      new ExploreClientModule(),
      new ViewAdminModules().getDistributedModules(),
      new StreamAdminModules().getDistributedModules(),
      new NotificationFeedClientModule(),
      new AuditModule().getDistributedModules(),
      new AuthorizationModule(),
      new AbstractModule() {
        @Override
        protected void configure() {
          // For Binding queue stuff
          bind(QueueReaderFactory.class).in(Scopes.SINGLETON);

          // For binding DataSet transaction stuff
          install(new DataFabricFacadeModule());

          bind(RuntimeStore.class).to(RemoteRuntimeStore.class);

          // For binding StreamWriter
          install(createStreamFactoryModule());

          // also bind AuthorizationEnforcementService as a singleton. This binding is used while starting/stopping
          // the service itself.
          bind(AuthorizationEnforcementService.class).to(DefaultAuthorizationEnforcementService.class)
            .in(Scopes.SINGLETON);
          // bind AuthorizationEnforcer to AuthorizationEnforcementService
          bind(AuthorizationEnforcer.class).to(AuthorizationEnforcementService.class).in(Scopes.SINGLETON);
          bind(PrivilegesFetcher.class).to(RemotePrivilegesFetcher.class);
        }
      }
    );

    return Modules.override(combined).with(new AbstractModule() {
      @Override
      protected void configure() {
        bind(LineageWriter.class).to(RemoteLineageWriter.class);
        bind(RuntimeUsageRegistry.class).to(RemoteRuntimeUsageRegistry.class);
      }
    });
  }

  // TODO(terence) make this works for different mode
  // usable from anywhere a TwillContext is exposed
  public Module createModule(final TwillContext context) {
    return Modules.combine(createModule(),
                           new AbstractModule() {
                             @Override
                             protected void configure() {
                               bind(InetAddress.class).annotatedWith(Names.named(Constants.AppFabric.SERVER_ADDRESS))
                                 .toInstance(context.getHost());

                               bind(ServiceAnnouncer.class).toInstance(new ServiceAnnouncer() {
                                 @Override
                                 public Cancellable announce(String serviceName, int port) {
                                   return context.announce(serviceName, port);
                                 }
                               });
                             }
                           });
  }

  private Module createStreamFactoryModule() {
    return new PrivateModule() {
      @Override
      protected void configure() {
        install(new FactoryModuleBuilder().implement(StreamWriter.class, DefaultStreamWriter.class)
                  .build(StreamWriterFactory.class));
        expose(StreamWriterFactory.class);
      }
    };
  }
}
