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

package co.cask.cdap.data2.util.hbase;

import co.cask.cdap.data2.increment.hbase10cdh.IncrementHandler;
import co.cask.cdap.data2.transaction.coprocessor.hbase10cdh.DefaultTransactionProcessor;
import co.cask.cdap.data2.transaction.queue.coprocessor.hbase10cdh.DequeueScanObserver;
import co.cask.cdap.data2.transaction.queue.coprocessor.hbase10cdh.HBaseQueueRegionObserver;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Coprocessor;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.NamespaceDescriptor;
import org.apache.hadoop.hbase.NamespaceNotFoundException;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.io.compress.Compression;

import java.io.IOException;
import java.util.List;

/**
 *
 */
@SuppressWarnings("ConstantConditions")
public class HBase10CDHTableUtil extends HBaseTableUtil {

  @Override
  public HTable createHTable(Configuration conf, HBaseTableId tableId) throws IOException {
    Preconditions.checkArgument(tableId != null, "Table id should not be null");
    return new HTable(conf, nameConverter.toTableName(tablePrefix, tableId));
  }

  @Override
  public HTableDescriptorBuilder buildHTableDescriptor(HBaseTableId tableId) {
    Preconditions.checkArgument(tableId != null, "Table id should not be null");
    return new HTableDescriptorBuilder(nameConverter.toTableName(tablePrefix, tableId));
  }

  @Override
  public HTableDescriptorBuilder buildHTableDescriptor(HTableDescriptor tableDescriptorToCopy) {
    Preconditions.checkArgument(tableDescriptorToCopy != null, "Table descriptor should not be null");
    return new HTableDescriptorBuilder(tableDescriptorToCopy);
  }

  @Override
  public HTableDescriptor getHTableDescriptor(HBaseAdmin admin, HBaseTableId tableId) throws IOException {
    Preconditions.checkArgument(admin != null, "HBaseAdmin should not be null");
    Preconditions.checkArgument(tableId != null, "Table Id should not be null.");
    return admin.getTableDescriptor(nameConverter.toTableName(tablePrefix, tableId));
  }

  @Override
  public boolean hasNamespace(HBaseAdmin admin, String namespace) throws IOException {
    Preconditions.checkArgument(admin != null, "HBaseAdmin should not be null");
    Preconditions.checkArgument(namespace != null, "Namespace should not be null.");
    try {
      admin.getNamespaceDescriptor(nameConverter.encodeHBaseEntity(namespace));
      return true;
    } catch (NamespaceNotFoundException e) {
      return false;
    }
  }

  @Override
  public void createNamespaceIfNotExists(HBaseAdmin admin, String namespace) throws IOException {
    Preconditions.checkArgument(admin != null, "HBaseAdmin should not be null");
    Preconditions.checkArgument(namespace != null, "Namespace should not be null.");
    if (!hasNamespace(admin, namespace)) {
      NamespaceDescriptor namespaceDescriptor =
        NamespaceDescriptor.create(nameConverter.encodeHBaseEntity(namespace)).build();
      admin.createNamespace(namespaceDescriptor);
    }
  }

  @Override
  public void deleteNamespaceIfExists(HBaseAdmin admin, String namespace) throws IOException {
    Preconditions.checkArgument(admin != null, "HBaseAdmin should not be null");
    Preconditions.checkArgument(namespace != null, "Namespace should not be null.");
    if (hasNamespace(admin, namespace)) {
      admin.deleteNamespace(nameConverter.encodeHBaseEntity(namespace));
    }
  }

  @Override
  public void disableTable(HBaseAdmin admin, HBaseTableId tableId) throws IOException {
    Preconditions.checkArgument(admin != null, "HBaseAdmin should not be null");
    Preconditions.checkArgument(tableId != null, "Table Id should not be null.");
    admin.disableTable(nameConverter.toTableName(tablePrefix, tableId));
  }

  @Override
  public void enableTable(HBaseAdmin admin, HBaseTableId tableId) throws IOException {
    Preconditions.checkArgument(admin != null, "HBaseAdmin should not be null");
    Preconditions.checkArgument(tableId != null, "Table Id should not be null.");
    admin.enableTable(nameConverter.toTableName(tablePrefix, tableId));
  }

  @Override
  public boolean tableExists(HBaseAdmin admin, HBaseTableId tableId) throws IOException {
    Preconditions.checkArgument(admin != null, "HBaseAdmin should not be null");
    Preconditions.checkArgument(tableId != null, "Table Id should not be null.");
    return admin.tableExists(nameConverter.toTableName(tablePrefix, tableId));
  }

  @Override
  public void deleteTable(HBaseAdmin admin, HBaseTableId tableId) throws IOException {
    Preconditions.checkArgument(admin != null, "HBaseAdmin should not be null");
    Preconditions.checkArgument(tableId != null, "Table Id should not be null.");
    admin.deleteTable(nameConverter.toTableName(tablePrefix, tableId));
  }

