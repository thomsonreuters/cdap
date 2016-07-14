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

package co.cask.cdap.data2.util.hbase;

import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.namespace.NamespaceQueryAdmin;
import com.google.inject.Inject;

/**
 * Factory for HBase version-specific {@link HBaseTableUtil} instances.
 */
public class HBaseTableUtilFactory extends HBaseVersionSpecificFactory<HBaseTableUtil> {

  private final CConfiguration cConf;
  private final NamespaceQueryAdmin namespaceQueryAdmin;

  public HBaseTableUtilFactory(CConfiguration cConf) {
    this.cConf = cConf;
    this.namespaceQueryAdmin = null;
  }

  @Inject
  public HBaseTableUtilFactory(CConfiguration cConf, NamespaceQueryAdmin namespaceQueryAdmin) {
    this.cConf = cConf;
    this.namespaceQueryAdmin = namespaceQueryAdmin;
  }

  @Override
  protected HBaseTableUtil createInstance(String className) throws ClassNotFoundException {
    HBaseTableUtil hBaseTableUtil = super.createInstance(className);
    HTableNameConverterFactory nameConverterFactory = new HTableNameConverterFactory(namespaceQueryAdmin);
    hBaseTableUtil.setCConf(cConf);
    hBaseTableUtil.setNamespaceQueryAdmin(namespaceQueryAdmin);
    hBaseTableUtil.setNameConverter(nameConverterFactory.createInstance(className));
    return hBaseTableUtil;
  }

  public static Class<? extends HBaseTableUtil> getHBaseTableUtilClass() {
    // Since we only need the class name, it is fine to have a null CConfiguration and null namespaceQueryAdmin,
    // since we do not use the tableUtil instance
    return new HBaseTableUtilFactory(null, null).get().getClass();
  }

  @Override
  protected String getHBase96Classname() {
    return "co.cask.cdap.data2.util.hbase.HBase96TableUtil";
  }

  @Override
  protected String getHBase98Classname() {
    return "co.cask.cdap.data2.util.hbase.HBase98TableUtil";
  }

  @Override
  protected String getHBase10Classname() {
    return "co.cask.cdap.data2.util.hbase.HBase10TableUtil";
  }

  @Override
  protected String getHBase10CDHClassname() {
    return "co.cask.cdap.data2.util.hbase.HBase10CDHTableUtil";
  }

  @Override
  protected String getHBase11Classname() {
    return "co.cask.cdap.data2.util.hbase.HBase11TableUtil";
  }

  @Override
  protected String getHBase10CHD550ClassName() {
    return "co.cask.cdap.data2.util.hbase.HBase10CDH550TableUtil";
  }

  @Override
  protected String getHBase12CHD570ClassName() {
    return "co.cask.cdap.data2.util.hbase.HBase12CDH570TableUtil";
  }
}
