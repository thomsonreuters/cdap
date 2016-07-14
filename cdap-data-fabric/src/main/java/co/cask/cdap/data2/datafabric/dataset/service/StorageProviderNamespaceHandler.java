/*
 * Copyright © 2015 Cask Data, Inc.
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

package co.cask.cdap.data2.datafabric.dataset.service;

import co.cask.cdap.common.NamespaceNotFoundException;
import co.cask.cdap.common.UnauthenticatedException;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.explore.service.ExploreException;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.NamespaceConfig;
import co.cask.http.AbstractHttpHandler;
import co.cask.http.HttpHandler;
import co.cask.http.HttpResponder;
import com.google.common.base.Charsets;
import com.google.gson.Gson;
import com.google.inject.Inject;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import java.io.IOException;
import java.sql.SQLException;
import javax.ws.rs.DELETE;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

/**
 * {@link HttpHandler} for admin operations on underlying systems - Filesystem, HBase, Hive.
 */
@Path(Constants.Gateway.API_VERSION_3 + "/namespaces/{namespace-id}")
public class StorageProviderNamespaceHandler extends AbstractHttpHandler {
  private static final Gson GSON = new Gson();
  private final StorageProviderNamespaceAdmin storageProviderNamespaceAdmin;

  @Inject
  public StorageProviderNamespaceHandler(StorageProviderNamespaceAdmin storageProviderNamespaceAdmin) {
    this.storageProviderNamespaceAdmin = storageProviderNamespaceAdmin;
  }

  @PUT
  @Path("/data/admin/create")
  public void createNamespace(HttpRequest request, HttpResponder responder,
                              @PathParam("namespace-id") String namespaceId) {
    try {
      String body = request.getContent().toString(Charsets.UTF_8);
      NamespaceConfig namespaceConfig = GSON.fromJson(body, NamespaceConfig.class);

      if (namespaceConfig == null) {
        responder.sendString(HttpResponseStatus.BAD_REQUEST, String.format("Failed to parse %s from the request body.",
                             NamespaceConfig.class.getSimpleName()));
        return;
      }
      storageProviderNamespaceAdmin.create(Id.Namespace.from(namespaceId), namespaceConfig);
    } catch (IOException e) {
      responder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                           "Error while creating namespace - " + e.getMessage());
      return;
    } catch (ExploreException | SQLException e) {
      responder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                           "Error while creating namespace in Hive - " + e.getMessage());
      return;
    } catch (UnauthenticatedException e) {
      responder.sendString(HttpResponseStatus.UNAUTHORIZED,
                           "Error while creating namespace - " + e.getMessage());
    } catch (NamespaceNotFoundException e) {
      responder.sendString(HttpResponseStatus.NOT_FOUND,
                           "Error while creating namespace - " + e.getMessage());
    }
    responder.sendString(HttpResponseStatus.OK,
                         String.format("Created namespace %s successfully", namespaceId));
  }

  @DELETE
  @Path("/data/admin/delete")
  public void deleteNamespace(HttpRequest request, HttpResponder responder,
                              @PathParam("namespace-id") String namespaceId) {
    try {
      storageProviderNamespaceAdmin.delete(Id.Namespace.from(namespaceId));
    } catch (IOException e) {
      responder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                           "Error while deleting namespace - " + e.getMessage());
      return;
    } catch (ExploreException | SQLException e) {
      responder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                           "Error while deleting namespace in Hive - " + e.getMessage());
      return;
    } catch (UnauthenticatedException e) {
      responder.sendString(HttpResponseStatus.UNAUTHORIZED,
                           "Error while deleting namespace - " + e.getMessage());
    } catch (NamespaceNotFoundException e) {
      responder.sendString(HttpResponseStatus.NOT_FOUND,
                           "Error while deleting namespace - " + e.getMessage());
    }
    responder.sendString(HttpResponseStatus.OK,
                         String.format("Deleted namespace %s successfully", namespaceId));
  }
}
