/*
 * Copyright © 2015-2016 Cask Data, Inc.
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
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.http.AbstractBodyConsumer;
import co.cask.cdap.common.io.Locations;
import co.cask.cdap.common.namespace.NamespaceQueryAdmin;
import co.cask.cdap.common.namespace.NamespacedLocationFactory;
import co.cask.cdap.common.utils.DirUtils;
import co.cask.cdap.data2.datafabric.dataset.type.DatasetModuleConflictException;
import co.cask.cdap.data2.datafabric.dataset.type.DatasetTypeManager;
import co.cask.cdap.proto.DatasetModuleMeta;
import co.cask.cdap.proto.DatasetTypeMeta;
import co.cask.cdap.proto.Id;
import co.cask.http.AbstractHttpHandler;
import co.cask.http.BodyConsumer;
import co.cask.http.HandlerContext;
import co.cask.http.HttpResponder;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.inject.Inject;
import org.apache.twill.filesystem.Location;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

/**
 * Handles dataset type management calls.
 */
// todo: do we want to make it authenticated? or do we treat it always as "internal" piece?
@Path(Constants.Gateway.API_VERSION_3 + "/namespaces/{namespace-id}")
public class DatasetTypeHandler extends AbstractHttpHandler {
  private static final String HEADER_CLASS_NAME = "X-Class-Name";

  private static final Logger LOG = LoggerFactory.getLogger(DatasetTypeHandler.class);

  private final DatasetTypeManager typeManager;
  private final CConfiguration cConf;
  private final NamespacedLocationFactory namespacedLocationFactory;
  private final NamespaceQueryAdmin namespaceQueryAdmin;

  @Inject
  DatasetTypeHandler(DatasetTypeManager typeManager, CConfiguration conf,
                     NamespacedLocationFactory namespacedLocationFactory, NamespaceQueryAdmin namespaceQueryAdmin) {
    this.typeManager = typeManager;
    this.cConf = conf;
    this.namespacedLocationFactory = namespacedLocationFactory;
    this.namespaceQueryAdmin = namespaceQueryAdmin;
  }

  @Override
  public void init(HandlerContext context) {
    LOG.info("Starting DatasetTypeHandler");
  }

  @Override
  public void destroy(HandlerContext context) {
    LOG.info("Stopping DatasetTypeHandler");
  }

  @GET
  @Path("/data/modules")
  public void listModules(HttpRequest request, HttpResponder responder,
                          @PathParam("namespace-id") String namespaceId) throws Exception {
    Id.Namespace namespace = Id.Namespace.from(namespaceId);
    // Throws NamespaceNotFoundException if the namespace does not exist
    ensureNamespaceExists(namespace);
    // Sorting by name for convenience
    List<DatasetModuleMeta> list = Lists.newArrayList(typeManager.getModules(namespace));
    Collections.sort(list, new Comparator<DatasetModuleMeta>() {
      @Override
      public int compare(DatasetModuleMeta o1, DatasetModuleMeta o2) {
        return o1.getName().compareTo(o2.getName());
      }
    });
    responder.sendJson(HttpResponseStatus.OK, list);
  }

  @DELETE
  @Path("/data/modules")
  public void deleteModules(HttpRequest request, HttpResponder responder,
                            @PathParam("namespace-id") String namespaceId) throws Exception {
    Id.Namespace namespace = Id.Namespace.from(namespaceId);
    if (Id.Namespace.SYSTEM.equals(namespace)) {
      responder.sendString(HttpResponseStatus.FORBIDDEN,
                           String.format("Cannot delete modules from '%s' namespace.", namespaceId));
      return;
    }
    // Throws NamespaceNotFoundException if the namespace does not exist
    ensureNamespaceExists(namespace);
    try {
      typeManager.deleteModules(namespace);
      responder.sendStatus(HttpResponseStatus.OK);
    } catch (DatasetModuleConflictException e) {
      responder.sendString(HttpResponseStatus.CONFLICT, e.getMessage());
    }
  }

