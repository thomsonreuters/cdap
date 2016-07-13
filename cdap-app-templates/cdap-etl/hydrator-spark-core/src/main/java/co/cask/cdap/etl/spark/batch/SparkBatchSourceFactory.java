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

package co.cask.cdap.etl.spark.batch;

import co.cask.cdap.api.data.batch.Input;
import co.cask.cdap.api.data.batch.InputFormatProvider;
import co.cask.cdap.api.data.format.FormatSpecification;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.stream.StreamBatchReadable;
import co.cask.cdap.api.spark.JavaSparkExecutionContext;
import co.cask.cdap.api.stream.StreamEventDecoder;
import com.google.common.base.Objects;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import scala.Tuple2;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static java.lang.Thread.currentThread;

/**
 * A POJO class for storing source information being set from {@link SparkBatchSourceContext} and used in
 * {@link BatchSparkPipelineDriver}.
 */
final class SparkBatchSourceFactory {

  static SparkBatchSourceFactory deserialize(InputStream inputStream) throws IOException {
    DataInput input = new DataInputStream(inputStream);

    Map<String, StreamBatchReadable> streamBatchReadables = Serializations.deserializeMap(
      input, new Serializations.ObjectReader<StreamBatchReadable>() {
      @Override
      public StreamBatchReadable read(DataInput input) throws IOException {
        return new StreamBatchReadable(URI.create(input.readUTF()));
      }
    });

    Map<String, InputFormatProvider> inputFormatProviders = Serializations.deserializeMap(
      input, new Serializations.ObjectReader<InputFormatProvider>() {
      @Override
      public InputFormatProvider read(DataInput input) throws IOException {
        return new BasicInputFormatProvider(input.readUTF(),
                                            Serializations.deserializeMap(input,
                                                                          Serializations.createStringObjectReader()));
      }
    });

    Map<String, DatasetInfo> datasetInfos = Serializations.deserializeMap(
      input, new Serializations.ObjectReader<DatasetInfo>() {
      @Override
      public DatasetInfo read(DataInput input) throws IOException {
        return DatasetInfo.deserialize(input);
      }
    });

    Map<String, Set<String>> sourceInputs = Serializations.deserializeMap(
      input, Serializations.createStringSetObjectReader());
    return new SparkBatchSourceFactory(streamBatchReadables, inputFormatProviders, datasetInfos, sourceInputs);
  }

  private final Map<String, StreamBatchReadable> streamBatchReadables;
  private final Map<String, InputFormatProvider> inputFormatProviders;
  private final Map<String, DatasetInfo> datasetInfos;
  private final Map<String, Set<String>> sourceInputs;

  SparkBatchSourceFactory() {
    this.streamBatchReadables = new HashMap<>();
    this.inputFormatProviders = new HashMap<>();
    this.datasetInfos = new HashMap<>();
    this.sourceInputs = new HashMap<>();
  }

  private SparkBatchSourceFactory(Map<String, StreamBatchReadable> streamBatchReadables,
                                  Map<String, InputFormatProvider> inputFormatProviders,
                                  Map<String, DatasetInfo> datasetInfos,
                                  Map<String, Set<String>> sourceInputs) {
    this.streamBatchReadables = streamBatchReadables;
    this.inputFormatProviders = inputFormatProviders;
    this.datasetInfos = datasetInfos;
    this.sourceInputs = sourceInputs;
  }

  public void addInput(String stageName, Input input) {
    if (input instanceof Input.DatasetInput) {
      // Note if input format provider is trackable then it comes in as DatasetInput
      Input.DatasetInput datasetInput = (Input.DatasetInput) input;
      addInput(stageName, datasetInput.getName(), datasetInput.getAlias(), datasetInput.getArguments());
    } else if (input instanceof Input.InputFormatProviderInput) {
      Input.InputFormatProviderInput ifpInput = (Input.InputFormatProviderInput) input;
      addInput(stageName, ifpInput.getAlias(),
               new BasicInputFormatProvider(ifpInput.getInputFormatProvider().getInputFormatClassName(),
                                            ifpInput.getInputFormatProvider().getInputFormatConfiguration()));
    } else if (input instanceof Input.StreamInput) {
      Input.StreamInput streamInput = (Input.StreamInput) input;
      addInput(stageName, streamInput.getAlias(), streamInput.getStreamBatchReadable());
    }
  }

  private void addInput(String stageName, String alias, StreamBatchReadable streamBatchReadable) {
    duplicateAliasCheck(alias);
    streamBatchReadables.put(alias, streamBatchReadable);
    addStageInput(stageName, alias);
  }

  private void addInput(String stageName, String datasetName, String alias, Map<String, String> datasetArgs) {
    duplicateAliasCheck(alias);
    datasetInfos.put(alias, new DatasetInfo(datasetName, datasetArgs, null));
    addStageInput(stageName, alias);
  }

  private void addInput(String stageName, String alias, BasicInputFormatProvider inputFormatProvider) {
    duplicateAliasCheck(alias);
    inputFormatProviders.put(alias, inputFormatProvider);
    addStageInput(stageName, alias);
  }

  private void duplicateAliasCheck(String alias) {
    if (inputFormatProviders.containsKey(alias) || datasetInfos.containsKey(alias)
      || streamBatchReadables.containsKey(alias)) {
      // this will never happen since alias will be unique since we append it with UUID
      throw new IllegalStateException(alias + " has already been added. Can't add an input with the same alias.");
    }
  }

