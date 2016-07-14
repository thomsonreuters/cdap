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

import co.cask.cdap.api.artifact.ArtifactScope;
import co.cask.cdap.api.schedule.SchedulableProgramType;
import co.cask.cdap.app.runtime.ProgramController;
import co.cask.cdap.app.runtime.ProgramRuntimeService;
import co.cask.cdap.common.ApplicationNotFoundException;
import co.cask.cdap.common.ArtifactAlreadyExistsException;
import co.cask.cdap.common.ArtifactNotFoundException;
import co.cask.cdap.common.BadRequestException;
import co.cask.cdap.common.InvalidArtifactException;
import co.cask.cdap.common.NamespaceNotFoundException;
import co.cask.cdap.common.NotFoundException;
import co.cask.cdap.common.UnauthenticatedException;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.http.AbstractBodyConsumer;
import co.cask.cdap.common.io.CaseInsensitiveEnumTypeAdapterFactory;
import co.cask.cdap.common.namespace.NamespaceQueryAdmin;
import co.cask.cdap.common.namespace.NamespacedLocationFactory;
import co.cask.cdap.common.utils.DirUtils;
import co.cask.cdap.gateway.handlers.util.AbstractAppFabricHttpHandler;
import co.cask.cdap.internal.app.deploy.ProgramTerminator;
import co.cask.cdap.internal.app.runtime.artifact.WriteConflictException;
import co.cask.cdap.internal.app.runtime.schedule.Scheduler;
import co.cask.cdap.internal.app.services.ApplicationLifecycleService;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.artifact.AppRequest;
import co.cask.cdap.proto.artifact.ArtifactSummary;
import co.cask.cdap.security.spi.authorization.UnauthorizedException;
import co.cask.http.BodyConsumer;
import co.cask.http.HttpResponder;
import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMultimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.twill.filesystem.Location;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

/**
 * {@link co.cask.http.HttpHandler} for managing application lifecycle.
 */
@Singleton
@Path(Constants.Gateway.API_VERSION_3 + "/namespaces/{namespace-id}")
public class AppLifecycleHttpHandler extends AbstractAppFabricHttpHandler {
  private static final Gson GSON = new GsonBuilder()
    .registerTypeAdapterFactory(new CaseInsensitiveEnumTypeAdapterFactory())
    .create();
  private static final Logger LOG = LoggerFactory.getLogger(AppLifecycleHttpHandler.class);

  /**
   * Runtime program service for running and managing programs.
   */
  private final ProgramRuntimeService runtimeService;

  private final CConfiguration configuration;
  private final Scheduler scheduler;
  private final NamespaceQueryAdmin namespaceQueryAdmin;
  private final NamespacedLocationFactory namespacedLocationFactory;
  private final ApplicationLifecycleService applicationLifecycleService;
  private final File tmpDir;

  @Inject
  AppLifecycleHttpHandler(CConfiguration configuration,
                          Scheduler scheduler, ProgramRuntimeService runtimeService,
                          NamespaceQueryAdmin namespaceQueryAdmin,
                          NamespacedLocationFactory namespacedLocationFactory,
                          ApplicationLifecycleService applicationLifecycleService) {
    this.configuration = configuration;
    this.namespaceQueryAdmin = namespaceQueryAdmin;
    this.scheduler = scheduler;
    this.runtimeService = runtimeService;
    this.namespacedLocationFactory = namespacedLocationFactory;
    this.applicationLifecycleService = applicationLifecycleService;
    this.tmpDir = new File(new File(configuration.get(Constants.CFG_LOCAL_DATA_DIR)),
                           configuration.get(Constants.AppFabric.TEMP_DIR)).getAbsoluteFile();
  }

  /**
   * Creates an application with the specified name from an artifact.
   */
  @PUT
  @Path("/apps/{app-id}")
  public BodyConsumer create(HttpRequest request, HttpResponder responder,
                             @PathParam("namespace-id") final String namespaceId,
                             @PathParam("app-id") final String appId)
    throws BadRequestException, NamespaceNotFoundException {

    Id.Application applicationId = validateApplicationId(namespaceId, appId);

    try {
      return deployAppFromArtifact(applicationId);
    } catch (Exception ex) {
      responder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Deploy failed: {}" + ex.getMessage());
      return null;
    }
  }

