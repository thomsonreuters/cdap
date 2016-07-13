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

package co.cask.cdap.internal.app.store.remote;

import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.data2.metadata.lineage.AccessType;
import co.cask.cdap.data2.metadata.writer.BasicLineageWriter;
import co.cask.cdap.data2.metadata.writer.LineageWriter;
import co.cask.cdap.proto.Id;
import com.google.inject.Inject;
import org.apache.twill.discovery.DiscoveryServiceClient;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

/**
 * Implementation of LineageWriter, which uses an HTTP Client to execute the actual lineage writing in a remote
 * server.
 */
public class RemoteLineageWriter extends RemoteOpsClient implements LineageWriter {

  private final ConcurrentMap<BasicLineageWriter.DataAccessKey, Boolean> registered = new ConcurrentHashMap<>();

  @Inject
  RemoteLineageWriter(CConfiguration cConf, DiscoveryServiceClient discoveryClient) {
    super(cConf, discoveryClient);
  }

  @Override
  public void addAccess(Id.Run run, Id.DatasetInstance datasetInstance, AccessType accessType) {
    // delegates on client side; so corresponding method is not required to be implemented on server side
    addAccess(run, datasetInstance, accessType, null);
  }

  @Override
  public void addAccess(Id.Run run, Id.DatasetInstance datasetInstance, AccessType accessType,
                        @Nullable Id.NamespacedId component) {
    if (alreadyRegistered(run, datasetInstance, accessType, component)) {
      return;
    }
    executeRequest("addDatasetAccess", run, datasetInstance, accessType, component);
  }

  @Override
  public void addAccess(Id.Run run, Id.Stream stream, AccessType accessType) {
    // delegates on client side; so corresponding method is not required to be implemented on server side
    addAccess(run, stream, accessType, null);
  }

  @Override
  public void addAccess(Id.Run run, Id.Stream stream, AccessType accessType, @Nullable Id.NamespacedId component) {
    if (alreadyRegistered(run, stream, accessType, component)) {
      return;
    }
    executeRequest("addStreamAccess", run, stream, accessType, component);
  }

  private boolean alreadyRegistered(Id.Run run, Id.NamespacedId data, AccessType accessType,
                                    @Nullable Id.NamespacedId component) {
    return registered.putIfAbsent(new BasicLineageWriter.DataAccessKey(run, data, accessType, component), true) != null;
  }
}
