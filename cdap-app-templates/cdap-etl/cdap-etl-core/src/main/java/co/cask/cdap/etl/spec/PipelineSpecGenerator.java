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

package co.cask.cdap.etl.spec;

import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.dataset.Dataset;
import co.cask.cdap.api.dataset.DatasetProperties;
import co.cask.cdap.api.plugin.PluginConfigurer;
import co.cask.cdap.etl.api.PipelineConfigurable;
import co.cask.cdap.etl.api.PipelineConfigurer;
import co.cask.cdap.etl.api.action.Action;
import co.cask.cdap.etl.common.DefaultPipelineConfigurer;
import co.cask.cdap.etl.planner.Dag;
import co.cask.cdap.etl.proto.Connection;
import co.cask.cdap.etl.proto.v2.ETLConfig;
import co.cask.cdap.etl.proto.v2.ETLPlugin;
import co.cask.cdap.etl.proto.v2.ETLStage;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This is run at application configure time to take an application config {@link ETLConfig} and call
 * {@link PipelineConfigurable#configurePipeline(PipelineConfigurer)} on all plugins in the pipeline.
 * This generates a {@link PipelineSpec} which the programs understand.
 *
 * @param <C> the type of user provided config
 * @param <P> the pipeline specification generated from the config
 */
public abstract class PipelineSpecGenerator<C extends ETLConfig, P extends PipelineSpec> {
  protected final PluginConfigurer configurer;
  private final Class<? extends Dataset> errorDatasetClass;
  private final DatasetProperties errorDatasetProperties;
  private final Set<String> sourcePluginTypes;
  private final Set<String> sinkPluginTypes;

  protected PipelineSpecGenerator(PluginConfigurer configurer,
                                  Set<String> sourcePluginTypes,
                                  Set<String> sinkPluginTypes,
                                  Class<? extends Dataset> errorDatasetClass,
                                  DatasetProperties errorDatasetProperties) {
    this.configurer = configurer;
    this.sourcePluginTypes = sourcePluginTypes;
    this.sinkPluginTypes = sinkPluginTypes;
    this.errorDatasetClass = errorDatasetClass;
    this.errorDatasetProperties = errorDatasetProperties;
  }

  /**
   * Validate the user provided ETL config and generate a pipeline specification from it.
   * It will also register all plugins used by the pipeline and create any error datasets used by the pipeline.
   *
   * A valid pipeline has the following properties:
   *
   * All stages in the pipeline have a unique name.
   * Source stages have at least one output and no inputs.
   * Sink stages have at least one input and no outputs.
   * There are no cycles in the pipeline.
   * All inputs into a stage have the same schema.
   *
   * @param config user provided ETL config
   */
  public abstract P generateSpec(C config);

  /**
   * Performs most of the validation and configuration needed by a pipeline.
   * Handles stages, connections, resources, and stage logging settings.
   *
   * @param config user provided ETL config
   * @param specBuilder builder for creating a pipeline spec.
   */
  protected void configureStages(ETLConfig config, PipelineSpec.Builder specBuilder) {
    // validate the config and determine the order we should configure the stages in.
    List<StageConnections> traversalOrder = validateConfig(config);

    Map<String, DefaultPipelineConfigurer> pluginConfigurers = new HashMap<>(traversalOrder.size());
    Map<String, String> pluginTypes = new HashMap<>(traversalOrder.size());
    for (StageConnections stageConnections : traversalOrder) {
      String stageName = stageConnections.getStage().getName();
      pluginTypes.put(stageName, stageConnections.getStage().getPlugin().getType());
      pluginConfigurers.put(stageName, new DefaultPipelineConfigurer(configurer, stageName));
    }

    // configure the stages in order and build up the stage specs
    for (StageConnections stageConnections : traversalOrder) {
      ETLStage stage = stageConnections.getStage();
      String stageName = stage.getName();
      DefaultPipelineConfigurer pluginConfigurer = pluginConfigurers.get(stageName);

      StageSpec stageSpec = configureStage(stageConnections, pluginConfigurer);
      Schema outputSchema = stageSpec.getOutputSchema();

      // for each output, set their input schema to our output schema
      for (String outputStageName : stageConnections.getOutputs()) {
        pluginConfigurers.get(outputStageName).getStageConfigurer().addInputSchema(stageName, outputSchema);
      }
      specBuilder.addStage(stageSpec);
    }

    specBuilder.addConnections(config.getConnections())
      .setResources(config.getResources())
      .setStageLoggingEnabled(config.isStageLoggingEnabled());
  }

  /**
   * Configures a stage and returns the spec for it.
   *
   * @param stageConnections the user provided configuration for the stage along with its connections
   * @param pluginConfigurer configurer used to configure the stage
   * @return the spec for the stage
   */
  private StageSpec configureStage(StageConnections stageConnections, DefaultPipelineConfigurer pluginConfigurer) {
    ETLStage stage = stageConnections.getStage();
    String stageName = stage.getName();
    ETLPlugin stagePlugin = stage.getPlugin();

    if (!Strings.isNullOrEmpty(stage.getErrorDatasetName())) {
      configurer.createDataset(stage.getErrorDatasetName(), errorDatasetClass, errorDatasetProperties);
    }

    PluginSpec pluginSpec = configurePlugin(stageName, stagePlugin, pluginConfigurer);
    Schema outputSchema = pluginConfigurer.getStageConfigurer().getOutputSchema();
    Map<String, Schema> inputSchemas = pluginConfigurer.getStageConfigurer().getInputSchemas();
    return StageSpec.builder(stageName, pluginSpec)
      .setErrorDatasetName(stage.getErrorDatasetName())
      .addInputSchemas(inputSchemas)
      .setOutputSchema(outputSchema)
      .addInputs(stageConnections.getInputs())
      .addOutputs(stageConnections.getOutputs())
      .build();
  }


  /**
   * Configures a plugin and returns the spec for it.
   *
   * @param pluginId the unique plugin id
   * @param etlPlugin user provided configuration for the plugin
   * @param pipelineConfigurer configurer used to configure the plugin
   * @return the spec for the plugin
   */
  protected PluginSpec configurePlugin(String pluginId, ETLPlugin etlPlugin,
                                       PipelineConfigurer pipelineConfigurer) {
    TrackedPluginSelector pluginSelector = new TrackedPluginSelector(etlPlugin.getPluginSelector());
    PipelineConfigurable plugin = configurer.usePlugin(etlPlugin.getType(),
                                                       etlPlugin.getName(),
                                                       pluginId,
                                                       etlPlugin.getPluginProperties(),
                                                       pluginSelector);
    if (plugin == null) {
      throw new IllegalArgumentException(
        String.format("No plugin of type %s and name %s could be found for stage %s.",
                      etlPlugin.getType(), etlPlugin.getName(), pluginId));
    }

    try {
      plugin.configurePipeline(pipelineConfigurer);
    } catch (Exception e) {
      throw new RuntimeException(
        String.format("Exception while configuring plugin of type %s and name %s for stage %s: %s",
                      etlPlugin.getType(), etlPlugin.getName(), pluginId, e.getMessage()),
        e);
    }

    return new PluginSpec(etlPlugin.getType(),
                          etlPlugin.getName(),
                          etlPlugin.getProperties(),
                          pluginSelector.getSelectedArtifact());
  }

  /**
   * Validate that this is a valid pipeline. A valid pipeline has the following properties:
   *
   * All stages in the pipeline have a unique name.
   * Source stages have at least one output and no inputs.
   * Sink stages have at least one input and no outputs.
   * There are no cycles in the pipeline.
   * All inputs into a stage have the same schema.
   *
   * Returns the stages in the order they should be configured to ensure that all input stages are configured
   * before their output.
   *
   * @param config the user provided configuration
   * @return the order to configure the stages in
   * @throws IllegalArgumentException if the pipeline is invalid
   */
  private List<StageConnections> validateConfig(ETLConfig config) {
    config.validate();
    if (config.getStages().isEmpty()) {
      throw new IllegalArgumentException("A pipeline must contain at least one stage.");
    }

    Set<String> actionStages = new HashSet<>();
    // check stage name uniqueness
    Set<String> stageNames = new HashSet<>();
    for (ETLStage stage : config.getStages()) {
      if (!stageNames.add(stage.getName())) {
        throw new IllegalArgumentException(
          String.format("Invalid pipeline. Multiple stages are named %s. Please ensure all stage names are unique",
                        stage.getName()));
      }
      // if stage is Action stage, add it to the Action stage set
      if (Action.PLUGIN_TYPE.equals(stage.getPlugin().getType())) {
        actionStages.add(stage.getName());
      }
    }

    // check that the from and to are names of actual stages
    for (Connection connection : config.getConnections()) {
      if (!stageNames.contains(connection.getFrom())) {
        throw new IllegalArgumentException(
          String.format("Invalid connection %s. %s is not a stage.", connection, connection.getFrom()));
      }
      if (!stageNames.contains(connection.getTo())) {
        throw new IllegalArgumentException(
          String.format("Invalid connection %s. %s is not a stage.", connection, connection.getTo()));
      }
    }

    Dag dag = new Dag(config.getConnections());

    // check source plugins are sources in the dag
    // check sink plugins are sinks in the dag
    // check that other plugins are not sources or sinks in the dag
    Map<String, StageConnections> stages = new HashMap<>();
    for (ETLStage stage : config.getStages()) {
      String stageName = stage.getName();
      Set<String> stageInputs = dag.getNodeInputs(stageName);
      Set<String> stageOutputs = dag.getNodeOutputs(stageName);

      if (isSource(stage.getPlugin().getType())) {
        if (!stageInputs.isEmpty() && !actionStages.containsAll(stageInputs)) {
          throw new IllegalArgumentException(
            String.format("Source %s has incoming connections from %s. Sources cannot have any incoming connections.",
                          stageName, Joiner.on(',').join(stageInputs)));
        }
      } else if (isSink(stage.getPlugin().getType())) {
        if (!stageOutputs.isEmpty() && !actionStages.containsAll(stageOutputs)) {
          throw new IllegalArgumentException(
            String.format("Sink %s has outgoing connections to %s. Sinks cannot have any outgoing connections.",
                          stageName, Joiner.on(',').join(stageOutputs)));
        }
      } else {
        if (stageInputs.isEmpty() && !(stage.getPlugin().getType().equals(Action.PLUGIN_TYPE))) {
          throw new IllegalArgumentException(
            String.format("Stage %s is unreachable, it has no incoming connections.", stageName));
        }
        if (stageOutputs.isEmpty() && !(stage.getPlugin().getType().equals(Action.PLUGIN_TYPE))) {
          throw new IllegalArgumentException(
            String.format("Stage %s is a dead end, it has no outgoing connections.", stageName));
        }
      }
      stages.put(stageName, new StageConnections(stage, stageInputs, stageOutputs));
    }

    List<StageConnections> traversalOrder = new ArrayList<>(stages.size());
    for (String stageName : dag.getTopologicalOrder()) {
      traversalOrder.add(stages.get(stageName));
    }

    return traversalOrder;
  }

  private boolean isSource(String pluginType) {
    return sourcePluginTypes.contains(pluginType);
  }

  private boolean isSink(String pluginType) {
    return sinkPluginTypes.contains(pluginType);
  }
}
