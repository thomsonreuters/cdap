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

package co.cask.cdap.etl.mock.action;

import co.cask.cdap.api.TxRunnable;
import co.cask.cdap.api.annotation.Name;
import co.cask.cdap.api.annotation.Plugin;
import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.data.DatasetContext;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.dataset.table.Put;
import co.cask.cdap.api.dataset.table.Row;
import co.cask.cdap.api.dataset.table.Scanner;
import co.cask.cdap.api.dataset.table.Table;
import co.cask.cdap.api.plugin.PluginClass;
import co.cask.cdap.api.plugin.PluginConfig;
import co.cask.cdap.api.plugin.PluginPropertyField;
import co.cask.cdap.etl.api.PipelineConfigurer;
import co.cask.cdap.etl.api.action.Action;
import co.cask.cdap.etl.api.action.ActionContext;
import co.cask.cdap.etl.proto.v2.ETLPlugin;
import co.cask.cdap.format.StructuredRecordStringConverter;
import co.cask.cdap.test.DataSetManager;
import com.google.common.collect.ImmutableMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock sink that writes records to a Table and has a utility method for getting all records written.
 */
@Plugin(type = Action.PLUGIN_TYPE)
@Name("TableWriterAction")
public class MockAction extends Action {
  private final Config config;
  public static final PluginClass PLUGIN_CLASS = getPluginClass();

  public static class Config extends PluginConfig {
    private String tableName;
  }

  public MockAction(Config config) {
    this.config = config;
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) throws IllegalArgumentException {
    pipelineConfigurer.createDataset(config.tableName, Table.class);
  }

  @Override
  public void run(ActionContext context) throws Exception {
    context.execute(new TxRunnable() {
      @Override
      public void run(DatasetContext context) throws Exception {
        Table table = context.getDataset(config.tableName);
        Put put = new Put("somekey");
        put.add("c1", "value1");
        table.put(put);
      }
    });
  }

  public static ETLPlugin getPlugin(String tableName) {
    return new ETLPlugin("TableWriterAction", Action.PLUGIN_TYPE, ImmutableMap.of("tableName", tableName), null);
  }

  private static PluginClass getPluginClass() {
    Map<String, PluginPropertyField> properties = new HashMap<>();
    properties.put("tableName", new PluginPropertyField("tableName", "", "string", true, false));
    return new PluginClass(Action.PLUGIN_TYPE, "TableWriterAction", "", MockAction.class.getName(),
                           "config", properties);
  }

  /**
   *
   * @param tableManager
   * @return
   * @throws Exception
   */
  public static String readOutput(DataSetManager<Table> tableManager) throws Exception {
    Table table = tableManager.get();
    return Bytes.toString(table.get(Bytes.toBytes("somekey"), Bytes.toBytes("c1")));
  }
}
