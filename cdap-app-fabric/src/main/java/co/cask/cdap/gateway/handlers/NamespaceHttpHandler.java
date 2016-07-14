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

package co.cask.cdap.gateway.handlers;

import co.cask.cdap.common.AlreadyExistsException;
import co.cask.cdap.common.BadRequestException;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.namespace.NamespaceAdmin;
import co.cask.cdap.gateway.handlers.util.AbstractAppFabricHttpHandler;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.NamespaceConfig;
import co.cask.cdap.proto.NamespaceMeta;
import co.cask.cdap.proto.id.EntityId;
import co.cask.cdap.proto.id.InstanceId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.security.Action;
import co.cask.cdap.proto.security.Principal;
import co.cask.cdap.security.authorization.AuthorizerInstantiator;
import co.cask.cdap.security.spi.authentication.SecurityRequestContext;
import co.cask.http.HttpHandler;
import co.cask.http.HttpResponder;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Inject;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

/**
 * The {@link HttpHandler} for handling REST calls to namespace endpoints.
 */
@Path(Constants.Gateway.API_VERSION_3)
public class NamespaceHttpHandler extends AbstractAppFabricHttpHandler {
  private static final Logger LOG = LoggerFactory.getLogger(NamespaceHttpHandler.class);

  private final CConfiguration cConf;
  private final NamespaceAdmin namespaceAdmin;
  private final AuthorizerInstantiator authorizerInstantiator;
  private final InstanceId instanceId;

  @Inject
  NamespaceHttpHandler(CConfiguration cConf, NamespaceAdmin namespaceAdmin,
                       AuthorizerInstantiator authorizerInstantiator) {
    this.cConf = cConf;
    this.namespaceAdmin = namespaceAdmin;
    this.authorizerInstantiator = authorizerInstantiator;
    this.instanceId = createInstanceId(cConf);
  }

  @GET
  @Path("/namespaces")
  public void getAllNamespaces(HttpRequest request, HttpResponder responder) throws Exception {
    responder.sendJson(HttpResponseStatus.OK, namespaceAdmin.list());
  }

  @GET
  @Path("/namespaces/{namespace-id}")
  public void getNamespace(HttpRequest request, HttpResponder responder,
                           @PathParam("namespace-id") String namespaceId) throws Exception {
    NamespaceMeta ns = namespaceAdmin.get(Id.Namespace.from(namespaceId));
    responder.sendJson(HttpResponseStatus.OK, ns);
  }


  @PUT
  @Path("/namespaces/{namespace-id}/properties")
  public void updateNamespaceProperties(HttpRequest request, HttpResponder responder,
                                        @PathParam("namespace-id") String namespaceId) throws Exception {
    NamespaceMeta meta = parseBody(request, NamespaceMeta.class);
    namespaceAdmin.updateProperties(Id.Namespace.from(namespaceId), meta);
    responder.sendString(HttpResponseStatus.OK, String.format("Updated properties for namespace '%s'.", namespaceId));
  }

  @PUT
  @Path("/namespaces/{namespace-id}")
  public void create(HttpRequest request, HttpResponder responder, @PathParam("namespace-id") String namespaceId)
    throws Exception {
    if (!NamespaceId.isValidNamespaceId(namespaceId)) {
      throw new BadRequestException("Namespace id can contain only alphanumeric characters or '_'.");
    }
    NamespaceId namespace = new NamespaceId(namespaceId);
    Principal principal = SecurityRequestContext.toPrincipal();
    // Skip authorization enforcement for the system user and the default namespace, so the DefaultNamespaceEnsurer
    // thread can successfully create the default namespace
    if (!(Principal.SYSTEM.equals(principal) && NamespaceId.DEFAULT.equals(namespace))) {
      authorizerInstantiator.get().enforce(instanceId, principal, Action.ADMIN);
    }

    NamespaceMeta metadata;
    try {
      metadata = parseBody(request, NamespaceMeta.class);
    } catch (JsonSyntaxException e) {
      throw new BadRequestException("Invalid json object provided in request body.");
    }

    if (isReserved(namespaceId)) {
      throw new BadRequestException(String.format("Cannot create the namespace '%s'. '%s' is a reserved namespace.",
                                                  namespaceId, namespaceId));
    }

    NamespaceMeta.Builder builder = new NamespaceMeta.Builder().setName(namespace.toId());

    // Handle optional params
    if (metadata != null) {
      if (metadata.getDescription() != null) {
        builder.setDescription(metadata.getDescription());
      }

      NamespaceConfig config = metadata.getConfig();
      if (config != null && !Strings.isNullOrEmpty(config.getSchedulerQueueName())) {
        builder.setSchedulerQueueName(config.getSchedulerQueueName());
      }
    }

    try {
      namespaceAdmin.create(builder.build());
      responder.sendString(HttpResponseStatus.OK,
                           String.format("Namespace '%s' created successfully.", namespaceId));
    } catch (AlreadyExistsException e) {
      responder.sendString(HttpResponseStatus.OK, String.format("Namespace '%s' already exists.", namespaceId));
    }
  }

  @DELETE
  @Path("/unrecoverable/namespaces/{namespace-id}")
  public void delete(HttpRequest request, HttpResponder responder, @PathParam("namespace-id") String namespace)
    throws Exception {
    if (!cConf.getBoolean(Constants.Dangerous.UNRECOVERABLE_RESET, Constants.Dangerous.DEFAULT_UNRECOVERABLE_RESET)) {
      responder.sendString(HttpResponseStatus.FORBIDDEN,
                           String.format("Namespace '%s' cannot be deleted because '%s' is not enabled. " +
                                           "Please enable it and restart CDAP Master.",
                                         namespace, Constants.Dangerous.UNRECOVERABLE_RESET));
      return;
    }
    Id.Namespace namespaceId = Id.Namespace.from(namespace);
    namespaceAdmin.delete(namespaceId);
    responder.sendStatus(HttpResponseStatus.OK);
  }

  @DELETE
  @Path("/unrecoverable/namespaces/{namespace-id}/datasets")
  public void deleteDatasets(HttpRequest request, HttpResponder responder,
                             @PathParam("namespace-id") String namespace) throws Exception {
    if (!cConf.getBoolean(Constants.Dangerous.UNRECOVERABLE_RESET, Constants.Dangerous.DEFAULT_UNRECOVERABLE_RESET)) {
      responder.sendString(HttpResponseStatus.FORBIDDEN,
                           String.format("All datasets in namespace %s cannot be deleted because '%s' is not enabled." +
                                           " Please enable it and restart CDAP Master.",
                                         namespace, Constants.Dangerous.UNRECOVERABLE_RESET));
      return;
    }
    Id.Namespace namespaceId = Id.Namespace.from(namespace);
    namespaceAdmin.deleteDatasets(namespaceId);
    responder.sendStatus(HttpResponseStatus.OK);
  }

  private boolean isReserved(String namespaceId) {
    return Id.Namespace.DEFAULT.getId().equals(namespaceId) || Id.Namespace.SYSTEM.getId().equals(namespaceId) ||
      Id.Namespace.CDAP.getId().equals(namespaceId);
  }

  private InstanceId createInstanceId(CConfiguration cConf) {
    String instanceName = cConf.get(Constants.INSTANCE_NAME);
    Preconditions.checkArgument(NamespaceId.isValidNamespaceId(instanceName),
                                "CDAP instance name specified by '%s' in cdap-site.xml should be alphanumeric " +
                                  "(underscores allowed). Its current invalid value is '%s'",
                                Constants.INSTANCE_NAME, instanceName);
    return new InstanceId(instanceName);
  }
}
