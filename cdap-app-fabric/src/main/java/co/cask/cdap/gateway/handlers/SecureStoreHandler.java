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

package co.cask.cdap.gateway.handlers;

import co.cask.cdap.api.security.securestore.SecureStore;
import co.cask.cdap.api.security.securestore.SecureStoreData;
import co.cask.cdap.api.security.securestore.SecureStoreManager;
import co.cask.cdap.api.security.securestore.SecureStoreMetadata;
import co.cask.cdap.common.*;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.gateway.handlers.util.AbstractAppFabricHttpHandler;

import javax.ws.rs.*;

import co.cask.cdap.proto.security.SecureStoreCreateRequest;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import co.cask.http.HttpResponder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Exposes {@link org.apache.twill.api.SecureStore} and
 * {@link co.cask.cdap.api.security.securestore.SecureStoreManager} operations over HTTPS
 */
@Path(Constants.Gateway.API_VERSION_3 + "/security/store/namespaces/{namespace-id}")
public class SecureStoreHandler extends AbstractAppFabricHttpHandler {
  private static final Logger LOG = LoggerFactory.getLogger(SecureStoreHandler.class);

  private final SecureStore secureStore;
  private final SecureStoreManager secureStoreManager;

  private SecureStoreCreateRequest secureStoreCreateRequest;

  public SecureStoreHandler(SecureStore secureStore, SecureStoreManager secureStoreManager) {
    this.secureStore = secureStore;
    this.secureStoreManager = secureStoreManager;
  }

  @Path("/key")
  @PUT
  public void create(HttpRequest httpRequest, HttpResponder httpResponder) {
    // Check authentication
    secureStoreCreateRequest = parseBody(httpRequest, SecureStoreCreateRequest.class);

    try {
      secureStoreManager.put(secureStoreCreateRequest.getName(), secureStoreCreateRequest.getData(),
                             secureStoreCreateRequest.getProperties());
    } catch (IOException e) {
      httpResponder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Failed to store the item in the store.");
      return;
    }
    httpResponder.sendStatus(HttpResponseStatus.OK);
  }

  @Path("/keys/{key-name}")
  @DELETE
  public void delete(HttpRequest httpRequest, HttpResponder httpResponder, @PathParam("key-name") String name) {
    try {
      secureStoreManager.delete(name);
    } catch (IOException e) {
      httpResponder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Failed to delete the key.");
      return;
    }
    httpResponder.sendStatus(HttpResponseStatus.OK);
  }

  @Path("/keys/{key-name}")
  @GET
  public void get(HttpRequest httpRequest, HttpResponder httpResponder, @PathParam("key-name") String name) {
    SecureStoreData secureStoreData;
    try {
      secureStoreData = secureStore.get(name);
    } catch (IOException e) {
      httpResponder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Failed to get the key.");
      return;
    }
    httpResponder.sendJson(HttpResponseStatus.OK, secureStoreData.get());
  }

  @Path("/keys")
  @GET
  public void list(HttpRequest httpRequest, HttpResponder httpResponder) {
    try {
      httpResponder.sendJson(HttpResponseStatus.OK, secureStore.list());
    } catch (IOException e) {
      httpResponder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Unable to list the keys in the store.");
    }
  }
}