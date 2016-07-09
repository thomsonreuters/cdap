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

import co.cask.cdap.api.security.store.SecureStore;
import co.cask.cdap.api.security.store.SecureStoreData;
import co.cask.cdap.api.security.store.SecureStoreManager;
import co.cask.cdap.api.security.store.SecureStoreMetadata;
import co.cask.cdap.common.BadRequestException;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.gateway.handlers.util.AbstractAppFabricHttpHandler;
import co.cask.cdap.proto.security.SecureStoreCreateRequest;
import co.cask.cdap.security.store.FileSecureStore;
import co.cask.http.HttpResponder;
import com.google.inject.Inject;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

/**
 * Exposes REST APIs for {@link co.cask.cdap.api.security.store.SecureStore} and
 * {@link co.cask.cdap.api.security.store.SecureStoreManager}.
 */
@Path(Constants.Gateway.API_VERSION_3 + "/security/store/namespaces/{namespace-id}")
public class SecureStoreHandler extends AbstractAppFabricHttpHandler {

  private final SecureStore secureStore;
  private final SecureStoreManager secureStoreManager;

  @Inject
  public SecureStoreHandler(SecureStore secureStore, SecureStoreManager secureStoreManager) {
    this.secureStore = secureStore;
    this.secureStoreManager = secureStoreManager;
  }

  @Path("/key")
  @PUT
  public void create(HttpRequest httpRequest, HttpResponder httpResponder) throws BadRequestException, IOException {
    SecureStoreCreateRequest secureStoreCreateRequest = parseBody(httpRequest, SecureStoreCreateRequest.class);

    if (secureStoreCreateRequest == null) {
      throw new BadRequestException("Unable to parse the request.");
    }

    String name = secureStoreCreateRequest.getName();
    String description = secureStoreCreateRequest.getDescription();
    String value = secureStoreCreateRequest.getData();
    if (name == null || name.isEmpty()) {
      throw new BadRequestException("Name can not be empty.");
    }
    if (value == null || value.isEmpty()) {
      throw new BadRequestException("Data can not be empty.");
    }
    byte[] data = value.getBytes(StandardCharsets.UTF_8);
    secureStoreManager.put(name, data, description, secureStoreCreateRequest.getProperties());
    httpResponder.sendStatus(HttpResponseStatus.OK);
  }

  @Path("/keys/{key-name}")
  @DELETE
  public void delete(HttpRequest httpRequest, HttpResponder httpResponder, @PathParam("key-name") String name)
    throws IOException {
    secureStoreManager.delete(name);
    httpResponder.sendStatus(HttpResponseStatus.OK);
  }

  @Path("/keys/{key-name}")
  @GET
  public void get(HttpRequest httpRequest, HttpResponder httpResponder, @PathParam("key-name") String name)
    throws IOException {
    SecureStoreData secureStoreData = secureStore.get(name);;
    String data = new String(secureStoreData.get(), StandardCharsets.UTF_8);
    httpResponder.sendJson(HttpResponseStatus.OK, data);
  }

  @Path("/keys")
  @GET
  public void list(HttpRequest httpRequest, HttpResponder httpResponder) throws IOException {
    List<SecureStoreMetadata> metadataList = secureStore.list();
    List<FileSecureStore.SecureStoreListEntry> returnList = new ArrayList<>(metadataList.size());
    for (SecureStoreMetadata metadata : metadataList) {
      returnList.add(new FileSecureStore.SecureStoreListEntry(metadata.getName(), metadata.getDescription()));
    }

    httpResponder.sendJson(HttpResponseStatus.OK, returnList);
  }
}
