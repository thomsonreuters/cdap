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

package co.cask.cdap.spark.app

import co.cask.cdap.api.app.AbstractApplication
import co.cask.cdap.api.data.stream.Stream
import co.cask.cdap.api.dataset.lib.{FileSet, FileSetProperties, KeyValueTable, ObjectMappedTable, ObjectMappedTableProperties, TimeseriesTable}
import co.cask.cdap.api.spark.AbstractSpark
import co.cask.cdap.api.workflow.{AbstractWorkflow, AbstractWorkflowAction}
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat

import scala.collection.JavaConversions._

/**
  * An application for SparkTestRun.
  */
class TestSparkApp extends AbstractApplication {

  override def configure() = {
    addStream(new Stream("SparkStream"))
    addStream(new Stream("PeopleStream"))
    createDataset("KeyValueTable", classOf[KeyValueTable])
    createDataset("SparkResult", classOf[KeyValueTable])
    createDataset("SparkThresholdResult", classOf[KeyValueTable])
    createDataset("PeopleFileSet", classOf[FileSet], FileSetProperties.builder
        .setOutputFormat(classOf[TextOutputFormat[_, _]])
        .setOutputProperty(TextOutputFormat.SEPERATOR, ":")
        .build)
    createDataset("TimeSeriesResult", classOf[TimeseriesTable])

    createDataset("PersonTable", classOf[ObjectMappedTable[Person]],
                  ObjectMappedTableProperties.builder().setType(classOf[Person]).build())
    addSpark(new DatasetSQLSpark)

    addSpark(new ClassicSpark)
    addSpark(new ScalaClassicSpark)
    addSpark(new TransactionSpark)
    addSpark(new StreamFormatSpecSpark)
    addSpark(new ScalaStreamFormatSpecSpark)

    addSpark(new KafkaSparkStreaming)

    addSpark(new ForkSpark("ForkSpark1"))
    addSpark(new ForkSpark("ForkSpark2"))
    addWorkflow(new ForkSparkWorkflow)
  }

  final class ClassicSpark extends AbstractSpark {
    override protected def configure {
      setMainClass(classOf[ClassicSparkProgram])
    }
  }

  final class ScalaClassicSpark extends AbstractSpark {
    override protected def configure {
      setMainClassName("co.cask.cdap.spark.app.ScalaClassicSparkProgram")
    }
  }

  final class ForkSparkWorkflow extends AbstractWorkflow {
    override protected def configure(): Unit = {
      fork()
        .addSpark("ForkSpark1")
      .also()
        .addSpark("ForkSpark2")
      .join()
      addAction(new VerifyAction)
    }
  }

  final class VerifyAction extends AbstractWorkflowAction {
    override def run() = {
      val values = getContext.getToken.getAll("sum")
      require(values.map(_.getValue.getAsInt).distinct.size == 2,
              "Expect number of distinct 'sum' token be 2: " + values)
    }
  }
}
