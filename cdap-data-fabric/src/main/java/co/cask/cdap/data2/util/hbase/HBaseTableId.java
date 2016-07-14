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

package co.cask.cdap.data2.util.hbase;

import com.google.common.base.Objects;

/**
 * Identifier for an HBase table
 */
public class HBaseTableId {
  private final String hbaseNamespace;
  private final String tableName;

  public HBaseTableId(String hbaseNamespace, String tableName) {
    this.hbaseNamespace = hbaseNamespace;
    this.tableName = tableName;
  }

  public String getHbaseNamespace() {
    return hbaseNamespace;
  }

  public String getTableName() {
    return tableName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof HBaseTableId)) {
      return false;
    }

    HBaseTableId that = (HBaseTableId) o;
    return Objects.equal(hbaseNamespace, that.getHbaseNamespace()) && Objects.equal(tableName, that.getTableName());
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(hbaseNamespace, tableName);
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
      .add("namespace", hbaseNamespace)
      .add("tableName", tableName)
      .toString();
  }
}
