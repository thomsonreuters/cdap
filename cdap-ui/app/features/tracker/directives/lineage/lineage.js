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

function LineageController ($scope, jsPlumb, $timeout, $state, LineageStore, myTrackerApi) {
  'ngInject';

  var vm = this;

  function render() {
    jsPlumb.reset();
    if (vm.instance) { vm.instance.reset(); }

    vm.nodes = LineageStore.getNodes();
    vm.connections = LineageStore.getConnections();
    vm.uniqueNodes = LineageStore.getUniqueNodes();
    vm.graph = LineageStore.getGraph();

    if (vm.nodes.length === 0) { return; }

    vm.graphInfo = vm.graph.graph();

    $timeout( () => {
      angular.forEach(vm.connections, (conn) => {
        vm.instance.connect({
          source: conn.source,
          target: conn.target,
          detachable: false,
          anchors: ['Right', 'Left']
        });
      });

      $timeout( () => {
        vm.instance.repaintEverything();
      });
    });
  }

  LineageStore.registerOnChangeListener(render);

  jsPlumb.ready( () => {
    jsPlumb.setContainer('lineage-diagram');

    vm.instance = jsPlumb.getInstance({
      PaintStyle: {
        lineWidth: 2,
        strokeStyle: 'rgba(0,0,0, 1)'
      },
      Connector: [ 'Flowchart', {gap: 0, stub: [10, 15], alwaysRespectStubs: true, cornerRadius: 3} ],
      Endpoints: ['Blank', 'Blank']
    });

    render();
  });

  vm.nodeClick = (node) => {
    let nodeInfo = vm.uniqueNodes[node.uniqueNodeId];
    if (nodeInfo.nodeType === 'data') {
      $state.go('tracker.entity.metadata', { entityType: nodeInfo.entityType, entityId: nodeInfo.entityId });
    } else {
      node.showPopover = true;

      node.popover = {
        activeRunIndex: 0,
        activeRunId: nodeInfo.runs[0],
        runInfo: {}
      };
      fetchRunStatus(nodeInfo, node.popover.activeRunId, node.popover.runInfo);
    }
  };

  vm.closePopover = (event, node) => {
    event.stopPropagation();
    node.showPopover = false;
  };

  vm.preventPropagation = (event) => {
    event.stopPropagation();
  };

  vm.nextRun = (event, node) => {
    event.stopPropagation();
    let nodeInfo = vm.uniqueNodes[node.uniqueNodeId];
    if (node.popover.activeRunIndex === nodeInfo.runs.length - 1) {
      node.popover.activeRunIndex = 0;
    } else {
      node.popover.activeRunIndex++;
    }
    node.popover.activeRunId = nodeInfo.runs[node.popover.activeRunIndex];
    fetchRunStatus(nodeInfo, node.popover.activeRunId, node.popover.runInfo);
  };
  vm.prevRun = (event, node) => {
    event.stopPropagation();
    let nodeInfo = vm.uniqueNodes[node.uniqueNodeId];
    if (node.popover.activeRunIndex === 0) {
      node.popover.activeRunIndex = nodeInfo.runs.length - 1;
    } else {
      node.popover.activeRunIndex--;
    }
    node.popover.activeRunId = nodeInfo.runs[node.popover.activeRunIndex];
    fetchRunStatus(nodeInfo, node.popover.activeRunId, node.popover.runInfo);
  };

  vm.mouseLeaveNode = (node) => {
    node.timeout = $timeout(() => {
      node.showNavigation = false;
    }, 300);
  };

  vm.mouseEnterNavigation = (node) => {
    $timeout.cancel(node.timeout);
  };

  vm.mouseLeaveNavigation = (node) => {
    $timeout(() => {
      node.showNavigation = false;
    }, 300);
  };

  vm.navigationClick = (event, node) => {
    event.stopPropagation();
    let unique = vm.uniqueNodes[node.uniqueNodeId];
    $scope.navigationFunction().call($scope.context, unique.entityType, unique.entityId);
  };

  function fetchRunStatus(node, runId, runInfo) {
    let params = {
      namespace: $state.params.namespace,
      appId: node.applicationId,
      programType: node.entityType,
      programId: node.entityId,
      runId: runId,
      scope: $scope
    };

    myTrackerApi.getProgramRunStatus(params)
      .$promise
      .then((res) => {
        runInfo.start = res.start * 1000;
        runInfo.status = res.status;
        runInfo.duration = res.end ? (res.end - res.start) * 1000 : '-';
      });
  }

  $scope.$on('$destroy', () => {
    LineageStore.setDefaults();
  });
}

angular.module(PKG.name + '.feature.tracker')
  .directive('myLineageDiagram', () => {
    return {
      restrict: 'E',
      scope: {
        navigationFunction: '&',
        context: '='
      },
      templateUrl: '/assets/features/tracker/directives/lineage/lineage.html',
      controller: LineageController,
      controllerAs: 'Lineage'
    };
  });