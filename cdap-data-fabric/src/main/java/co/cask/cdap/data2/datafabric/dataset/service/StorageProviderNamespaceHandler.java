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

import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.kerberos.SecurityUtil;
import co.cask.cdap.common.namespace.NamespaceAdmin;
import co.cask.cdap.data2.security.ImpersonationInfo;
import co.cask.cdap.data2.security.ImpersonationUserResolver;
import co.cask.cdap.data2.security.ImpersonationUtils;
import co.cask.cdap.explore.service.ExploreException;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.NamespaceConfig;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.http.AbstractHttpHandler;
import co.cask.http.HttpHandler;
import co.cask.http.HttpResponder;
import com.google.common.base.Charsets;
import com.google.common.base.Supplier;
import com.google.gson.Gson;
import com.google.inject.Inject;
import org.apache.hadoop.security.UserGroupInformation;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.Callable;
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
  private final ImpersonationUserResolver impersonationUserResolver;
  private final CConfiguration cConf;

  @Inject
  public StorageProviderNamespaceHandler(CConfiguration cConf,
                                         StorageProviderNamespaceAdmin storageProviderNamespaceAdmin,
                                         ImpersonationUserResolver impersonationUserResolver) {
    this.cConf = cConf;
    this.storageProviderNamespaceAdmin = storageProviderNamespaceAdmin;
    this.impersonationUserResolver = impersonationUserResolver;
  }

  private void impersonateAction(final Callable callable, final NamespaceId namespaceId) throws Throwable {
    impersonateAction(callable, new Supplier<ImpersonationInfo>() {
      @Override
      public ImpersonationInfo get() {
        return impersonationUserResolver.getImpersonationInfo(namespaceId);
      }
    });
  }

  private void impersonateAction(final Callable callable, final NamespaceConfig namespaceConfig) throws Throwable {
    impersonateAction(callable, new Supplier<ImpersonationInfo>() {
      @Override
      public ImpersonationInfo get() {
        return impersonationUserResolver.getImpersonationInfo(namespaceConfig);
      }
    });
  }

  private void impersonateAction(final Callable callable,
                                 Supplier<ImpersonationInfo> impersonationInfo) throws Throwable {
    // we use Supplier, so that we don't need to attempt to compute the ImpersonationInfo if security isn't enabled,
    // in which case the principal and keytab may not be configured even in cConf or at the namespace level
    if (!SecurityUtil.isKerberosEnabled(cConf)) {
      callable.call();
      return;
    }
    ImpersonationUtils.doAs(impersonationUserResolver.getResolvedUser(impersonationInfo.get()), callable);
  }

  @PUT
  @Path("/data/admin/create")
  public void createNamespace(HttpRequest request, HttpResponder responder,
                              @PathParam("namespace-id") final String namespaceId) throws Throwable {
    try {
      String body = request.getContent().toString(Charsets.UTF_8);
      NamespaceConfig namespaceConfig = GSON.fromJson(body, NamespaceConfig.class);
      if (namespaceConfig == null) {
        responder.sendString(HttpResponseStatus.BAD_REQUEST, "Expected namespace config in request body.");
        return;
      }

      impersonateAction(new Callable<Void>() {
        @Override
        public Void call() throws Exception {
          storageProviderNamespaceAdmin.create(Id.Namespace.from(namespaceId));
          return null;
        }
      }, namespaceConfig);
    } catch (IOException e) {
      responder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                           "Error while creating namespace - " + e.getMessage());
      return;
    } catch (ExploreException | SQLException e) {
      responder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                           "Error while creating namespace in Hive - " + e.getMessage());
      return;
    }
    responder.sendString(HttpResponseStatus.OK,
                         String.format("Created namespace %s successfully", namespaceId));
  }

  @DELETE
  @Path("/data/admin/delete")
  public void deleteNamespace(HttpRequest request, HttpResponder responder,
                              @PathParam("namespace-id") final String namespaceId) throws Throwable {
    try {
      impersonateAction(new Callable<Void>() {
        @Override
        public Void call() throws Exception {
          storageProviderNamespaceAdmin.delete(Id.Namespace.from(namespaceId));
          return null;
        }
      }, new NamespaceId(namespaceId));
    } catch (IOException e) {
      responder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                           "Error while deleting namespace - " + e.getMessage());
      return;
    } catch (ExploreException | SQLException e) {
      responder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                           "Error while deleting namespace in Hive - " + e.getMessage());
      return;
    }
    responder.sendString(HttpResponseStatus.OK,
                         String.format("Deleted namespace %s successfully", namespaceId));
  }
}
