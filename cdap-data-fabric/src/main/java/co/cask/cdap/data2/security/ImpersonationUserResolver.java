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

package co.cask.cdap.data2.security;

import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.kerberos.SecurityUtil;
import co.cask.cdap.common.utils.DirUtils;
import co.cask.cdap.proto.NamespaceConfig;
import co.cask.cdap.proto.NamespaceMeta;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.id.NamespacedId;
import co.cask.cdap.store.NamespaceStore;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.inject.Inject;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.twill.filesystem.LocationFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;

/**
 * Helper class to resolve the principal which CDAP will launch programs as.
 * This class should only be used if Kerberos security is enabled and it is for a user namespace.
 */
public class ImpersonationUserResolver {
  private static final Logger LOG = LoggerFactory.getLogger(ImpersonationUserResolver.class);

  private final CConfiguration cConf;
  private final NamespaceStore namespaceStore;
  private final LocationFactory locationFactory;

  private final String defaultPrincipal;
  private final String defaultKeytabPath;

  @Inject
  public ImpersonationUserResolver(CConfiguration cConf, NamespaceStore namespaceStore,
                                   LocationFactory locationFactory) {
    this.cConf = cConf;
    this.namespaceStore = namespaceStore;
    this.locationFactory = locationFactory;
    this.defaultPrincipal = cConf.get(Constants.Security.CFG_CDAP_MASTER_KRB_PRINCIPAL);
    this.defaultKeytabPath = cConf.get(Constants.Security.CFG_CDAP_MASTER_KRB_KEYTAB_PATH);
  }

  /**
   * Get impersonation info for a given namespace. If the info configured at the namespace level is empty,
   * returns the info configured at the cdap level.
   *
   * @return configured {@link ImpersonationInfo}.
   */
  public ImpersonationInfo getImpersonationInfo(NamespacedId namespacedId) {
    return getImpersonationInfo(getNamespaceConfig(namespacedId));
  }

  public ImpersonationInfo getImpersonationInfo(NamespaceConfig namespaceConfig) {
    String principal = Objects.firstNonNull(namespaceConfig.getPrincipal(), defaultPrincipal);
    String keytabPath = Objects.firstNonNull(namespaceConfig.getKeytabPath(), defaultKeytabPath);
    return new ImpersonationInfo(principal, keytabPath);
  }

  private NamespaceConfig getNamespaceConfig(NamespacedId namespacedId) {
    NamespaceMeta meta = namespaceStore.get(new NamespaceId(namespacedId.getNamespace()).toId());
    Preconditions.checkNotNull(meta,
                               "Failed to retrieve namespace meta for namespace id {}", namespacedId.getNamespace());
    return meta.getConfig();
  }

  /**
   * Resolves the impersonation info for a given namespace. Then, creates and returns a UserGroupInformation with this
   * information, performing any keytab localization, if necessary.
   *
   * @return a {@link UserGroupInformation}, based upon the information configured for a particular namespace
   * @throws IOException if there was any IOException during localization of the keytab
   */
  public UserGroupInformation getResolvedUser(NamespacedId namespacedId) throws IOException {
    return getResolvedUser(getImpersonationInfo(namespacedId));
  }

  public UserGroupInformation getResolvedUser(ImpersonationInfo impersonationInfo) throws IOException {
    // TODO: figure out what this is (where exactly it writes to)
    File tempDir = DirUtils.createTempDir(new File(cConf.get(Constants.CFG_LOCAL_DATA_DIR),
                                                   cConf.get(Constants.AppFabric.TEMP_DIR)).getAbsoluteFile());

    String localizedKeytabPath =
      ImpersonationUtils.localizeKeytab(locationFactory, URI.create(impersonationInfo.getKeytabPath()), tempDir);

    LOG.info("Configured impersonation info: {}", impersonationInfo);

    String expandedPrincipal = SecurityUtil.expandPrincipal(impersonationInfo.getPrincipal());
    LOG.info("Logging in as: principal={}, keytab={}", expandedPrincipal, localizedKeytabPath);

    return UserGroupInformation.loginUserFromKeytabAndReturnUGI(expandedPrincipal, localizedKeytabPath);
  }
}
