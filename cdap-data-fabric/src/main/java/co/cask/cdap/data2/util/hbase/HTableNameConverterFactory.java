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

import co.cask.cdap.common.namespace.NamespaceQueryAdmin;

import javax.annotation.Nullable;

/**
 * Factory for HBase version-specific {@link HTableNameConverterFactory} instances.
 */
public class HTableNameConverterFactory extends HBaseVersionSpecificFactory<HTableNameConverter> {

  private final NamespaceQueryAdmin namespaceQueryAdmin;

  public HTableNameConverterFactory(@Nullable NamespaceQueryAdmin namespaceQueryAdmin) {
    this.namespaceQueryAdmin = namespaceQueryAdmin;
  }

  @Override
  protected HTableNameConverter createInstance(String className) throws ClassNotFoundException {
    HTableNameConverter hTableNameConverter = super.createInstance(className);
    hTableNameConverter.setNamespaceQueryAdmin(namespaceQueryAdmin);
    return hTableNameConverter;
  }

  @Override
  protected String getHBase96Classname() {
    return "co.cask.cdap.data2.util.hbase.HTable96NameConverter";
  }

  @Override
  protected String getHBase98Classname() {
    return "co.cask.cdap.data2.util.hbase.HTable98NameConverter";
  }

  @Override
  protected String getHBase10Classname() {
    return "co.cask.cdap.data2.util.hbase.HTable10NameConverter";
  }

  @Override
  protected String getHBase10CDHClassname() {
    return "co.cask.cdap.data2.util.hbase.HTable10CDHNameConverter";
  }

  @Override
  protected String getHBase11Classname() {
    return "co.cask.cdap.data2.util.hbase.HTable11NameConverter";
  }

  @Override
  protected String getHBase10CHD550ClassName() {
    return "co.cask.cdap.data2.util.hbase.HTable10CDH550NameConverter";
  }

  @Override
  protected String getHBase12CHD570ClassName() {
    return "co.cask.cdap.data2.util.hbase.HTable12CDH570NameConverter";
  }
}
