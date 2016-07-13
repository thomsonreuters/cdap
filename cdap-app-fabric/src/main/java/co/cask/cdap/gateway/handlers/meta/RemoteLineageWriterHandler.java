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

package co.cask.cdap.gateway.handlers.meta;

import co.cask.cdap.data2.metadata.lineage.AccessType;
import co.cask.cdap.data2.metadata.writer.LineageWriter;
import co.cask.cdap.internal.app.store.remote.MethodArgument;
import co.cask.cdap.proto.Id;
import co.cask.http.HttpResponder;
import com.google.inject.Inject;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import java.util.Iterator;
import javax.ws.rs.POST;
import javax.ws.rs.Path;

/**
 * The {@link co.cask.http.HttpHandler} for handling REST calls to LineageStore.
 */
@Path(AbstractRemoteSystemOpsHandler.VERSION + "/execute")
public class RemoteLineageWriterHandler extends AbstractRemoteSystemOpsHandler {

  private final LineageWriter lineageWriter;

  @Inject
  public RemoteLineageWriterHandler(LineageWriter lineageWriter) {
    this.lineageWriter = lineageWriter;
  }

  @POST
  @Path("/addDatasetAccess")
  public void addDatasetAccess(HttpRequest request, HttpResponder responder) throws Exception {
    Iterator<MethodArgument> arguments = parseArguments(request);

    Id.Run run = deserializeNext(arguments);
    Id.DatasetInstance datasetInstance = deserializeNext(arguments);
    AccessType accessType = deserializeNext(arguments);
    Id.NamespacedId component = deserializeNext(arguments);
    lineageWriter.addAccess(run, datasetInstance, accessType, component);

    responder.sendStatus(HttpResponseStatus.OK);
  }

  @POST
  @Path("/addStreamAccess")
  public void addStreamAccess(HttpRequest request, HttpResponder responder) throws Exception {
    Iterator<MethodArgument> arguments = parseArguments(request);

    Id.Run run = deserializeNext(arguments);
    Id.Stream stream = deserializeNext(arguments);
    AccessType accessType = deserializeNext(arguments);
    Id.NamespacedId component = deserializeNext(arguments);
    lineageWriter.addAccess(run, stream, accessType, component);

    responder.sendStatus(HttpResponseStatus.OK);
  }
}
