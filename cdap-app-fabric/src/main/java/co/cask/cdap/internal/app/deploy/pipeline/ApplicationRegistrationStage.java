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

package co.cask.cdap.internal.app.deploy.pipeline;

import co.cask.cdap.api.app.ApplicationSpecification;
import co.cask.cdap.api.data.stream.StreamBatchReadable;
import co.cask.cdap.api.flow.FlowSpecification;
import co.cask.cdap.api.flow.FlowletConnection;
import co.cask.cdap.api.flow.FlowletDefinition;
import co.cask.cdap.api.mapreduce.MapReduceSpecification;
import co.cask.cdap.api.service.ServiceSpecification;
import co.cask.cdap.api.service.http.HttpServiceHandlerSpecification;
import co.cask.cdap.app.store.Store;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.data2.registry.UsageRegistry;
import co.cask.cdap.pipeline.AbstractStage;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.id.ApplicationId;
import co.cask.cdap.proto.id.NamespaceId;
import com.google.common.reflect.TypeToken;

import java.net.URI;

/**
 *
 */
public class ApplicationRegistrationStage extends AbstractStage<ApplicationWithPrograms> {

  private final Store store;
  private final UsageRegistry usageRegistry;

  public ApplicationRegistrationStage(Store store, UsageRegistry usageRegistry) {
    super(TypeToken.of(ApplicationWithPrograms.class));
    this.store = store;
    this.usageRegistry = usageRegistry;
  }

  @Override
  public void process(ApplicationWithPrograms input) throws Exception {
    store.addApplication(input.getApplicationId().toId(), input.getSpecification());
    registerDatasets(input);
    emit(input);
  }

  // Register dataset usage, based upon the program specifications.
  // Note that worker specifications' datasets are not registered upon app deploy because the useDataset of the
  // WorkerConfigurer is deprecated. Workers' access to datasets is aimed to be completely dynamic. Other programs are
  // moving in this direction.
  // Also, SparkSpecifications are the same in that a Spark program's dataset access is completely dynamic.
  private void registerDatasets(ApplicationWithPrograms input) {
    ApplicationSpecification appSpec = input.getSpecification();
    ApplicationId appId = input.getApplicationId();
    NamespaceId namespaceId = appId.getParent();

    for (FlowSpecification flow : appSpec.getFlows().values()) {
      Id.Program programId = appId.flow(flow.getName()).toId();
      for (FlowletConnection connection : flow.getConnections()) {
        if (connection.getSourceType().equals(FlowletConnection.Type.STREAM)) {
          usageRegistry.register(programId, namespaceId.stream(connection.getSourceName()).toId());
        }
      }
      for (FlowletDefinition flowlet : flow.getFlowlets().values()) {
        for (String dataset : flowlet.getDatasets()) {
          usageRegistry.register(programId, namespaceId.dataset(dataset).toId());
        }
      }
    }

    for (MapReduceSpecification program : appSpec.getMapReduce().values()) {
      Id.Program programId = appId.mr(program.getName()).toId();
      for (String dataset : program.getDataSets()) {
        if (!dataset.startsWith(Constants.Stream.URL_PREFIX)) {
          usageRegistry.register(programId, namespaceId.dataset(dataset).toId());
        }
      }
      String inputDatasetName = program.getInputDataSet();
      if (inputDatasetName != null && inputDatasetName.startsWith(Constants.Stream.URL_PREFIX)) {
        StreamBatchReadable stream = new StreamBatchReadable(URI.create(inputDatasetName));
        usageRegistry.register(programId, namespaceId.stream(stream.getStreamName()).toId());
      }
    }

    for (ServiceSpecification serviceSpecification : appSpec.getServices().values()) {
      Id.Program programId = appId.service(serviceSpecification.getName()).toId();
      for (HttpServiceHandlerSpecification handlerSpecification : serviceSpecification.getHandlers().values()) {
        for (String dataset : handlerSpecification.getDatasets()) {
          usageRegistry.register(programId, namespaceId.dataset(dataset).toId());
        }
      }
    }
  }
}