  /**
   * Deploys an application.
   */
  @POST
  @Path("/apps")
  public BodyConsumer deploy(HttpRequest request, HttpResponder responder,
                             @PathParam("namespace-id") final String namespaceId,
                             @HeaderParam(ARCHIVE_NAME_HEADER) final String archiveName,
                             @HeaderParam(APP_CONFIG_HEADER) String configString)
    throws BadRequestException, NamespaceNotFoundException {

    Id.Namespace namespace = validateNamespace(namespaceId);

    // null means use name provided by app spec
    try {
      return deployApplication(responder, namespace, null, archiveName, configString);
    } catch (Exception ex) {
      responder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Deploy failed: " + ex.getMessage());
      return null;
    }
  }

  /**
   * Returns a list of applications associated with a namespace.
   */
  @GET
  @Path("/apps")
  public void getAllApps(HttpRequest request, HttpResponder responder,
                         @PathParam("namespace-id") String namespaceId,
                         @QueryParam("artifactName") String artifactName,
                         @QueryParam("artifactVersion") String artifactVersion)
    throws NamespaceNotFoundException, BadRequestException {

    Id.Namespace namespace = validateNamespace(namespaceId);

    Set<String> names = new HashSet<>();
    if (!Strings.isNullOrEmpty(artifactName)) {
      for (String name : Splitter.on(',').split(artifactName)) {
        names.add(name);
      }
    }
    responder.sendJson(HttpResponseStatus.OK, applicationLifecycleService.getApps(namespace, names, artifactVersion));
  }

  /**
   * Returns the info associated with the application.
   */
  @GET
  @Path("/apps/{app-id}")
  public void getAppInfo(HttpRequest request, HttpResponder responder,
                         @PathParam("namespace-id") String namespaceId,
                         @PathParam("app-id") final String appId)
    throws NamespaceNotFoundException, BadRequestException, ApplicationNotFoundException {

    Id.Application applicationId = validateApplicationId(namespaceId, appId);
    responder.sendJson(HttpResponseStatus.OK, applicationLifecycleService.getAppDetail(applicationId));
  }

  /**
   * Delete an application specified by appId.
   */
  @DELETE
  @Path("/apps/{app-id}")
  public void deleteApp(HttpRequest request, HttpResponder responder,
                        @PathParam("namespace-id") String namespaceId,
                        @PathParam("app-id") String appId) throws Exception {
    Id.Application id = validateApplicationId(namespaceId, appId);
    applicationLifecycleService.removeApplication(id);
    responder.sendStatus(HttpResponseStatus.OK);
  }

  /**
   * Deletes all applications in CDAP.
   */
  @DELETE
  @Path("/apps")
  public void deleteAllApps(HttpRequest request, HttpResponder responder,
                            @PathParam("namespace-id") String namespaceId) throws Exception {
    Id.Namespace id = validateNamespace(namespaceId);
    applicationLifecycleService.removeAll(id);
    responder.sendStatus(HttpResponseStatus.OK);
  }

  /**
   * Updates an existing application.
   */
  @POST
  @Path("/apps/{app-id}/update")
  public void updateApp(HttpRequest request, HttpResponder responder,
                        @PathParam("namespace-id") final String namespaceId,
                        @PathParam("app-id") final String appName)
    throws NotFoundException, BadRequestException, UnauthorizedException, IOException {

    Id.Application appId = validateApplicationId(namespaceId, appName);

    AppRequest appRequest;
    try (Reader reader = new InputStreamReader(new ChannelBufferInputStream(request.getContent()), Charsets.UTF_8)) {
      appRequest = GSON.fromJson(reader, AppRequest.class);
    } catch (IOException e) {
      LOG.error("Error reading request to update app {} in namespace {}.", appName, namespaceId, e);
      throw new IOException("Error reading request body.");
    } catch (JsonSyntaxException e) {
      throw new BadRequestException("Request body is invalid json: " + e.getMessage());
    }

    try {
      applicationLifecycleService.updateApp(appId, appRequest, createProgramTerminator());
      responder.sendString(HttpResponseStatus.OK, "Update complete.");
    } catch (InvalidArtifactException e) {
      throw new BadRequestException(e.getMessage());
    } catch (NotFoundException | UnauthorizedException e) {
      throw e;
    } catch (Exception e) {
      // this is the same behavior as deploy app pipeline, but this is bad behavior. Error handling needs improvement.
      LOG.error("Deploy failure", e);
      responder.sendString(HttpResponseStatus.BAD_REQUEST, e.getMessage());
    }
  }