  public void serialize(OutputStream outputStream) throws IOException {
    DataOutput output = new DataOutputStream(outputStream);

    Serializations.serializeMap(streamBatchReadables, new Serializations.ObjectWriter<StreamBatchReadable>() {
      @Override
      public void write(StreamBatchReadable streamBatchReadable, DataOutput output) throws IOException {
        output.writeUTF(streamBatchReadable.toURI().toString());
      }
    }, output);

    Serializations.serializeMap(inputFormatProviders, new Serializations.ObjectWriter<InputFormatProvider>() {
      @Override
      public void write(InputFormatProvider inputFormatProvider, DataOutput output) throws IOException {
        output.writeUTF(inputFormatProvider.getInputFormatClassName());
        Serializations.serializeMap(inputFormatProvider.getInputFormatConfiguration(),
                                    Serializations.createStringObjectWriter(), output);
      }
    }, output);

    Serializations.serializeMap(datasetInfos, new Serializations.ObjectWriter<DatasetInfo>() {
      @Override
      public void write(DatasetInfo datasetInfo, DataOutput output) throws IOException {
        datasetInfo.serialize(output);
      }
    }, output);

    Serializations.serializeMap(sourceInputs, Serializations.createStringSetObjectWriter(), output);
  }

  public <K, V> JavaPairRDD<K, V> createRDD(JavaSparkExecutionContext sec, JavaSparkContext jsc, String sourceName,
                                            Class<K> keyClass, Class<V> valueClass) {
    Set<String> inputNames = sourceInputs.get(sourceName);
    if (inputNames == null || inputNames.isEmpty()) {
      // should never happen if validation happened correctly at pipeline configure time
      throw new IllegalArgumentException(
        sourceName + " has no input. Please check that the source calls setInput at some input.");
    }

    JavaPairRDD<K, V> inputRDD = JavaPairRDD.fromJavaRDD(jsc.<Tuple2<K, V>>emptyRDD());
    for (String inputName : inputNames) {
      inputRDD = inputRDD.union(createInputRDD(sec, jsc, inputName, keyClass, valueClass));
    }
    return inputRDD;
  }

  @SuppressWarnings("unchecked")
  private <K, V> JavaPairRDD<K, V> createInputRDD(JavaSparkExecutionContext sec, JavaSparkContext jsc, String inputName,
                                                  Class<K> keyClass, Class<V> valueClass) {
    if (streamBatchReadables.containsKey(inputName)) {
      StreamBatchReadable streamBatchReadable = streamBatchReadables.get(inputName);
      FormatSpecification formatSpec = streamBatchReadable.getFormatSpecification();
      if (formatSpec != null) {
        return (JavaPairRDD<K, V>) sec.fromStream(streamBatchReadable.getStreamName(),
                                                  formatSpec,
                                                  streamBatchReadable.getStartTime(),
                                                  streamBatchReadable.getEndTime(),
                                                  StructuredRecord.class);
      }

      String decoderType = streamBatchReadable.getDecoderType();
      if (decoderType == null) {
        return (JavaPairRDD<K, V>) sec.fromStream(streamBatchReadable.getStreamName(),
                                                  streamBatchReadable.getStartTime(),
                                                  streamBatchReadable.getEndTime(),
                                                  valueClass);
      } else {
        try {
          Class<StreamEventDecoder<K, V>> decoderClass =
            (Class<StreamEventDecoder<K, V>>) Thread.currentThread().getContextClassLoader().loadClass(decoderType);
          return sec.fromStream(streamBatchReadable.getStreamName(),
                                streamBatchReadable.getStartTime(),
                                streamBatchReadable.getEndTime(),
                                decoderClass, keyClass, valueClass);
        } catch (Exception e) {
          throw Throwables.propagate(e);
        }
      }
    }

    if (inputFormatProviders.containsKey(inputName)) {
      InputFormatProvider inputFormatProvider = inputFormatProviders.get(inputName);
      Configuration hConf = new Configuration();
      hConf.clear();
      for (Map.Entry<String, String> entry : inputFormatProvider.getInputFormatConfiguration().entrySet()) {
        hConf.set(entry.getKey(), entry.getValue());
      }
      ClassLoader classLoader = Objects.firstNonNull(currentThread().getContextClassLoader(),
                                                     getClass().getClassLoader());
      try {
        @SuppressWarnings("unchecked")
        Class<InputFormat> inputFormatClass = (Class<InputFormat>) classLoader.loadClass(
          inputFormatProvider.getInputFormatClassName());
        return jsc.newAPIHadoopRDD(hConf, inputFormatClass, keyClass, valueClass);
      } catch (ClassNotFoundException e) {
        throw Throwables.propagate(e);
      }
    }

    if (datasetInfos.containsKey(inputName)) {
      DatasetInfo datasetInfo = datasetInfos.get(inputName);
      return sec.fromDataset(datasetInfo.getDatasetName(), datasetInfo.getDatasetArgs());
    }
    // This should never happen since the constructor is private and it only get calls from static create() methods
    // which make sure one and only one of those source type will be specified.
    throw new IllegalStateException("Unknown source type");
  }

  private void addStageInput(String stageName, String inputName) {
    Set<String> inputs = sourceInputs.get(stageName);
    if (inputs == null) {
      inputs = new HashSet<>();
    }
    inputs.add(inputName);
    sourceInputs.put(stageName, inputs);
  }

  private static final class BasicInputFormatProvider implements InputFormatProvider {

    private final String inputFormatClassName;
    private final Map<String, String> configuration;

    private BasicInputFormatProvider(String inputFormatClassName, Map<String, String> configuration) {
      this.inputFormatClassName = inputFormatClassName;
      this.configuration = ImmutableMap.copyOf(configuration);
    }

    @Override
    public String getInputFormatClassName() {
      return inputFormatClassName;
    }

    @Override
    public Map<String, String> getInputFormatConfiguration() {
      return configuration;
    }
  }
}
