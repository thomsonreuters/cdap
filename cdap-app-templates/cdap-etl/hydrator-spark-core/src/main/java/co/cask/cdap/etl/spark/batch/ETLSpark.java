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

package co.cask.cdap.etl.spark.batch;

import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.spark.AbstractSpark;
import co.cask.cdap.api.spark.SparkClientContext;
import co.cask.cdap.etl.api.batch.BatchAggregator;
import co.cask.cdap.etl.api.batch.BatchConfigurable;
import co.cask.cdap.etl.api.batch.BatchSink;
import co.cask.cdap.etl.api.batch.BatchSinkContext;
import co.cask.cdap.etl.api.batch.BatchSource;
import co.cask.cdap.etl.api.batch.BatchSourceContext;
import co.cask.cdap.etl.api.batch.SparkPluginContext;
import co.cask.cdap.etl.api.batch.SparkSink;
import co.cask.cdap.etl.batch.AbstractAggregatorContext;
import co.cask.cdap.etl.batch.BatchPhaseSpec;
import co.cask.cdap.etl.common.CompositeFinisher;
import co.cask.cdap.etl.common.Constants;
import co.cask.cdap.etl.common.DatasetContextLookupProvider;
import co.cask.cdap.etl.common.Finisher;
import co.cask.cdap.etl.common.SetMultimapCodec;
import co.cask.cdap.etl.planner.StageInfo;
import co.cask.cdap.internal.io.SchemaTypeAdapter;
import com.google.common.collect.SetMultimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.spark.SparkConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configures and sets up runs of {@link BatchSparkPipelineDriver}.
 */
public class ETLSpark extends AbstractSpark {
  private static final Logger LOG = LoggerFactory.getLogger(ETLSpark.class);
  private static final Gson GSON = new GsonBuilder()
    .registerTypeAdapter(SetMultimap.class, new SetMultimapCodec<>())
    .registerTypeAdapter(Schema.class, new SchemaTypeAdapter())
    .create();

  private final BatchPhaseSpec phaseSpec;
  private Finisher finisher;
  private List<File> cleanupFiles;

  public ETLSpark(BatchPhaseSpec phaseSpec) {
    this.phaseSpec = phaseSpec;
  }

  @Override
  protected void configure() {
    setName(phaseSpec.getPhaseName());
    setDescription(phaseSpec.getDescription());

    setMainClass(BatchSparkPipelineDriver.class);

    setExecutorResources(phaseSpec.getResources());
    setDriverResources(phaseSpec.getDriverResources());

    // add source, sink, transform ids to the properties. These are needed at runtime to instantiate the plugins
    Map<String, String> properties = new HashMap<>();
    Gson gson = new GsonBuilder()
      .registerTypeAdapter(SetMultimap.class, new SetMultimapCodec<>())
      .registerTypeAdapter(Schema.class, new SchemaTypeAdapter())
      .create();
    properties.put(Constants.PIPELINEID, gson.toJson(phaseSpec, BatchPhaseSpec.class));
    setProperties(properties);
  }

  @Override
  public void beforeSubmit(SparkClientContext context) throws Exception {
    cleanupFiles = new ArrayList<>();
    CompositeFinisher.Builder finishers = CompositeFinisher.builder();

    SparkConf sparkConf = new SparkConf();
    sparkConf.set("spark.driver.extraJavaOptions", "-XX:MaxPermSize=256m");
    sparkConf.set("spark.executor.extraJavaOptions", "-XX:MaxPermSize=256m");
    context.setSparkConf(sparkConf);

    Map<String, String> properties = context.getSpecification().getProperties();
    String pipelineStr = properties.get(Constants.PIPELINEID);
    LOG.error("ashau - pipelineSTr = {}", pipelineStr);
    BatchPhaseSpec phaseSpec = GSON.fromJson(properties.get(Constants.PIPELINEID), BatchPhaseSpec.class);
    DatasetContextLookupProvider lookProvider = new DatasetContextLookupProvider(context);

    SparkBatchSourceFactory sourceFactory = new SparkBatchSourceFactory();
    SparkBatchSinkFactory sinkFactory = new SparkBatchSinkFactory();
    Map<String, Integer> stagePartitions = new HashMap<>();

    for (StageInfo stageInfo : phaseSpec.getPhase()) {
      String stageName = stageInfo.getName();
      String pluginType = stageInfo.getPluginType();

      if (BatchSource.PLUGIN_TYPE.equals(pluginType)) {
        BatchConfigurable<BatchSourceContext> batchSource = context.newPluginInstance(stageName);
        BatchSourceContext sourceContext = new SparkBatchSourceContext(sourceFactory, context, lookProvider, stageName);
        batchSource.prepareRun(sourceContext);
        finishers.add(batchSource, sourceContext);
      } else if (BatchSink.PLUGIN_TYPE.equals(pluginType)) {
        BatchConfigurable<BatchSinkContext> batchSink = context.newPluginInstance(stageName);
        BatchSinkContext sinkContext = new SparkBatchSinkContext(sinkFactory, context, null, stageName);
        batchSink.prepareRun(sinkContext);
        finishers.add(batchSink, sinkContext);
      } else if (SparkSink.PLUGIN_TYPE.equals(pluginType)) {
        BatchConfigurable<SparkPluginContext> sparkSink = context.newPluginInstance(stageName);
        SparkPluginContext sparkPluginContext = new BasicSparkPluginContext(context, lookProvider, stageName);
        sparkSink.prepareRun(sparkPluginContext);
        finishers.add(sparkSink, sparkPluginContext);
      } else if (BatchAggregator.PLUGIN_TYPE.equals(pluginType)) {
        BatchAggregator aggregator = context.newPluginInstance(stageName);
        AbstractAggregatorContext aggregatorContext =
          new SparkAggregatorContext(context, new DatasetContextLookupProvider(context), stageName);
        aggregator.prepareRun(aggregatorContext);
        finishers.add(aggregator, aggregatorContext);
        stagePartitions.put(stageName, aggregatorContext.getNumPartitions());
      }
    }

    File configFile = File.createTempFile("ETLSpark", ".config");
    cleanupFiles.add(configFile);
    try (OutputStream os = new FileOutputStream(configFile)) {
      sourceFactory.serialize(os);
      sinkFactory.serialize(os);
      DataOutput dataOutput = new DataOutputStream(os);
      dataOutput.writeUTF(GSON.toJson(stagePartitions));
    }

    finisher = finishers.build();
    context.localize("ETLSpark.config", configFile.toURI());
  }

  @Override
  public void onFinish(boolean succeeded, SparkClientContext context) throws Exception {
    finisher.onFinish(succeeded);
    for (File file : cleanupFiles) {
      if (!file.delete()) {
        LOG.warn("Failed to clean up resource {} ", file);
      }
    }
  }

}
