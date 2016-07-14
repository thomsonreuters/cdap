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

import co.cask.cdap.proto.NamespaceConfig;
import co.cask.cdap.proto.NamespaceMeta;
import co.cask.common.http.HttpMethod;
import co.cask.common.http.HttpRequest;
import co.cask.common.http.HttpRequests;
import co.cask.common.http.HttpResponse;
import com.google.gson.Gson;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

/**
 * Tests for {@link StorageProviderNamespaceHandler}
 */
public class StorageProviderNamespaceHandlerTest extends DatasetServiceTestBase {

  private static final Gson GSON = new Gson();

  @Test
  public void test() throws Exception {
    // first add the namespace meta
    namespaceAdmin.create(new NamespaceMeta.Builder().setName("myspace").build());
    // creating the namesapce in underlying storage provider should work
    Assert.assertEquals(200, createNamespace("myspace").getResponseCode());
    // Since the namespace directory already exist this creating the same should fail
    Assert.assertEquals(500, createNamespace("myspace").getResponseCode());
    // should be able to delete the namespace
    Assert.assertEquals(200, deleteNamespace("myspace").getResponseCode());
  }

  private HttpResponse createNamespace(String namespaceId) throws IOException {
    HttpRequest request = HttpRequest.builder(HttpMethod.PUT, getStorageProviderNamespaceAdminUrl(namespaceId,
                                                                                                  "create"))
      .withBody(GSON.toJson(new NamespaceConfig())).build();
    return HttpRequests.execute(request);
  }

  private HttpResponse deleteNamespace(String namespaceId) throws IOException {
    HttpRequest request = HttpRequest.delete(getStorageProviderNamespaceAdminUrl(namespaceId, "delete")).build();
    return HttpRequests.execute(request);
  }
}
