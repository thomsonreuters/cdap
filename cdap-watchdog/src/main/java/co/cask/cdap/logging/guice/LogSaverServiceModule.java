/*
 * Copyright © 2014 Cask Data, Inc.
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

package co.cask.cdap.logging.guice;

import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.gateway.handlers.CommonHandlers;
import co.cask.cdap.logging.save.KafkaLogProcessor;
import co.cask.cdap.logging.save.KafkaLogWriterPlugin;
import co.cask.cdap.logging.save.LogMetricsPlugin;
import co.cask.cdap.logging.save.LogSaverFactory;
import co.cask.cdap.logging.service.LogSaverStatusService;
import co.cask.http.HttpHandler;
import com.google.inject.PrivateModule;
import com.google.inject.Scopes;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;

/**
 * Module for LogSaver
 */
public class LogSaverServiceModule extends PrivateModule {

  @Override
  protected void configure() {
    Multibinder<KafkaLogProcessor> logProcessorBinder = Multibinder.newSetBinder
      (binder(), KafkaLogProcessor.class, Names.named(Constants.LogSaver.MESSAGE_PROCESSORS));
    logProcessorBinder.addBinding().to(KafkaLogWriterPlugin.class);
    logProcessorBinder.addBinding().to(LogMetricsPlugin.class);

    Multibinder<HttpHandler> handlerBinder = Multibinder.newSetBinder
      (binder(), HttpHandler.class, Names.named(Constants.LogSaver.LOG_SAVER_STATUS_HANDLER));
    CommonHandlers.add(handlerBinder);
    bind(LogSaverStatusService.class).in(Scopes.SINGLETON);
    expose(LogSaverStatusService.class);

    install(new FactoryModuleBuilder().build(LogSaverFactory.class));
    expose(LogSaverFactory.class);
  }
}