  @Override
  public void modifyTable(HBaseAdmin admin, HTableDescriptor tableDescriptor) throws IOException {
    Preconditions.checkArgument(admin != null, "HBaseAdmin should not be null");
    Preconditions.checkArgument(tableDescriptor != null, "Table descriptor should not be null.");
    admin.modifyTable(tableDescriptor.getTableName(), tableDescriptor);
  }

  @Override
  public List<HRegionInfo> getTableRegions(HBaseAdmin admin, HBaseTableId tableId) throws IOException {
    Preconditions.checkArgument(admin != null, "HBaseAdmin should not be null");
    Preconditions.checkArgument(tableId != null, "Table Id should not be null.");
    return admin.getTableRegions(nameConverter.toTableName(tablePrefix, tableId));
  }

  @Override
  public List<HBaseTableId> listTablesInNamespace(HBaseAdmin admin, String namespaceId) throws IOException {
    List<HBaseTableId> tableIds = Lists.newArrayList();
    HTableDescriptor[] hTableDescriptors =
      admin.listTableDescriptorsByNamespace(nameConverter.encodeHBaseEntity(namespaceId));
    for (HTableDescriptor hTableDescriptor : hTableDescriptors) {
      if (isCDAPTable(hTableDescriptor)) {
        tableIds.add(nameConverter.from(hTableDescriptor));
      }
    }
    return tableIds;
  }

  @Override
  public List<HBaseTableId> listTables(HBaseAdmin admin) throws IOException {
    List<HBaseTableId> tableIds = Lists.newArrayList();
    HTableDescriptor[] hTableDescriptors = admin.listTables();
    for (HTableDescriptor hTableDescriptor : hTableDescriptors) {
      if (isCDAPTable(hTableDescriptor)) {
        tableIds.add(nameConverter.from(hTableDescriptor));
      }
    }
    return tableIds;
  }

  @Override
  public void setCompression(HColumnDescriptor columnDescriptor, CompressionType type) {
    switch (type) {
      case LZO:
        columnDescriptor.setCompressionType(Compression.Algorithm.LZO);
        break;
      case SNAPPY:
        columnDescriptor.setCompressionType(Compression.Algorithm.SNAPPY);
        break;
      case GZIP:
        columnDescriptor.setCompressionType(Compression.Algorithm.GZ);
        break;
      case NONE:
        columnDescriptor.setCompressionType(Compression.Algorithm.NONE);
        break;
      default:
        throw new IllegalArgumentException("Unsupported compression type: " + type);
    }
  }

  @Override
  public void setBloomFilter(HColumnDescriptor columnDescriptor, BloomType type) {
    switch (type) {
      case ROW:
        columnDescriptor.setBloomFilterType(org.apache.hadoop.hbase.regionserver.BloomType.ROW);
        break;
      case ROWCOL:
        columnDescriptor.setBloomFilterType(org.apache.hadoop.hbase.regionserver.BloomType.ROWCOL);
        break;
      case NONE:
        columnDescriptor.setBloomFilterType(org.apache.hadoop.hbase.regionserver.BloomType.NONE);
        break;
      default:
        throw new IllegalArgumentException("Unsupported bloom filter type: " + type);
    }
  }

  @Override
  public CompressionType getCompression(HColumnDescriptor columnDescriptor) {
    Compression.Algorithm type = columnDescriptor.getCompressionType();
    switch (type) {
      case LZO:
        return CompressionType.LZO;
      case SNAPPY:
        return CompressionType.SNAPPY;
      case GZ:
        return CompressionType.GZIP;
      case NONE:
        return CompressionType.NONE;
      default:
        throw new IllegalArgumentException("Unsupported compression type: " + type);
    }
  }

  @Override
  public BloomType getBloomFilter(HColumnDescriptor columnDescriptor) {
    org.apache.hadoop.hbase.regionserver.BloomType type = columnDescriptor.getBloomFilterType();
    switch (type) {
      case ROW:
        return BloomType.ROW;
      case ROWCOL:
        return BloomType.ROWCOL;
      case NONE:
        return BloomType.NONE;
      default:
        throw new IllegalArgumentException("Unsupported bloom filter type: " + type);
    }
  }

  @Override
  public Class<? extends Coprocessor> getTransactionDataJanitorClassForVersion() {
    return DefaultTransactionProcessor.class;
  }

  @Override
  public Class<? extends Coprocessor> getQueueRegionObserverClassForVersion() {
    return HBaseQueueRegionObserver.class;
  }

  @Override
  public Class<? extends Coprocessor> getDequeueScanObserverClassForVersion() {
    return DequeueScanObserver.class;
  }

  @Override
  public Class<? extends Coprocessor> getIncrementHandlerClassForVersion() {
    return IncrementHandler.class;
  }

  @Override
  protected HTableNameConverter getHTableNameConverter() {
    return nameConverter;
  }
}
