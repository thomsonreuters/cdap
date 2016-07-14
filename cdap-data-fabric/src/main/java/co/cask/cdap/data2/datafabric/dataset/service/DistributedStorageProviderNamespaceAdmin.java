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

import co.cask.cdap.common.NamespaceNotFoundException;
import co.cask.cdap.common.UnauthenticatedException;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.namespace.NamespaceQueryAdmin;
import co.cask.cdap.common.namespace.NamespacedLocationFactory;
import co.cask.cdap.data2.util.hbase.HBaseTableUtil;
import co.cask.cdap.explore.client.ExploreFacade;
import co.cask.cdap.explore.service.ExploreException;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.NamespaceConfig;
import com.google.common.base.Strings;
import com.google.inject.Inject;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Manages namespaces on underlying systems - HDFS, HBase, Hive, etc.
 */
public final class DistributedStorageProviderNamespaceAdmin extends StorageProviderNamespaceAdmin {
  private static final Logger LOG = LoggerFactory.getLogger(DistributedStorageProviderNamespaceAdmin.class);

  private final Configuration hConf;
  private final HBaseTableUtil tableUtil;
  private final NamespaceQueryAdmin namespaceQueryAdmin;
  private HBaseAdmin hBaseAdmin;

  @Inject
  public DistributedStorageProviderNamespaceAdmin(CConfiguration cConf,
                                                  NamespacedLocationFactory namespacedLocationFactory,
                                                  ExploreFacade exploreFacade, HBaseTableUtil tableUtil,
                                                  NamespaceQueryAdmin namespaceQueryAdmin) {
    super(cConf, namespacedLocationFactory, exploreFacade, namespaceQueryAdmin);
    this.hConf = HBaseConfiguration.create();
    this.tableUtil = tableUtil;
    this.namespaceQueryAdmin = namespaceQueryAdmin;
  }

  @Override
  public void create(Id.Namespace namespaceId, NamespaceConfig namespaceConfig) throws IOException, ExploreException,
    SQLException, NamespaceNotFoundException, UnauthenticatedException {
    // create filesystem directory
    super.create(namespaceId, namespaceConfig);
    // TODO: CDAP-1519: Create base directory for filesets under namespace home
    tableUtil.createNamespaceIfNotExists(getAdmin(), namespaceId);
  }

  @Override
  public void delete(Id.Namespace namespaceId) throws IOException, ExploreException, SQLException,
    NamespaceNotFoundException, UnauthenticatedException {
    // soft delete namespace directory from filesystem
    super.delete(namespaceId);
    NamespaceConfig namespaceConfig = namespaceQueryAdmin.get(namespaceId).getConfig();

    if (Strings.isNullOrEmpty(namespaceConfig.getHbaseNamespace())) {
      // delete HBase namespace
      tableUtil.deleteNamespaceIfExists(getAdmin(), Id.Namespace.from(namespaceId.getId()));
    } else {
      // custom namespace mapping is set for HBase, hence don't do anything during delete since the lifecycle of the
      // namespace will be managed by the user
      LOG.warn("Custom HBase mapping {} was found while deleting namespace {}. Hence skipping deletion of " +
                 "HBase namespace", namespaceConfig.getHbaseNamespace(), namespaceId.getId());
    }
  }

  private HBaseAdmin getAdmin() throws IOException {
    if (hBaseAdmin == null) {
      hBaseAdmin = new HBaseAdmin(hConf);
    }
    return hBaseAdmin;
  }
}
