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

package co.cask.cdap.datapipeline;

import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.dataset.lib.KeyValueTable;
import co.cask.cdap.api.dataset.table.Table;
import co.cask.cdap.api.workflow.NodeStatus;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.datapipeline.mock.NaiveBayesClassifier;
import co.cask.cdap.datapipeline.mock.NaiveBayesTrainer;
import co.cask.cdap.datapipeline.mock.SpamMessage;
import co.cask.cdap.etl.api.batch.SparkCompute;
import co.cask.cdap.etl.api.batch.SparkSink;
import co.cask.cdap.etl.mock.action.MockAction;
import co.cask.cdap.etl.mock.batch.MockSink;
import co.cask.cdap.etl.mock.batch.MockSource;
import co.cask.cdap.etl.mock.batch.NodeStatesAction;
import co.cask.cdap.etl.mock.batch.aggregator.FieldCountAggregator;
import co.cask.cdap.etl.mock.batch.aggregator.IdentityAggregator;
import co.cask.cdap.etl.mock.batch.joiner.Join;
import co.cask.cdap.etl.mock.test.HydratorTestBase;
import co.cask.cdap.etl.mock.transform.FieldsPrefixTransform;
import co.cask.cdap.etl.mock.transform.IdentityTransform;
import co.cask.cdap.etl.mock.transform.StringValueFilterTransform;
import co.cask.cdap.etl.proto.Engine;
import co.cask.cdap.etl.proto.v2.ETLBatchConfig;
import co.cask.cdap.etl.proto.v2.ETLPlugin;
import co.cask.cdap.etl.proto.v2.ETLStage;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.artifact.AppRequest;
import co.cask.cdap.proto.artifact.ArtifactSummary;
import co.cask.cdap.proto.id.ArtifactId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.test.ApplicationManager;
import co.cask.cdap.test.DataSetManager;
import co.cask.cdap.test.StreamManager;
import co.cask.cdap.test.TestConfiguration;
import co.cask.cdap.test.WorkflowManager;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 *
 */
public class DataPipelineTest extends HydratorTestBase {

  protected static final ArtifactId APP_ARTIFACT_ID =
    new ArtifactId(NamespaceId.DEFAULT.getNamespace(), "app", "1.0.0");
  protected static final ArtifactSummary APP_ARTIFACT = new ArtifactSummary("app", "1.0.0");
  private static int startCount = 0;
  @ClassRule
  public static final TestConfiguration CONFIG = new TestConfiguration(Constants.Explore.EXPLORE_ENABLED, false);

  @BeforeClass
  public static void setupTest() throws Exception {
    if (startCount++ > 0) {
      return;
    }
    setupBatchArtifacts(APP_ARTIFACT_ID, DataPipelineApp.class);

    // add some test plugins
    addPluginArtifact(new ArtifactId(NamespaceId.DEFAULT.getNamespace(), "spark-plugins", "1.0.0"),
                      APP_ARTIFACT_ID,
                      NaiveBayesTrainer.class, NaiveBayesClassifier.class);
  }

  @After
  public void cleanupTest() throws Exception {
    getMetricsManager().resetAll();
  }

  @Test
  public void testPipelineWithActions() throws Exception {
    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(new ETLStage("action1", MockAction.getPlugin("mytable1")))
      .addStage(new ETLStage("action2", MockAction.getPlugin("mytable2")))
      .addStage(new ETLStage("action3", MockAction.getPlugin("mytable3")))
      .addStage(new ETLStage("source", MockSource.getPlugin("singleInput")))
      .addStage(new ETLStage("sink", MockSink.getPlugin("singleOutput")))
      .addConnection("source", "sink")
      .addConnection("action1", "action2")
      .addConnection("action2", "source")
      .addConnection("sink", "action3")
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(APP_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "MyApp");
    ApplicationManager appManager = deployApplication(appId, appRequest);


    Schema schema = Schema.recordOf(
      "testRecord",
      Schema.Field.of("name", Schema.of(Schema.Type.STRING))
    );
    StructuredRecord recordSamuel = StructuredRecord.builder(schema).set("name", "samuel").build();
    StructuredRecord recordBob = StructuredRecord.builder(schema).set("name", "bob").build();

    // write records to source
    DataSetManager<Table> inputManager = getDataset(Id.Namespace.DEFAULT, "singleInput");
    MockSource.writeInput(inputManager, ImmutableList.of(recordSamuel, recordBob));

    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForFinish(5, TimeUnit.MINUTES);

    // check sink
    DataSetManager<Table> sinkManager = getDataset("singleOutput");
    Set<StructuredRecord> expected = ImmutableSet.of(recordSamuel, recordBob);
    Set<StructuredRecord> actual = Sets.newHashSet(MockSink.readOutput(sinkManager));
    Assert.assertEquals(expected, actual);

    DataSetManager<Table> actionTable = getDataset("mytable1");
    Assert.assertEquals("value1", MockAction.readOutput(actionTable));
    actionTable = getDataset("mytable2");
    Assert.assertEquals("value1", MockAction.readOutput(actionTable));
    actionTable = getDataset("mytable3");
    Assert.assertEquals("value1", MockAction.readOutput(actionTable));

    validateMetric(2, appId, "source.records.out");
    validateMetric(2, appId, "sink.records.in");
  }