  @PUT
  @Path("/data/modules/{name}")
  public BodyConsumer addModule(HttpRequest request, HttpResponder responder,
                                @PathParam("namespace-id") String namespaceId, @PathParam("name") final String name,
                                @HeaderParam(HEADER_CLASS_NAME) final String className) throws Exception {
    Id.Namespace namespace = Id.Namespace.from(namespaceId);
    if (Id.Namespace.SYSTEM.equals(namespace)) {
      responder.sendString(HttpResponseStatus.FORBIDDEN,
                           String.format("Cannot add module to '%s' namespace.", namespaceId));
      return null;
    }
    // Throws NamespaceNotFoundException if the namespace does not exist
    ensureNamespaceExists(namespace);
    // verify namespace directory exists
    final Location namespaceHomeLocation = namespacedLocationFactory.get(namespace);
    if (!namespaceHomeLocation.exists()) {
      String msg = String.format("Home directory %s for namespace %s not found",
                                 namespaceHomeLocation, namespaceId);
      LOG.error(msg);
      responder.sendString(HttpResponseStatus.NOT_FOUND, msg);
      return null;
    }

    // Store uploaded content to a local temp file
    String namespacesDir = cConf.get(Constants.Namespace.NAMESPACES_DIR);
    File localDataDir = new File(cConf.get(Constants.CFG_LOCAL_DATA_DIR));
    File namespaceBase = new File(localDataDir, namespacesDir);
    File tempDir = new File(new File(namespaceBase, namespaceId),
                            cConf.get(Constants.AppFabric.TEMP_DIR)).getAbsoluteFile();
    if (!DirUtils.mkdirs(tempDir)) {
      throw new IOException("Could not create temporary directory at: " + tempDir);
    }

    final Id.DatasetModule datasetModuleId = Id.DatasetModule.from(namespace, name);

    return new AbstractBodyConsumer(File.createTempFile("dataset-", ".jar", tempDir)) {
      @Override
      protected void onFinish(HttpResponder responder, File uploadedFile) throws Exception {
        if (className == null) {
          // We have to delay until body upload is completed due to the fact that not all client is
          // requesting with "Expect: 100-continue" header and the client library we have cannot handle
          // connection close, and yet be able to read response reliably.
          // In longer term we should fix the client, as well as the netty-http server. However, since
          // this handler will be gone in near future, it's ok to have this workaround.
          responder.sendString(HttpResponseStatus.BAD_REQUEST, "Required header 'class-name' is absent.");
          return;
        }

        LOG.info("Adding module {}, class name: {}", datasetModuleId, className);

        String dataFabricDir = cConf.get(Constants.Dataset.Manager.OUTPUT_DIR);
        Location archiveDir = namespaceHomeLocation.append(dataFabricDir).append(name)
          .append(Constants.ARCHIVE_DIR);
        String archiveName = name + ".jar";
        Location archive = archiveDir.append(archiveName);

        // Copy uploaded content to a temporary location
        Location tmpLocation = archive.getTempFile(".tmp");
        try {
          conflictIfModuleExists(datasetModuleId);

          Locations.mkdirsIfNotExists(archiveDir);

          LOG.debug("Copy from {} to {}", uploadedFile, tmpLocation);
          Files.copy(uploadedFile, Locations.newOutputSupplier(tmpLocation));

          // Check if the module exists one more time to minimize the window of possible conflict
          conflictIfModuleExists(datasetModuleId);

          // Finally, move archive to final location
          LOG.debug("Storing module {} jar at {}", datasetModuleId, archive);
          if (tmpLocation.renameTo(archive) == null) {
            throw new IOException(String.format("Could not move archive from location: %s, to location: %s",
                                                tmpLocation, archive));
          }

          typeManager.addModule(datasetModuleId, className, archive);
          // todo: response with DatasetModuleMeta of just added module (and log this info)
          LOG.info("Added module {}", datasetModuleId);
          responder.sendStatus(HttpResponseStatus.OK);
        } catch (Exception e) {
          // In case copy to temporary file failed, or rename failed
          try {
            tmpLocation.delete();
          } catch (IOException ex) {
            LOG.warn("Failed to cleanup temporary location {}", tmpLocation);
          }
          if (e instanceof DatasetModuleConflictException) {
            responder.sendString(HttpResponseStatus.CONFLICT, e.getMessage());
          } else {
            LOG.error("Failed to add module {}", name, e);
            throw e;
          }
        }
      }
    };
  }