  // normally we wouldn't want to use a body consumer but would just want to read the request body directly
  // since it wont be big. But the deploy app API has one path with different behavior based on content type
  // the other behavior requires a BodyConsumer and only have one method per path is allowed,
  // so we have to use a BodyConsumer
  private BodyConsumer deployAppFromArtifact(final Id.Application appId) throws IOException {

    // createTempFile() needs a prefix of at least 3 characters
    return new AbstractBodyConsumer(File.createTempFile("apprequest-" + appId, ".json", tmpDir)) {

      @Override
      protected void onFinish(HttpResponder responder, File uploadedFile) {
        try (FileReader fileReader = new FileReader(uploadedFile)) {

          AppRequest<?> appRequest = GSON.fromJson(fileReader, AppRequest.class);
          ArtifactSummary artifactSummary = appRequest.getArtifact();
          Id.Namespace artifactNamespace =
            ArtifactScope.SYSTEM.equals(artifactSummary.getScope()) ? Id.Namespace.SYSTEM : appId.getNamespace();
          Id.Artifact artifactId =
            Id.Artifact.from(artifactNamespace, artifactSummary.getName(), artifactSummary.getVersion());

          // if we don't null check, it gets serialized to "null"
          String configString = appRequest.getConfig() == null ? null : GSON.toJson(appRequest.getConfig());
          applicationLifecycleService.deployApp(appId.getNamespace(), appId.getId(),
                                                artifactId, configString, createProgramTerminator());
          responder.sendString(HttpResponseStatus.OK, "Deploy Complete");
        } catch (ArtifactNotFoundException e) {
          responder.sendString(HttpResponseStatus.NOT_FOUND, e.getMessage());
        } catch (InvalidArtifactException e) {
          responder.sendString(HttpResponseStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
          LOG.error("Error reading request body for creating app {}.", appId);
          responder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR, String.format(
            "Error while reading json request body for app %s.", appId));
        } catch (Exception e) {
          LOG.error("Deploy failure", e);
          responder.sendString(HttpResponseStatus.BAD_REQUEST, e.getMessage());
        }
      }
    };
  }

