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

package co.cask.cdap.logging;

import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.guice.LocationUnitTestModule;
import co.cask.cdap.data.runtime.DataSetsModules;
import co.cask.cdap.data.runtime.SystemDatasetRuntimeModule;
import co.cask.cdap.data.runtime.TransactionExecutorModule;
import co.cask.cdap.kafka.KafkaTester;
import co.cask.cdap.logging.guice.LoggingModules;
import co.cask.cdap.logging.save.KafkaLogProcessor;
import co.cask.cdap.logging.save.KafkaLogWriterPlugin;
import co.cask.cdap.logging.save.LogMetricsPlugin;
import co.cask.cdap.logging.save.LogSaverFactory;
import co.cask.cdap.metrics.guice.MetricsClientRuntimeModule;
import co.cask.tephra.runtime.TransactionModules;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.AbstractModule;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;
import org.junit.ClassRule;

/**
 * Base test class that start up Kafka during at the beginning of the test, and stop Kafka when test is done.
 */
public abstract class KafkaTestBase {
  @ClassRule
  public static final KafkaTester KAFKA_TESTER = new KafkaTester(
    ImmutableMap.<String, String>builder()
      .put(LoggingConfiguration.NUM_PARTITIONS, "2")
      .put(LoggingConfiguration.KAFKA_PRODUCER_TYPE, "sync")
      .put(LoggingConfiguration.KAFKA_PROCUDER_BUFFER_MS, "100")
      .put(LoggingConfiguration.LOG_RETENTION_DURATION_DAYS, "10")
      .put(LoggingConfiguration.LOG_MAX_FILE_SIZE_BYTES, "10240")
      .put(LoggingConfiguration.LOG_FILE_SYNC_INTERVAL_BYTES, "5120")
      .put(LoggingConfiguration.LOG_SAVER_EVENT_BUCKET_INTERVAL_MS, "100")
      .put(LoggingConfiguration.LOG_SAVER_MAXIMUM_INMEMORY_EVENT_BUCKETS, "2")
      .put(LoggingConfiguration.LOG_SAVER_TOPIC_WAIT_SLEEP_MS, "10")
      .build(),
    ImmutableList.of(
      new LocationUnitTestModule().getModule(),
      new TransactionModules().getInMemoryModules(),
      new TransactionExecutorModule(),
      new DataSetsModules().getInMemoryModules(),
      new SystemDatasetRuntimeModule().getInMemoryModules(),
      new MetricsClientRuntimeModule().getInMemoryModules(),
      new LoggingModules().getDistributedModules(),
      new AbstractModule() {
        @Override
        protected void configure() {
          Multibinder<KafkaLogProcessor> logProcessorBinder = Multibinder.newSetBinder
            (binder(), KafkaLogProcessor.class, Names.named(Constants.LogSaver.MESSAGE_PROCESSORS));
          logProcessorBinder.addBinding().to(KafkaLogWriterPlugin.class);
          logProcessorBinder.addBinding().to(LogMetricsPlugin.class);

          install(new FactoryModuleBuilder().build(LogSaverFactory.class));
        }
      }
    ),
    2,
    LoggingConfiguration.KAFKA_SEED_BROKERS
  );
}
