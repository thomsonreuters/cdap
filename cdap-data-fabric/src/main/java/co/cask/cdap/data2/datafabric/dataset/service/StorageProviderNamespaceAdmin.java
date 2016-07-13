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
import co.cask.cdap.common.namespace.NamespacedLocationFactory;
import co.cask.cdap.explore.client.ExploreFacade;
import co.cask.cdap.explore.service.ExploreException;
import co.cask.cdap.proto.Id;
import org.apache.twill.filesystem.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Performs namespace admin operations on underlying storage (HBase, Filesystem, Hive, etc)
 */
public class StorageProviderNamespaceAdmin {
  private static final Logger LOG = LoggerFactory.getLogger(StorageProviderNamespaceAdmin.class);

  private final CConfiguration cConf;
  private final NamespacedLocationFactory namespacedLocationFactory;
  private final ExploreFacade exploreFacade;

  StorageProviderNamespaceAdmin(CConfiguration cConf, NamespacedLocationFactory namespacedLocationFactory,
                                ExploreFacade exploreFacade) {
    this.cConf = cConf;
    this.namespacedLocationFactory = namespacedLocationFactory;
    this.exploreFacade = exploreFacade;
  }

  /**
   * Create a namespace in the underlying system.
   * Can perform operations such as creating directories, creating namespaces, etc.
   * The default implementation creates the namespace directory on the filesystem.
   * Subclasses can override to add more logic such as create namespaces in HBase, etc.
   *
   * @param namespaceId {@link Id.Namespace} for the namespace to create
   * @throws IOException if there are errors while creating the namespace
   */
  protected void create(Id.Namespace namespaceId) throws IOException, ExploreException, SQLException {
    Location namespaceHome = namespacedLocationFactory.get(namespaceId);
    if (namespaceHome.exists()) {
      LOG.warn("Home directory '{}' for namespace '{}' already exists. Deleting it.",
               namespaceHome, namespaceId);
      if (!namespaceHome.delete(true)) {
        throw new IOException(String.format("Error while deleting home directory '%s' for namespace '%s'",
                                            namespaceHome, namespaceId.getId()));
      }
    }
    if (!namespaceHome.mkdirs()) {
      throw new IOException(String.format("Error while creating home directory '%s' for namespace '%s'",
                                          namespaceHome, namespaceId));
    }

    if (cConf.getBoolean(Constants.Explore.EXPLORE_ENABLED)) {
      exploreFacade.createNamespace(namespaceId);
    }
  }

  /**
   * Delete a namespace from the underlying system
   * Can perform operations such as deleting directories, deleting namespaces, etc.
   * The default implementation deletes the namespace directory on the filesystem.
   * Subclasses can override to add more logic such as delete namespaces in HBase, etc.
   *
   * @param namespaceId {@link Id.Namespace} for the namespace to delete
   * @throws IOException if there are errors while deleting the namespace
   */
  protected void delete(Id.Namespace namespaceId) throws IOException, ExploreException, SQLException {
    // TODO: CDAP-1581: Implement soft delete
    Location namespaceHome = namespacedLocationFactory.get(namespaceId);
    if (namespaceHome.exists()) {
      if (!namespaceHome.delete(true)) {
        throw new IOException(String.format("Error while deleting home directory '%s' for namespace '%s'",
                                            namespaceHome, namespaceId.getId()));
      }
    } else {
      // warn that namespace home was not found and skip delete step
      LOG.warn(String.format("Home directory '%s' for namespace '%s' does not exist.",
                             namespaceHome, namespaceId));
    }

    if (cConf.getBoolean(Constants.Explore.EXPLORE_ENABLED)) {
      exploreFacade.removeNamespace(namespaceId);
    }
  }
}
