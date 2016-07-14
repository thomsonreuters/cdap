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
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.discovery.EndpointStrategy;
import co.cask.cdap.common.discovery.RandomEndpointStrategy;
import co.cask.cdap.proto.BasicThrowable;
import co.cask.cdap.proto.WorkflowTokenDetail;
import co.cask.cdap.proto.WorkflowTokenNodeDetail;
import co.cask.cdap.proto.codec.BasicThrowableCodec;
import co.cask.cdap.proto.codec.WorkflowTokenDetailCodec;
import co.cask.cdap.proto.codec.WorkflowTokenNodeDetailCodec;
import co.cask.common.http.HttpMethod;
import co.cask.common.http.HttpRequest;
import co.cask.common.http.HttpRequestConfig;
import co.cask.common.http.HttpRequests;
import co.cask.common.http.HttpResponse;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import org.apache.twill.discovery.Discoverable;
import org.apache.twill.discovery.DiscoveryServiceClient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Common HTTP client functionality for remote operations from programs.
 */
class RemoteOpsClient {

  private static final Gson GSON = new GsonBuilder()
    .registerTypeAdapter(BasicThrowable.class, new BasicThrowableCodec())
    .registerTypeAdapter(WorkflowTokenDetail.class, new WorkflowTokenDetailCodec())
    .registerTypeAdapter(WorkflowTokenNodeDetail.class, new WorkflowTokenNodeDetailCodec())
    .create();

  private final Supplier<EndpointStrategy> endpointStrategySupplier;
  private final HttpRequestConfig httpRequestConfig;

  @Inject
  RemoteOpsClient(CConfiguration cConf, final DiscoveryServiceClient discoveryClient) {
    this.endpointStrategySupplier = Suppliers.memoize(new Supplier<EndpointStrategy>() {
      @Override
      public EndpointStrategy get() {
        return new RandomEndpointStrategy(discoveryClient.discover(Constants.Service.APP_FABRIC_HTTP));
      }
    });

    int httpClientTimeoutMs = cConf.getInt(Constants.HTTP_CLIENT_TIMEOUT_MS);
    this.httpRequestConfig = new HttpRequestConfig(httpClientTimeoutMs, httpClientTimeoutMs);
  }

  protected HttpResponse executeRequest(String methodName, Object... arguments) {
    return doRequest("execute/" + methodName, HttpMethod.POST, ImmutableMap.<String, String>of(),
                     GSON.toJson(createArguments(arguments)));
  }

  private String resolve(String resource) {
    Discoverable discoverable = endpointStrategySupplier.get().pick(3L, TimeUnit.SECONDS);
    if (discoverable == null) {
      throw new RuntimeException(
        String.format("Cannot discover service %s", Constants.Service.APP_FABRIC_HTTP));
    }
    InetSocketAddress addr = discoverable.getSocketAddress();

    return String.format("http://%s:%s%s/%s",
                         addr.getHostName(), addr.getPort(), "/v1", resource);
  }

  private static List<MethodArgument> createArguments(Object... arguments) {
    List<MethodArgument> methodArguments = new ArrayList<>();
    for (Object arg : arguments) {
      if (arg == null) {
        methodArguments.add(null);
      } else {
        String type = arg.getClass().getName();
        methodArguments.add(new MethodArgument(type, GSON.toJsonTree(arg)));
      }
    }
    return methodArguments;
  }

  private HttpResponse doRequest(String resource, HttpMethod requestMethod,
                                 @Nullable Map<String, String> headers, @Nullable String body) {
    String resolvedUrl = resolve(resource);
    try {
      URL url = new URL(resolvedUrl);
      HttpRequest.Builder builder = HttpRequest.builder(requestMethod, url).addHeaders(headers);
      if (body != null) {
        builder.withBody(body);
      }
      HttpResponse response = HttpRequests.execute(builder.build(), httpRequestConfig);
      if (response.getResponseCode() == HttpURLConnection.HTTP_OK) {
        return response;
      }
      throw new RuntimeException(String.format("%s Response: %s.",
                                               createErrorMessage(resolvedUrl, requestMethod, headers, body),
                                               response));
    } catch (IOException e) {
      // throw diff type of Exception?
      throw new RuntimeException(createErrorMessage(resolvedUrl, requestMethod, headers, body),
                                 e);
    }
  }

  // creates error message, encoding details about the request
  private static String createErrorMessage(String resolvedUrl, HttpMethod requestMethod,
                                           @Nullable Map<String, String> headers, @Nullable String body) {
    return String.format("Error making request to AppFabric Service at %s while doing %s with headers %s and body %s.",
                         resolvedUrl, requestMethod,
                         headers == null ? "null" : Joiner.on(",").withKeyValueSeparator("=").join(headers),
                         body == null ? "null" : body);
  }
}
