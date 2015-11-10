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

angular.module(PKG.name + '.feature.hydrator')
  .service('DetailRunsStore', function(PipelineDetailDispatcher, $state, myHelpers, GLOBALS, myPipelineCommonApi) {

    var dispatcher = PipelineDetailDispatcher.getDispatcher();
    this.changeListeners = [];
    this.setDefaults = function(app) {
      this.state = {
        runs:{
          list: [],
          latest: {},
          count: 0,
        },
        params: app.params || {},
        scheduleParams: app.scheduleParams || {},
        logsParams: app.logsParams || {},
        configJson: app.configJson || {},
        cloneConfig: app.cloneConfig || {},
        api: app.api,
        type: app.type,
        metricProgramType: app.metricProgramType,
        statistics: '',
        name: app.name,
        datasets: app.datasets || [],
        streams: app.streams || [],
        description: app.description
      };
    };
    this.setDefaults({});

    this.changeListeners = [];

    this.getRuns = function() {
      return this.state.runs.list;
    };
    this.getLatestRun = function() {
      return this.state.runs.list[0];
    };
    this.getHistory = this.getRuns;

    this.getStatus = function() {
      var status;
      if (this.state.runs.list.length === 0) {
        status = 'STOPPED';
      } else {
        status = myHelpers.objectQuery(this.state, 'runs', 'latest', 'status') || '';
      }
      return status;
    };
    this.getRunsCount = function() {
      return this.state.runs.count;
    };
    this.getDatasets = function() {
      return this.state.datasets;
    };
    this.getStreams = function() {
      return this.state.streams;
    };
    this.getApi = function() {
      return this.state.api;
    };
    this.getParams = function() {
      return this.state.params;
    };
    this.getScheduleParams = function() {
      return this.state.scheduleParams;
    };
    this.getLogsParams = function() {
      var logsParams = angular.extend({runId: this.state.runs.latest.runid}, this.state.logsParams);
      return logsParams;
    };
    this.getConfigJson = function() {
      return this.state.configJson;
    };
    this.getCloneConfig = function() {
      return this.state.cloneConfig;
    };
    this.getPipelineType = function() {
      return this.state.type;
    };
    this.getPipelineName = function() {
      return this.state.name;
    };
    this.getPipelineDescription = function() {
      return this.state.description;
    };
    this.getStatistics = function() {
      return this.state.statistics;
    };
    this.getAppType = function() {
      return this.state.type;
    };
    this.getMetricProgramType = function() {
      return this.state.metricProgramType;
    };
    this.registerOnChangeListener = function(callback) {
      this.changeListeners.push(callback);
    };
    this.emitChange = function() {
      this.changeListeners.forEach(function(callback) {
        callback(this.state);
      }.bind(this));
    };

    this.setRunsState = function(runs) {
      if (!runs.length) {
        return;
      }
      this.state.runs = {
        list: runs,
        count: runs.length,
        latest: runs[0],
        runsCount: runs.length
      };
      if (this.state.type === GLOBALS.etlBatch) {
        this.state.logsParams.runId = this.state.runs.latest.properties.ETLMapReduce;
      } else {
        this.state.logsParams.runId = this.state.runs.latest.runid;
      }
      this.emitChange();
    };
    this.setStatistics = function(statistics) {
      this.state.statistics = statistics;
      this.emitChange();
    };
    this.init = function(app) {
      var appConfig = {};
      var appLevelParams,
          logsLevelParams,
          metricProgramType,
          programType;

      angular.extend(appConfig, app);
      try {
        appConfig.configJson = JSON.parse(app.configuration);
      } catch(e) {
        appConfig.configJson = e;
        console.log('ERROR cannot parse configuration');
        return;
      }
      appLevelParams = {
        namespace: $state.params.namespace,
        app: app.name
      };

      logsLevelParams = {
        namespace: $state.params.namespace,
        appId: app.name
      };

      programType = app.artifact.name === GLOBALS.etlBatch ? 'WORKFLOWS' : 'WORKER';

      if (programType === 'WORKFLOWS') {
        angular.forEach(app.programs, function (program) {
          if (program.type === 'Workflow') {
            appLevelParams.programName = program.id;
            appLevelParams.programType = program.type.toLowerCase() + 's';
          } else {
            metricProgramType = program.type.toLowerCase();

            logsLevelParams.programId = program.id;
            logsLevelParams.programType = program.type.toLowerCase();
          }
        });
      } else {
        angular.forEach(app.programs, function (program) {
          metricProgramType = program.type.toLowerCase();
          appLevelParams.programName = program.id;
          appLevelParams.programType = program.type.toLowerCase() + 's';

          logsLevelParams.programId = program.id;
          logsLevelParams.programType = program.type.toLowerCase() + 's';
        });
      }

      appConfig.streams = app.streams.map(function (stream) {
        stream.type = 'Stream';
        return stream;
      });
      appConfig.datasets = app.datasets.map(function (dataset) {
        dataset.type = 'Dataset';
        return dataset;
      });
      appConfig.logsParams = logsLevelParams;
      appConfig.params = appLevelParams;
      appConfig.api = myPipelineCommonApi;
      appConfig.metricProgramType = metricProgramType;
      appConfig.type = app.artifact.name;
      appConfig.scheduleParams = {
        app: appLevelParams.app,
        schedule: 'etlWorkflow',
        namespace: appLevelParams.namespace
      };
      appConfig.cloneConfig = {
        name: app.name,
        artifact: app.artifact,
        template: app.artifact.name,
        description: app.description,
        config: {
          source: appConfig.configJson.source,
          sinks: appConfig.configJson.sinks,
          transforms: appConfig.configJson.transforms,
          instances: app.instance,
          schedule: appConfig.configJson.schedule
        }
      };
      this.setDefaults(appConfig);
    };

    dispatcher.register('onRunsChange', this.setRunsState.bind(this));
    dispatcher.register('onStatisticsFetch', this.setStatistics.bind(this));
    dispatcher.register('onReset', this.setDefaults.bind(this, {}));
  });