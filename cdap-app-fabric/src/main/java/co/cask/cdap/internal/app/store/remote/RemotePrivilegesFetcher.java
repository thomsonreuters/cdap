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
import co.cask.cdap.gateway.handlers.meta.RemotePrivilegeFetcherHandler;
import co.cask.cdap.internal.guava.reflect.TypeToken;
import co.cask.cdap.proto.codec.EntityIdTypeAdapter;
import co.cask.cdap.proto.id.EntityId;
import co.cask.cdap.proto.security.Principal;
import co.cask.cdap.proto.security.Privilege;
import co.cask.cdap.security.spi.authorization.PrivilegesFetcher;
import co.cask.common.http.HttpResponse;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import org.apache.twill.discovery.DiscoveryServiceClient;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.Set;

/**
 * An {@link PrivilegesFetcher} that is used to make an HTTP call to {@link RemotePrivilegeFetcherHandler} to fetch
 * privileges of a given principal. Communication over HTTP is necessary because program containers, which use this
 * class (and run as the user running the program) may not be white-listed to make calls to authorization providers
 * (like Apache Sentry).
 */
public class RemotePrivilegesFetcher extends RemoteOpsClient implements PrivilegesFetcher {
  private static final Logger LOG = LoggerFactory.getLogger(RemotePrivilegesFetcher.class);
  private static final Gson GSON = new GsonBuilder()
    .registerTypeAdapter(EntityId.class, new EntityIdTypeAdapter())
    .create();
  private static final Type SET_PRIVILEGES_TYPE = new TypeToken<Set<Privilege>>() { }.getType();

  @Inject
  RemotePrivilegesFetcher(CConfiguration cConf, DiscoveryServiceClient discoveryClient) {
    super(cConf, discoveryClient);
  }

  @Override
  public Set<Privilege> listPrivileges(Principal principal) throws Exception {
    LOG.trace("Making list privileges request for principal {}", principal);
    HttpResponse httpResponse = executeRequest("listPrivileges", principal);
    String responseBody = httpResponse.getResponseBodyAsString();
    Preconditions.checkArgument(httpResponse.getResponseCode() == HttpResponseStatus.OK.getCode(),
                                "Error listing privileges for %s: Code - %s; Message - %s", principal,
                                httpResponse.getResponseCode(), responseBody);
    LOG.debug("List privileges response for principal {}: {}", principal, responseBody);
    return GSON.fromJson(responseBody, SET_PRIVILEGES_TYPE);
  }
}