  @Test
  public void testInnerJoin() throws Exception {
    Schema inputSchema1 = Schema.recordOf(
      "customerRecord",
      Schema.Field.of("customer_id", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("customer_name", Schema.of(Schema.Type.STRING))
    );

    Schema inputSchema2 = Schema.recordOf(
      "itemRecord",
      Schema.Field.of("item_id", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("item_price", Schema.of(Schema.Type.LONG)),
      Schema.Field.of("cust_id", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("cust_name", Schema.of(Schema.Type.STRING))
    );

    Schema inputSchema3 = Schema.recordOf(
      "transactionRecord",
      Schema.Field.of("t_id", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("c_id", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("c_name", Schema.of(Schema.Type.STRING))
    );

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(new ETLStage("source1", MockSource.getPlugin("source1Input")))
      .addStage(new ETLStage("source2", MockSource.getPlugin("source2Input")))
      .addStage(new ETLStage("source3", MockSource.getPlugin("source3Input")))
      .addStage(new ETLStage("t1", FieldsPrefixTransform.getPlugin("", inputSchema1.toString())))
      .addStage(new ETLStage("t2", FieldsPrefixTransform.getPlugin("", inputSchema2.toString())))
      .addStage(new ETLStage("t3", FieldsPrefixTransform.getPlugin("", inputSchema3.toString())))
      .addStage(new ETLStage("testJoiner", Join.getPlugin("t1.customer_id=t2.cust_id=t3.c_id," +
                                                            "t1.customer_name=t2.cust_name=t3.c_name",
                                                          "t1,t2,t3", "", "")))
      .addStage(new ETLStage("sink1", MockSink.getPlugin("joinerOutput")))
      .addConnection("source1", "t1")
      .addConnection("source2", "t2")
      .addConnection("source3", "t3")
      .addConnection("t1", "testJoiner")
      .addConnection("t2", "testJoiner")
      .addConnection("t3", "testJoiner")
      .addConnection("testJoiner", "sink1")
      .setEngine(Engine.MAPREDUCE)
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(APP_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "JoinerApp");
    ApplicationManager appManager = deployApplication(appId, appRequest);

    Schema outSchema = Schema.recordOf(
      "join.output",
      Schema.Field.of("customer_id", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("customer_name", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("item_id", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("item_price", Schema.of(Schema.Type.LONG)),
      Schema.Field.of("cust_id", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("cust_name", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("t_id", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("c_id", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("c_name", Schema.of(Schema.Type.STRING))
    );

    StructuredRecord recordSamuel = StructuredRecord.builder(inputSchema1).set("customer_id", "1")
      .set("customer_name", "samuel").build();
    StructuredRecord recordBob = StructuredRecord.builder(inputSchema1).set("customer_id", "2")
      .set("customer_name", "bob").build();
    StructuredRecord recordJane = StructuredRecord.builder(inputSchema1).set("customer_id", "3")
      .set("customer_name", "jane").build();

    StructuredRecord recordCar = StructuredRecord.builder(inputSchema2).set("item_id", "11").set("item_price", 10000L)
      .set("cust_id", "1").set("cust_name", "samuel").build();
    StructuredRecord recordBike = StructuredRecord.builder(inputSchema2).set("item_id", "22").set("item_price", 100L)
      .set("cust_id", "3").set("cust_name", "jane").build();

    StructuredRecord recordTrasCar = StructuredRecord.builder(inputSchema3).set("t_id", "1").set("c_id", "1")
      .set("c_name", "samuel").build();
    StructuredRecord recordTrasBike = StructuredRecord.builder(inputSchema3).set("t_id", "2").set("c_id", "3")
      .set("c_name", "jane").build();

    // write one record to each source
    DataSetManager<Table> inputManager = getDataset(Id.Namespace.DEFAULT, "source1Input");
    MockSource.writeInput(inputManager, ImmutableList.of(recordSamuel, recordBob, recordJane));
    inputManager = getDataset(Id.Namespace.DEFAULT, "source2Input");
    MockSource.writeInput(inputManager, ImmutableList.of(recordCar, recordBike));
    inputManager = getDataset(Id.Namespace.DEFAULT, "source3Input");
    MockSource.writeInput(inputManager, ImmutableList.of(recordTrasCar, recordTrasBike));

    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForFinish(5, TimeUnit.MINUTES);


    StructuredRecord joinRecordSamuel = StructuredRecord.builder(outSchema)
    .set("customer_id", "1").set("customer_name", "samuel")
    .set("item_id", "11").set("item_price", 10000L).set("cust_id", "1").set("cust_name", "samuel")
    .set("t_id", "1").set("c_id", "1").set("c_name", "samuel").build();

    StructuredRecord joinRecordJane = StructuredRecord.builder(outSchema)
      .set("customer_id", "3").set("customer_name", "jane")
      .set("item_id", "22").set("item_price", 100L).set("cust_id", "3").set("cust_name", "jane")
      .set("t_id", "2").set("c_id", "3").set("c_name", "jane").build();

    DataSetManager<Table> sinkManager = getDataset("joinerOutput");
    Set<StructuredRecord> expected = ImmutableSet.of(joinRecordSamuel, joinRecordJane);
    Set<StructuredRecord> actual = Sets.newHashSet(MockSink.readOutput(sinkManager));
    Assert.assertEquals(expected, actual);

    validateMetric(2, appId, "testJoiner.records.out");
    validateMetric(2, appId, "sink1.records.in");
  }

  @Test
  public void testSinglePhase() throws Exception {
    /*
     * source --> sink
     */
    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(new ETLStage("source", MockSource.getPlugin("singleInput")))
      .addStage(new ETLStage("sink", MockSink.getPlugin("singleOutput")))
      .addConnection("source", "sink")
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(APP_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "SinglePhaseApp");
    ApplicationManager appManager = deployApplication(appId, appRequest);

    Schema schema = Schema.recordOf(
      "testRecord",
      Schema.Field.of("name", Schema.of(Schema.Type.STRING))
    );
    StructuredRecord recordSamuel = StructuredRecord.builder(schema).set("name", "samuel").build();
    StructuredRecord recordBob = StructuredRecord.builder(schema).set("name", "bob").build();

    // write records to source
    DataSetManager<Table> inputManager = getDataset(Id.Namespace.DEFAULT, "singleInput");
    MockSource.writeInput(inputManager, ImmutableList.of(recordSamuel, recordBob));

    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForFinish(5, TimeUnit.MINUTES);

    // check sink
    DataSetManager<Table> sinkManager = getDataset("singleOutput");
    Set<StructuredRecord> expected = ImmutableSet.of(recordSamuel, recordBob);
    Set<StructuredRecord> actual = Sets.newHashSet(MockSink.readOutput(sinkManager));
    Assert.assertEquals(expected, actual);

    validateMetric(2, appId, "source.records.out");
    validateMetric(2, appId, "sink.records.in");
  }

  @Test
  public void testMapRedSimpleMultipleSource() throws Exception {
    testSimpleMultiSource(Engine.MAPREDUCE);
  }

  @Test
  public void testSparkSimpleMultipleSource() throws Exception {
    testSimpleMultiSource(Engine.SPARK);
  }

  private void testSimpleMultiSource(Engine engine) throws Exception {
    /*
     * source1 --|
     *           |--> sink
     * source2 --|
     */
    String source1Name = String.format("simpleMSInput1-%s", engine);
    String source2Name = String.format("simpleMSInput2-%s", engine);
    String sinkName = String.format("simpleMSOutput-%s", engine);
    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(new ETLStage("source1", MockSource.getPlugin(source1Name)))
      .addStage(new ETLStage("source2", MockSource.getPlugin(source2Name)))
      .addStage(new ETLStage("sink", MockSink.getPlugin(sinkName)))
      .addConnection("source1", "sink")
      .addConnection("source2", "sink")
      .setEngine(engine)
      .build();


    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(APP_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "SimpleMultiSourceApp");
    ApplicationManager appManager = deployApplication(appId, appRequest);

    // there should be only two programs - one workflow and one mapreduce/spark
    Assert.assertEquals(2, appManager.getInfo().getPrograms().size());
    Schema schema = Schema.recordOf(
      "testRecord",
      Schema.Field.of("name", Schema.of(Schema.Type.STRING))
    );
    StructuredRecord recordSamuel = StructuredRecord.builder(schema).set("name", "samuel").build();
    StructuredRecord recordBob = StructuredRecord.builder(schema).set("name", "bob").build();

    // write one record to each source
    DataSetManager<Table> inputManager = getDataset(Id.Namespace.DEFAULT, source1Name);
    MockSource.writeInput(inputManager, ImmutableList.of(recordSamuel));
    inputManager = getDataset(Id.Namespace.DEFAULT, source2Name);
    MockSource.writeInput(inputManager, ImmutableList.of(recordBob));

    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForFinish(5, TimeUnit.MINUTES);

    // check sink
    DataSetManager<Table> sinkManager = getDataset(sinkName);
    Set<StructuredRecord> expected = ImmutableSet.of(recordSamuel, recordBob);
    Set<StructuredRecord> actual = Sets.newHashSet(MockSink.readOutput(sinkManager));
    Assert.assertEquals(expected, actual);

    validateMetric(1, appId, "source1.records.out");
    validateMetric(1, appId, "source2.records.out");
    validateMetric(2, appId, "sink.records.in");
  }

  @Test
  public void testMapRedMultiSource() throws Exception {
    testMultiSource(Engine.MAPREDUCE);
  }

  @Test
  public void testSparkMultiSource() throws Exception {
    testMultiSource(Engine.SPARK);
  }

  private void testMultiSource(Engine engine) throws Exception {
    /*
     * source1 --|                 |--> sink1
     *           |--> transform1 --|
     * source2 --|                 |
     *                             |--> transform2 --> sink2
     *                                     ^
     *                                     |
     * source3 ----------------------------|
     */
    String source1Name = String.format("msInput1-%s", engine);
    String source2Name = String.format("msInput2-%s", engine);
    String source3Name = String.format("msInput3-%s", engine);
    String sink1Name = String.format("msOutput1-%s", engine);
    String sink2Name = String.format("msOutput2-%s", engine);
    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(new ETLStage("source1", MockSource.getPlugin(source1Name)))
      .addStage(new ETLStage("source2", MockSource.getPlugin(source2Name)))
      .addStage(new ETLStage("source3", MockSource.getPlugin(source3Name)))
      .addStage(new ETLStage("transform1", IdentityTransform.getPlugin()))
      .addStage(new ETLStage("transform2", IdentityTransform.getPlugin()))
      .addStage(new ETLStage("sink1", MockSink.getPlugin(sink1Name)))
      .addStage(new ETLStage("sink2", MockSink.getPlugin(sink2Name)))
      .addConnection("source1", "transform1")
      .addConnection("source2", "transform1")
      .addConnection("transform1", "sink1")
      .addConnection("transform1", "transform2")
      .addConnection("transform2", "sink2")
      .addConnection("source3", "transform2")
      .setEngine(engine)
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(APP_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "MultiSourceApp");
    ApplicationManager appManager = deployApplication(appId, appRequest);

    // there should be only two programs - one workflow and one mapreduce/spark
    Assert.assertEquals(2, appManager.getInfo().getPrograms().size());
    Schema schema = Schema.recordOf(
      "testRecord",
      Schema.Field.of("name", Schema.of(Schema.Type.STRING))
    );

    StructuredRecord recordSamuel = StructuredRecord.builder(schema).set("name", "samuel").build();
    StructuredRecord recordBob = StructuredRecord.builder(schema).set("name", "bob").build();
    StructuredRecord recordJane = StructuredRecord.builder(schema).set("name", "jane").build();

    // write one record to each source
    DataSetManager<Table> inputManager = getDataset(Id.Namespace.DEFAULT, source1Name);
    MockSource.writeInput(inputManager, ImmutableList.of(recordSamuel));
    inputManager = getDataset(Id.Namespace.DEFAULT, source2Name);
    MockSource.writeInput(inputManager, ImmutableList.of(recordBob));
    inputManager = getDataset(Id.Namespace.DEFAULT, source3Name);
    MockSource.writeInput(inputManager, ImmutableList.of(recordJane));

    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForFinish(5, TimeUnit.MINUTES);

    // sink1 should get records from source1 and source2
    DataSetManager<Table> sinkManager = getDataset(sink1Name);
    Set<StructuredRecord> expected = ImmutableSet.of(recordSamuel, recordBob);
    Set<StructuredRecord> actual = Sets.newHashSet(MockSink.readOutput(sinkManager));
    Assert.assertEquals(expected, actual);

    // sink2 should get all records
    sinkManager = getDataset(sink2Name);
    expected = ImmutableSet.of(recordSamuel, recordBob, recordJane);
    actual = Sets.newHashSet(MockSink.readOutput(sinkManager));
    Assert.assertEquals(expected, actual);

    validateMetric(1, appId, "source1.records.out");
    validateMetric(1, appId, "source2.records.out");
    validateMetric(1, appId, "source3.records.out");
    validateMetric(2, appId, "transform1.records.in");
    validateMetric(2, appId, "transform1.records.out");
    validateMetric(3, appId, "transform2.records.in");
    validateMetric(3, appId, "transform2.records.out");
    validateMetric(2, appId, "sink1.records.in");
    validateMetric(3, appId, "sink2.records.in");
  }

  @Test
  public void testMapRedSequentialAggregators() throws Exception {
    testSequentialAggregators(Engine.MAPREDUCE);
  }

  @Test
  public void testSparkSequentialAggregators() throws Exception {
    testSequentialAggregators(Engine.SPARK);
  }

  @Test
  public void testMapRedParallelAggregators() throws Exception {
    testParallelAggregators(Engine.MAPREDUCE);
  }

  @Test
  public void testSparkParallelAggregators() throws Exception {
    testParallelAggregators(Engine.SPARK);
  }

  private void testSequentialAggregators(Engine engine) throws Exception {
    String sourceName = "linearAggInput-" + engine.name();
    String sinkName = "linearAggOutput-" + engine.name();
    /*
     * source --> filter1 --> aggregator1 --> aggregator2 --> filter2 --> sink
     */
    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .setEngine(engine)
      .addStage(new ETLStage("source", MockSource.getPlugin(sourceName)))
      .addStage(new ETLStage("sink", MockSink.getPlugin(sinkName)))
      .addStage(new ETLStage("filter1", StringValueFilterTransform.getPlugin("name", "bob")))
      .addStage(new ETLStage("filter2", StringValueFilterTransform.getPlugin("name", "jane")))
      .addStage(new ETLStage("aggregator1", IdentityAggregator.getPlugin()))
      .addStage(new ETLStage("aggregator2", IdentityAggregator.getPlugin()))
      .addConnection("source", "filter1")
      .addConnection("filter1", "aggregator1")
      .addConnection("aggregator1", "aggregator2")
      .addConnection("aggregator2", "filter2")
      .addConnection("filter2", "sink")
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(APP_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "LinearAggApp");
    ApplicationManager appManager = deployApplication(appId, appRequest);

    Schema schema = Schema.recordOf(
      "testRecord",
      Schema.Field.of("name", Schema.of(Schema.Type.STRING))
    );

    StructuredRecord recordSamuel = StructuredRecord.builder(schema).set("name", "samuel").build();
    StructuredRecord recordBob = StructuredRecord.builder(schema).set("name", "bob").build();
    StructuredRecord recordJane = StructuredRecord.builder(schema).set("name", "jane").build();

    // write one record to each source
    DataSetManager<Table> inputManager = getDataset(Id.Namespace.DEFAULT, sourceName);
    MockSource.writeInput(inputManager, ImmutableList.of(recordSamuel, recordBob, recordJane));

    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForFinish(5, TimeUnit.MINUTES);

    // check output
    DataSetManager<Table> sinkManager = getDataset(sinkName);
    Set<StructuredRecord> expected = ImmutableSet.of(recordSamuel);
    Set<StructuredRecord> actual = Sets.newHashSet(MockSink.readOutput(sinkManager));
    Assert.assertEquals(expected, actual);

    validateMetric(3, appId, "source.records.out");
    validateMetric(3, appId, "filter1.records.in");
    validateMetric(2, appId, "filter1.records.out");
    validateMetric(2, appId, "aggregator1.records.in");
    validateMetric(2, appId, "aggregator1.records.out");
    validateMetric(2, appId, "aggregator2.records.in");
    validateMetric(2, appId, "aggregator2.records.out");
    validateMetric(2, appId, "filter2.records.in");
    validateMetric(1, appId, "filter2.records.out");
    validateMetric(1, appId, "sink.records.out");
  }

  private void testParallelAggregators(Engine engine) throws Exception {
    String source1Name = "pAggInput1-" + engine.name();
    String source2Name = "pAggInput2-" + engine.name();
    String sink1Name = "pAggOutput1-" + engine.name();
    String sink2Name = "pAggOutput2-" + engine.name();
    /*
       source1 --|--> agg1 --> sink1
                 |
       source1 --|--> agg2 --> sink2
     */
    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .setEngine(engine)
      .addStage(new ETLStage("source1", MockSource.getPlugin(source1Name)))
      .addStage(new ETLStage("source2", MockSource.getPlugin(source2Name)))
      .addStage(new ETLStage("sink1", MockSink.getPlugin(sink1Name)))
      .addStage(new ETLStage("sink2", MockSink.getPlugin(sink2Name)))
      .addStage(new ETLStage("agg1", FieldCountAggregator.getPlugin("user", "string")))
      .addStage(new ETLStage("agg2", FieldCountAggregator.getPlugin("item", "long")))
      .addConnection("source1", "agg1")
      .addConnection("source1", "agg2")
      .addConnection("source2", "agg1")
      .addConnection("source2", "agg2")
      .addConnection("agg1", "sink1")
      .addConnection("agg2", "sink2")
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(APP_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "ParallelAggApp");
    ApplicationManager appManager = deployApplication(appId, appRequest);
    // total programs = 4; 1 workflow, 1 job for source1,source2 -> agg1.connector,agg2.connector
    // 1 job for agg1.connector -> sink1 and another job for agg2.connector -> sink2
    Assert.assertEquals(4, appManager.getInfo().getPrograms().size());
    Schema inputSchema = Schema.recordOf(
      "testRecord",
      Schema.Field.of("user", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("item", Schema.of(Schema.Type.LONG))
    );

    // write few records to each source
    DataSetManager<Table> inputManager = getDataset(Id.Namespace.DEFAULT, source1Name);
    MockSource.writeInput(inputManager, ImmutableList.of(
      StructuredRecord.builder(inputSchema).set("user", "samuel").set("item", 1L).build(),
      StructuredRecord.builder(inputSchema).set("user", "samuel").set("item", 2L).build()));

    inputManager = getDataset(Id.Namespace.DEFAULT, source2Name);
    MockSource.writeInput(inputManager, ImmutableList.of(
      StructuredRecord.builder(inputSchema).set("user", "samuel").set("item", 3L).build(),
      StructuredRecord.builder(inputSchema).set("user", "john").set("item", 4L).build(),
      StructuredRecord.builder(inputSchema).set("user", "john").set("item", 3L).build()));

    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForFinish(5, TimeUnit.MINUTES);

    Schema outputSchema1 = Schema.recordOf(
      "user.count",
      Schema.Field.of("user", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("ct", Schema.of(Schema.Type.LONG))
    );
    Schema outputSchema2 = Schema.recordOf(
      "item.count",
      Schema.Field.of("item", Schema.of(Schema.Type.LONG)),
      Schema.Field.of("ct", Schema.of(Schema.Type.LONG))
    );

    // check output
    DataSetManager<Table> sinkManager = getDataset(sink1Name);
    Set<StructuredRecord> expected = ImmutableSet.of(
      StructuredRecord.builder(outputSchema1).set("user", "all").set("ct", 5L).build(),
      StructuredRecord.builder(outputSchema1).set("user", "samuel").set("ct", 3L).build(),
      StructuredRecord.builder(outputSchema1).set("user", "john").set("ct", 2L).build());
    Set<StructuredRecord> actual = Sets.newHashSet(MockSink.readOutput(sinkManager));
    Assert.assertEquals(expected, actual);

    sinkManager = getDataset(sink2Name);
    expected = ImmutableSet.of(
      StructuredRecord.builder(outputSchema2).set("item", 0L).set("ct", 5L).build(),
      StructuredRecord.builder(outputSchema2).set("item", 1L).set("ct", 1L).build(),
      StructuredRecord.builder(outputSchema2).set("item", 2L).set("ct", 1L).build(),
      StructuredRecord.builder(outputSchema2).set("item", 3L).set("ct", 2L).build(),
      StructuredRecord.builder(outputSchema2).set("item", 4L).set("ct", 1L).build());
    actual = Sets.newHashSet(MockSink.readOutput(sinkManager));
    Assert.assertEquals(expected, actual);

    validateMetric(2, appId, "source1.records.out");
    validateMetric(3, appId, "source2.records.out");
    validateMetric(5, appId, "agg1.records.in");
    // 2 users, but FieldCountAggregator always emits an 'all' group
    validateMetric(3, appId, "agg1.aggregator.groups");
    validateMetric(3, appId, "agg1.records.out");
    validateMetric(5, appId, "agg2.records.in");
    // 4 items, but FieldCountAggregator always emits an 'all' group
    validateMetric(5, appId, "agg2.aggregator.groups");
    validateMetric(5, appId, "agg2.records.out");
    validateMetric(3, appId, "sink1.records.in");
    validateMetric(5, appId, "sink2.records.in");
  }

  @Test
  public void testSparkSinkAndCompute() throws Exception {
    // use the SparkSink to train a model
    testSinglePhaseWithSparkSink();
    // use a SparkCompute to classify all records going through the pipeline, using the model build with the SparkSink
    testSinglePhaseWithSparkCompute();
  }

  @Test
  public void testPostAction() throws Exception {
    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(new ETLStage("source", MockSource.getPlugin("actionInput")))
      .addStage(new ETLStage("sink", MockSink.getPlugin("actionOutput")))
      .addPostAction(new ETLStage("tokenWriter", NodeStatesAction.getPlugin("tokenTable")))
      .addConnection("source", "sink")
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(APP_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "ActionApp");
    ApplicationManager appManager = deployApplication(appId, appRequest);

    Schema schema = Schema.recordOf(
      "testRecord",
      Schema.Field.of("name", Schema.of(Schema.Type.STRING))
    );

    StructuredRecord recordSamuel = StructuredRecord.builder(schema).set("name", "samuel").build();
    StructuredRecord recordBob = StructuredRecord.builder(schema).set("name", "bob").build();
    StructuredRecord recordJane = StructuredRecord.builder(schema).set("name", "jane").build();

    DataSetManager<Table> inputManager = getDataset(Id.Namespace.DEFAULT, "actionInput");
    MockSource.writeInput(inputManager, ImmutableList.of(recordSamuel, recordBob, recordJane));

    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForFinish(5, TimeUnit.MINUTES);

    DataSetManager<Table> tokenTableManager = getDataset(Id.Namespace.DEFAULT, "tokenTable");
    Table tokenTable = tokenTableManager.get();
    NodeStatus status = NodeStatus.valueOf(Bytes.toString(
      tokenTable.get(Bytes.toBytes("phase-1"), Bytes.toBytes("status"))));
    Assert.assertEquals(NodeStatus.COMPLETED, status);
  }

  private void testSinglePhaseWithSparkSink() throws Exception {
    /*
     * source1 ---|
     *            |--> sparksink
     * source2 ---|
     */
    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(new ETLStage("source1", MockSource.getPlugin("messages1")))
      .addStage(new ETLStage("source2", MockSource.getPlugin("messages2")))
      .addStage(new ETLStage("customsink",
                             new ETLPlugin(NaiveBayesTrainer.PLUGIN_NAME, SparkSink.PLUGIN_TYPE,
                                           ImmutableMap.of("fileSetName", "modelFileSet",
                                                           "path", "output",
                                                           "fieldToClassify", SpamMessage.TEXT_FIELD,
                                                           "predictionField", SpamMessage.SPAM_PREDICTION_FIELD),
                                           null)))
      .addConnection("source1", "customsink")
      .addConnection("source2", "customsink")
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(APP_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "SparkSinkApp");
    ApplicationManager appManager = deployApplication(appId, appRequest);


    // set up five spam messages and five non-spam messages to be used for classification
    List<StructuredRecord> messagesToWrite = new ArrayList<>();
    messagesToWrite.add(new SpamMessage("buy our clothes", 1.0).toStructuredRecord());
    messagesToWrite.add(new SpamMessage("sell your used books to us", 1.0).toStructuredRecord());
    messagesToWrite.add(new SpamMessage("earn money for free", 1.0).toStructuredRecord());
    messagesToWrite.add(new SpamMessage("this is definitely not spam", 1.0).toStructuredRecord());
    messagesToWrite.add(new SpamMessage("you won the lottery", 1.0).toStructuredRecord());

    // write records to source1
    DataSetManager<Table> inputManager = getDataset(Id.Namespace.DEFAULT, "messages1");
    MockSource.writeInput(inputManager, messagesToWrite);

    messagesToWrite.clear();
    messagesToWrite.add(new SpamMessage("how was your day", 0.0).toStructuredRecord());
    messagesToWrite.add(new SpamMessage("what are you up to", 0.0).toStructuredRecord());
    messagesToWrite.add(new SpamMessage("this is a genuine message", 0.0).toStructuredRecord());
    messagesToWrite.add(new SpamMessage("this is an even more genuine message", 0.0).toStructuredRecord());
    messagesToWrite.add(new SpamMessage("could you send me the report", 0.0).toStructuredRecord());

    // write records to source2
    inputManager = getDataset(Id.Namespace.DEFAULT, "messages2");
    MockSource.writeInput(inputManager, messagesToWrite);

    // ingest in some messages to be classified
    StreamManager textsToClassify = getStreamManager(NaiveBayesTrainer.TEXTS_TO_CLASSIFY);
    textsToClassify.send("how are you doing today");
    textsToClassify.send("free money money");
    textsToClassify.send("what are you doing today");
    textsToClassify.send("genuine report");

    // manually trigger the pipeline
    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForFinish(5, TimeUnit.MINUTES);


    DataSetManager<KeyValueTable> classifiedTexts = getDataset(NaiveBayesTrainer.CLASSIFIED_TEXTS);

    Assert.assertEquals(0.0d, Bytes.toDouble(classifiedTexts.get().read("how are you doing today")), 0.01d);
    // only 'free money money' should be predicated as spam
    Assert.assertEquals(1.0d, Bytes.toDouble(classifiedTexts.get().read("free money money")), 0.01d);
    Assert.assertEquals(0.0d, Bytes.toDouble(classifiedTexts.get().read("what are you doing today")), 0.01d);
    Assert.assertEquals(0.0d, Bytes.toDouble(classifiedTexts.get().read("genuine report")), 0.01d);

    validateMetric(5, appId, "source1.records.out");
    validateMetric(5, appId, "source2.records.out");
    validateMetric(10, appId, "customsink.records.in");
  }

  private void testSinglePhaseWithSparkCompute() throws Exception {
    /*
     * source --> sparkcompute --> sink
     */
    String classifiedTextsTable = "classifiedTextTable";

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(new ETLStage("source", MockSource.getPlugin(NaiveBayesTrainer.TEXTS_TO_CLASSIFY)))
      .addStage(new ETLStage("sparkcompute",
                             new ETLPlugin(NaiveBayesClassifier.PLUGIN_NAME, SparkCompute.PLUGIN_TYPE,
                                           ImmutableMap.of("fileSetName", "modelFileSet"  ,
                                                           "path", "output",
                                                           "fieldToClassify", SpamMessage.TEXT_FIELD,
                                                           "fieldToSet", SpamMessage.SPAM_PREDICTION_FIELD),
                                           null)))
      .addStage(new ETLStage("sink", MockSink.getPlugin(classifiedTextsTable)))
      .addConnection("source", "sparkcompute")
      .addConnection("sparkcompute", "sink")
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(APP_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "SparkComputeApp");
    ApplicationManager appManager = deployApplication(appId, appRequest);


    // write some some messages to be classified
    List<StructuredRecord> messagesToWrite = new ArrayList<>();
    messagesToWrite.add(new SpamMessage("how are you doing today").toStructuredRecord());
    messagesToWrite.add(new SpamMessage("free money money").toStructuredRecord());
    messagesToWrite.add(new SpamMessage("what are you doing today").toStructuredRecord());
    messagesToWrite.add(new SpamMessage("genuine report").toStructuredRecord());

    DataSetManager<Table> inputManager = getDataset(Id.Namespace.DEFAULT, NaiveBayesTrainer.TEXTS_TO_CLASSIFY);
    MockSource.writeInput(inputManager, messagesToWrite);

    // manually trigger the pipeline
    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForFinish(5, TimeUnit.MINUTES);


    DataSetManager<Table> classifiedTexts = getDataset(classifiedTextsTable);
    List<StructuredRecord> structuredRecords = MockSink.readOutput(classifiedTexts);


    Set<SpamMessage> results = new HashSet<>();
    for (StructuredRecord structuredRecord : structuredRecords) {
      results.add(SpamMessage.fromStructuredRecord(structuredRecord));
    }

    Set<SpamMessage> expected = new HashSet<>();
    expected.add(new SpamMessage("how are you doing today", 0.0));
    // only 'free money money' should be predicated as spam
    expected.add(new SpamMessage("free money money", 1.0));
    expected.add(new SpamMessage("what are you doing today", 0.0));
    expected.add(new SpamMessage("genuine report", 0.0));

    Assert.assertEquals(expected, results);

    validateMetric(4, appId, "source.records.out");
    validateMetric(4, appId, "sparkcompute.records.in");
    validateMetric(4, appId, "sink.records.in");
  }

  private void validateMetric(long expected, Id.Application appId,
                              String metric) throws TimeoutException, InterruptedException {
    Map<String, String> tags = ImmutableMap.of(Constants.Metrics.Tag.NAMESPACE, appId.getNamespaceId(),
                                               Constants.Metrics.Tag.APP, appId.getId(),
                                               Constants.Metrics.Tag.WORKFLOW, SmartWorkflow.NAME);
    getMetricsManager().waitForTotalMetricCount(tags, "user." + metric, expected, 20, TimeUnit.SECONDS);
    // wait for won't throw an exception if the metric count is greater than expected
    Assert.assertEquals(expected, getMetricsManager().getTotalMetric(tags, "user." + metric));
  }
}