  private BodyConsumer deployApplication(final HttpResponder responder,
                                         final Id.Namespace namespace,
                                         final String appId,
                                         final String archiveName,
                                         final String configString) throws IOException {

    Location namespaceHomeLocation = null;
    try {
      namespaceHomeLocation = namespacedLocationFactory.get(namespace);
    } catch (NamespaceNotFoundException e) {
      responder.sendString(HttpResponseStatus.NOT_FOUND, e.getMessage());
    } catch (UnauthenticatedException e) {
      responder.sendString(HttpResponseStatus.FORBIDDEN, e.getMessage());
    }
    if (!namespaceHomeLocation.exists()) {
      String msg = String.format("Home directory %s for namespace %s not found",
                                 namespaceHomeLocation, namespace.getId());
      LOG.error(msg);
      responder.sendString(HttpResponseStatus.NOT_FOUND, msg);
      return null;
    }

    if (archiveName == null || archiveName.isEmpty()) {
      responder.sendString(HttpResponseStatus.BAD_REQUEST,
                           String.format(
                             "%s header not present. Please include the header and set its value to the jar name.",
                             ARCHIVE_NAME_HEADER),
                           ImmutableMultimap.of(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE));
      return null;
    }

    // TODO: (CDAP-3258) error handling needs to be refactored here, should be able just to throw the exception,
    // but the caller catches all exceptions and responds with a 500
    final Id.Artifact artifactId;
    try {
      artifactId = Id.Artifact.parse(namespace, archiveName);
    } catch (IllegalArgumentException e) {
      responder.sendString(HttpResponseStatus.BAD_REQUEST, e.getMessage());
      return null;
    }

    // Store uploaded content to a local temp file
    String namespacesDir = configuration.get(Constants.Namespace.NAMESPACES_DIR);
    File localDataDir = new File(configuration.get(Constants.CFG_LOCAL_DATA_DIR));
    File namespaceBase = new File(localDataDir, namespacesDir);
    File tempDir = new File(new File(namespaceBase, namespace.getId()),
                            configuration.get(Constants.AppFabric.TEMP_DIR)).getAbsoluteFile();
    if (!DirUtils.mkdirs(tempDir)) {
      throw new IOException("Could not create temporary directory at: " + tempDir);
    }

    return new AbstractBodyConsumer(File.createTempFile("app-", ".jar", tempDir)) {

      @Override
      protected void onFinish(HttpResponder responder, File uploadedFile) {
        try {
          // deploy app
          applicationLifecycleService.deployAppAndArtifact(namespace, appId, artifactId, uploadedFile,
                                                           configString, createProgramTerminator());
          responder.sendString(HttpResponseStatus.OK, "Deploy Complete");
        } catch (InvalidArtifactException e) {
          responder.sendString(HttpResponseStatus.BAD_REQUEST, e.getMessage());
        } catch (ArtifactAlreadyExistsException e) {
          responder.sendString(HttpResponseStatus.CONFLICT, String.format(
            "Artifact '%s' already exists. Please use the API that creates an application from an existing artifact. " +
              "If you are trying to replace the artifact, please delete it and then try again.", artifactId));
        } catch (WriteConflictException e) {
          // don't really expect this to happen. It means after multiple retries there were still write conflicts.
          LOG.warn("Write conflict while trying to add artifact {}.", artifactId, e);
          responder.sendString(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                               "Write conflict while adding artifact. This can happen if multiple requests to add " +
                                 "the same artifact occur simultaneously. Please try again.");
        } catch (UnauthorizedException e) {
          responder.sendString(HttpResponseStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
          LOG.error("Deploy failure", e);
          responder.sendString(HttpResponseStatus.BAD_REQUEST, e.getMessage());
        }
      }
    };
  }

  private ProgramTerminator createProgramTerminator() {
    return new ProgramTerminator() {
      @Override
      public void stop(Id.Program programId) throws Exception {
        switch (programId.getType()) {
          case FLOW:
            stopProgramIfRunning(programId);
            break;
          case WORKFLOW:
            scheduler.deleteSchedules(programId, SchedulableProgramType.WORKFLOW);
            break;
          case MAPREDUCE:
            //no-op
            break;
          case SERVICE:
            stopProgramIfRunning(programId);
            break;
          case WORKER:
            stopProgramIfRunning(programId);
            break;
        }
      }
    };
  }

  private void stopProgramIfRunning(Id.Program programId) throws InterruptedException, ExecutionException {
    ProgramRuntimeService.RuntimeInfo programRunInfo = findRuntimeInfo(programId, runtimeService);
    if (programRunInfo != null) {
      ProgramController controller = programRunInfo.getController();
      controller.stop().get();
    }
  }

  private Id.Namespace validateNamespace(String namespaceId) throws BadRequestException, NamespaceNotFoundException {
    Id.Namespace namespace;
    try {
      namespace = Id.Namespace.from(namespaceId);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(String.format("Invalid namespace '%s': %s", namespaceId, e.getMessage()));
    }

    try {
      namespaceQueryAdmin.get(namespace);
    } catch (NamespaceNotFoundException e) {
      throw e;
    } catch (Exception e) {
      // This can only happen when NamespaceAdmin uses HTTP calls to interact with namespaces.
      // In AppFabricServer, NamespaceAdmin is bound to DefaultNamespaceAdmin, which interacts directly with the MDS.
      // Hence, this exception will never be thrown
      throw Throwables.propagate(e);
    }
    return namespace;
  }

  private Id.Application validateApplicationId(String namespaceId, String appId)
    throws BadRequestException, NamespaceNotFoundException {

    Id.Namespace namespace = validateNamespace(namespaceId);
    try {
      return Id.Application.from(namespace, appId);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(String.format("Invalid app name '%s': %s", appId, e.getMessage()));
    }
  }
}
