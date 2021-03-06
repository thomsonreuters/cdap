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

import co.cask.cdap.data2.util.TableId;
import co.cask.cdap.proto.Id;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;

/**
 * Utility methods for dealing with HBase table name conversions in CDH 5.7.0 version of HBase 1.2.x
 */
public class HTable12CDH570NameConverter extends HTableNameConverter {

  @Override
  public String getSysConfigTablePrefix(HTableDescriptor htd) {
    return getNamespacePrefix(htd) + "_" + Id.Namespace.SYSTEM.getId() + ":";
  }

  @Override
  public TableId from(HTableDescriptor htd) {
    return prefixedTableIdFromTableName(htd.getTableName()).getTableId();
  }

  @Override
  public String getNamespacePrefix(HTableDescriptor htd) {
    return prefixedTableIdFromTableName(htd.getTableName()).getTablePrefix();
  }

  public TableName toTableName(String tablePrefix, TableId tableId) {
    return TableName.valueOf(toHBaseNamespace(tablePrefix, tableId.getNamespace()),
                             getHBaseTableName(tablePrefix, tableId));
  }

  private PrefixedTableId prefixedTableIdFromTableName(TableName tableName) {
    return fromHBaseTableName(tableName.getNamespaceAsString(), tableName.getQualifierAsString());
  }
}
