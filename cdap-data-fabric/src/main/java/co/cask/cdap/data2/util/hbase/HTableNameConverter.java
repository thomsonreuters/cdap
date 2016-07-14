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

package co.cask.cdap.data2.util.hbase;

import co.cask.cdap.proto.Id;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Common utility methods for dealing with HBase table name conversions.
 */
public abstract class HTableNameConverter {

  private String encodeHBaseEntity(String entityName) {
    try {
      return URLEncoder.encode(entityName, "ASCII");
    } catch (UnsupportedEncodingException e) {
      // this can never happen - we know that ASCII is a supported character set!
      throw new RuntimeException(e);
    }
  }

  private String getBackwardCompatibleTableName(String tablePrefix, HBaseTableId tableId) {
    String tableName = tableId.getTableName();
    // handle table names in default namespace so we do not have to worry about upgrades
    // cdap default namespace always maps to hbase default namespace and vice versa
    if (Id.Namespace.DEFAULT.getId().equals(tableId.getHbaseNamespace())) {
      // if the table name starts with 'system.', then its a queue or stream table. Do not add namespace to table name
      // e.g. namespace = default, tableName = system.queue.config. Resulting table name = cdap.system.queue.config
      // also no need to prepend the table name if it already starts with 'user'.
      // TODO: the 'user' should be prepended by the HBaseTableAdmin.
      if (tableName.startsWith(String.format("%s.", Id.Namespace.SYSTEM.getId()))) {
        return Joiner.on(".").join(tablePrefix, tableName);
      }
      // if the table name does not start with 'system.', then its a user dataset. Add 'user' to the table name to
      // maintain backward compatibility. Also, do not add namespace to the table name
      // e.g. namespace = default, tableName = purchases. Resulting table name = cdap.user.purchases
      return Joiner.on(".").join(tablePrefix, "user", tableName);
    }
    // if the namespace is not default, do not need to change anything
    return tableName;
  }

  /**
   * @return Backward compatible, ASCII encoded table name
   */
  protected String getHBaseTableName(String tablePrefix, HBaseTableId tableId) {
    Preconditions.checkArgument(tablePrefix != null, "Table prefix should not be null.");
    return encodeHBaseEntity(getBackwardCompatibleTableName(tablePrefix, tableId));
  }

  /**
   * Gets the system configuration table prefix.
   *
   * @param prefix Prefix string
   * @return System configuration table prefix (full table name minus the table qualifier).
   * Example input: "cdap_ns.table.name"  -->  output: "cdap_system."   (hbase 94)
   * Example input: "cdap.table.name"     -->  output: "cdap_system."   (hbase 94. input table is in default namespace)
   * Example input: "cdap_ns:table.name"  -->  output: "cdap_system:"   (hbase 96, 98)
   */
  public String getSysConfigTablePrefix(String prefix) {
    return prefix + "_" + Id.Namespace.SYSTEM.getId() + ":";
  }

  /**
   * Returns {@link HBaseTableId} for the table represented by the given {@link HTableDescriptor}.
   */
  public abstract HBaseTableId from(HTableDescriptor htd);

  /**
   * Construct and return the HBase tableName from {@link HBaseTableId} and tablePrefix.
   */
  public abstract TableName toTableName(String tablePrefix, HBaseTableId tableId);

  protected HBaseTableId fromHBaseTableName(String namespace, String qualifier) {
    Preconditions.checkArgument(namespace != null, "Table namespace should not be null.");
    Preconditions.checkArgument(qualifier != null, "Table qualifier should not be null.");
    return new HBaseTableId(namespace, qualifier);
  }
}