  @DELETE
  @Path("/data/modules/{name}")
  public void deleteModule(HttpRequest request, HttpResponder responder,
                           @PathParam("namespace-id") String namespaceId,
                           @PathParam("name") String name) throws Exception {
    Id.Namespace namespace = Id.Namespace.from(namespaceId);
    if (Id.Namespace.SYSTEM.equals(namespace)) {
      responder.sendString(HttpResponseStatus.FORBIDDEN,
                           String.format("Cannot delete module '%s' from '%s' namespace.", name, namespaceId));
      return;
    }
    // Throws NamespaceNotFoundException if the namespace does not exist
    ensureNamespaceExists(namespace);
    boolean deleted;
    try {
      deleted = typeManager.deleteModule(Id.DatasetModule.from(namespace, name));
    } catch (DatasetModuleConflictException e) {
      responder.sendString(HttpResponseStatus.CONFLICT, e.getMessage());
      return;
    }

    if (!deleted) {
      responder.sendStatus(HttpResponseStatus.NOT_FOUND);
      return;
    }

    responder.sendStatus(HttpResponseStatus.OK);
  }

  @GET
  @Path("/data/modules/{name}")
  public void getModuleInfo(HttpRequest request, HttpResponder responder,
                            @PathParam("namespace-id") String namespaceId,
                            @PathParam("name") String name) throws Exception {
    Id.Namespace namespace = Id.Namespace.from(namespaceId);
    // Throws NamespaceNotFoundException if the namespace does not exist
    ensureNamespaceExists(namespace);
    DatasetModuleMeta moduleMeta = typeManager.getModule(Id.DatasetModule.from(namespace, name));
    if (moduleMeta == null) {
      responder.sendStatus(HttpResponseStatus.NOT_FOUND);
    } else {
      responder.sendJson(HttpResponseStatus.OK, moduleMeta);
    }
  }

  @GET
  @Path("/data/types")
  public void listTypes(HttpRequest request, HttpResponder responder,
                        @PathParam("namespace-id") String namespaceId) throws Exception {
    Id.Namespace namespace = Id.Namespace.from(namespaceId);
    // Throws NamespaceNotFoundException if the namespace does not exist
    ensureNamespaceExists(namespace);
    // Sorting by name for convenience
    List<DatasetTypeMeta> list = Lists.newArrayList(typeManager.getTypes(namespace));
    Collections.sort(list, new Comparator<DatasetTypeMeta>() {
      @Override
      public int compare(DatasetTypeMeta o1, DatasetTypeMeta o2) {
        return o1.getName().compareTo(o2.getName());
      }
    });
    responder.sendJson(HttpResponseStatus.OK, list);
  }

  @GET
  @Path("/data/types/{name}")
  public void getTypeInfo(HttpRequest request, HttpResponder responder,
                          @PathParam("namespace-id") String namespaceId,
                          @PathParam("name") String name) throws Exception {
    Id.Namespace namespace = Id.Namespace.from(namespaceId);
    // Throws NamespaceNotFoundException if the namespace does not exist
    ensureNamespaceExists(namespace);
    DatasetTypeMeta typeMeta = typeManager.getTypeInfo(Id.DatasetType.from(namespace, name));
    if (typeMeta == null) {
      responder.sendStatus(HttpResponseStatus.NOT_FOUND);
    } else {
      responder.sendJson(HttpResponseStatus.OK, typeMeta);
    }
  }

  /**
   * Checks if the given module name already exists.
   *
   * @param datasetModuleId {@link Id.DatasetModule} of the module to check
   * @throws DatasetModuleConflictException if the module exists
   */
  private void conflictIfModuleExists(Id.DatasetModule datasetModuleId) throws DatasetModuleConflictException {
    if (cConf.getBoolean(Constants.Dataset.DATASET_UNCHECKED_UPGRADE)) {
      return;
    }
    DatasetModuleMeta existing = typeManager.getModule(datasetModuleId);
    if (existing != null) {
      String message = String.format("Cannot add module %s: module with same name already exists: %s",
                                     datasetModuleId, existing);
      throw new DatasetModuleConflictException(message);
    }
  }

  /**
   * Throws an exception if the specified namespace is not the system namespace and does not exist
   */
  private void ensureNamespaceExists(Id.Namespace namespace) throws Exception {
    if (!Id.Namespace.SYSTEM.equals(namespace)) {
      if (namespaceQueryAdmin.get(namespace) == null) {
        throw new NamespaceNotFoundException(namespace);
      }
    }
  }
}
