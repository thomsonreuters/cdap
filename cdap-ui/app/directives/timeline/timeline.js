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

function link (scope, element) {

  //Globals
  let width,
      height,
      paddingLeft,
      paddingRight,
      maxRange,
      sliderLimit,
      pinX,
      sliderX,
      timelineStack,
      startTime,
      endTime,
      firstRun = true;

  //Components
  let sliderHandle,
      pinHandle,
      sliderBrush,
      scrollPinBrush,
      xScale,
      timelineData,
      slide,
      slider,
      timescaleSvg,
      scrollPinSvg,
      xAxis,
      sliderBar;

  //Initialize charting
  scope.initialize = () => {

    //If chart already exists, remove it
    if(timescaleSvg){
      d3.selectAll('svg > *').remove();
      timescaleSvg.remove();
    }

    if(scrollPinSvg){
      scrollPinSvg.remove();
    }

    width = element.parent()[0].offsetWidth;
    height = 50;
    paddingLeft = 15;
    paddingRight = 15;
    maxRange = width - paddingLeft - paddingRight;
    sliderLimit = maxRange + 24;
    pinX = 0;
    sliderX = 0;
    timelineStack = {};
    sliderHandle = undefined;
    pinHandle = undefined;
    sliderBrush = undefined;
    scrollPinBrush = undefined;
    xScale = undefined;
    slide = undefined;
    slider = undefined;
    timescaleSvg = undefined;
    scrollPinSvg = undefined;
    xAxis = undefined;
    sliderBar = undefined;
    timelineData = scope.metadata;

    scope.plot();
  };

  /* ------------------- Plot Function ------------------- */
  scope.plot = function(){

    startTime = timelineData.qid.startTime*1000;
    endTime = timelineData.qid.endTime*1000;

    timescaleSvg = d3.select('.timeline-log-chart')
                .append('svg')
                .attr('width', width)
                .attr('height', height);

    //Set the Range and Domain
    xScale = d3.time.scale().range([0, (maxRange)]);
    xScale.domain([startTime, endTime]);

    xAxis = d3.svg.axis().scale(xScale)
      .orient('bottom')
      .innerTickSize(-40)
      .outerTickSize(0)
      .tickPadding(7)
      .ticks(8);

    generateEventCircles();
    renderBrushAndSlider();
  };

  // -------------------------Build Brush / Sliders------------------------- //
  function renderBrushAndSlider(){

    timescaleSvg.append('g')
      .attr('class', 'xaxis-bottom')
      .attr('transform', 'translate(' + ( (paddingLeft + paddingRight) / 2) + ',' + (height - 20) + ')')
      .call(xAxis);

    //attach handler to brush
    sliderBrush = d3.svg.brush()
        .x(xScale)
        .on('brush', function(){
          if(d3.event.sourceEvent) {
            let index = d3.mouse(this)[0];
            updateSlider(index);
          }
        });

    //Creates the top slider and trailing dark background
    sliderBar = timescaleSvg.append('g')
      .attr('class', 'slider leftSlider')
      .call(d3.svg.axis()
        .scale(xScale)
        .tickSize(0)
        .tickFormat(''))
      .select('.domain')
      .attr('class', 'fill-bar');

    slide = timescaleSvg.append('g')
          .attr('class', 'slider')
          .attr('transform' , 'translate(0,10)')
          .call(sliderBrush);

    if(firstRun){
      firstRun = false;
      scope.sliderBarPositionRefresh = xScale.invert(0);
      scope.Timeline.updateStartTimeInStore(xScale.invert(0));
    }
    let xValue = xScale(scope.sliderBarPositionRefresh);
    if(xValue < 0 || xValue > maxRange){
      xValue = 0;
    }

    sliderBar.attr('d', 'M0,0V0H' + xValue + 'V0');

    sliderHandle = slide.append('svg:image')
      .attr('width', 8)
      .attr('height', 52)
      .attr('xlink:href', '/assets/img/sliderHandle.svg')
      .attr('x', xValue-1)
      .attr('y', -10);

    //Append the Top slider
    scrollPinBrush = d3.svg.brush()
        .x(xScale)
        .on('brush', function(){
          let xPos = d3.mouse(this)[0];

          if(xPos < 0){
            xPos = 0;
          }

          if(xPos > width - 8){
            xPos = width - 8;
          }

          if(xPos > sliderX){
            updatePin(xPos-11);
          }
        });

    scrollPinSvg = d3.select('.top-bar').append('svg')
        .attr('width', width)
        .attr('height', 20);

    scrollPinSvg.append('g')
        .attr('class', 'xaxis-top')
        .call(d3.svg.axis()
          .scale(xScale)
          .orient('bottom'))
      .select('.domain')
      .select( function() {
        return this.parentNode.appendChild(this.cloneNode(true));
      });

    slider = scrollPinSvg.append('g')
        .attr('class', 'slider')
        .attr('width', width)
        .call(scrollPinBrush);

    slider.select('.background')
      .attr('height', 15);

    pinHandle = slider.append('svg:image')
      .attr('width', 40)
      .attr('height', 60)
      .attr('xlink:href', '/assets/img/scrollPin.svg')
      .attr('x', xValue - 13)
      .attr('y', 0);
  }

  var updatePin = function (val) {
    pinX = val - 13;
    pinHandle.attr('x', pinX);
  };

  function updateSlider(val) {
    if(val < 0){
      val = 0;
    }
    if(val > sliderLimit){
      val = sliderLimit;
    }

    sliderX = val;

    if(sliderX >= pinX){
      updatePin(sliderX);
    }

    sliderHandle.attr('x', val);
    sliderBar.attr('d', 'M0,0V0H' + val + 'V0');
    scope.Timeline.updateStartTimeInStore(xScale.invert(val));
  }

  scope.updateSlider = updateSlider;

  function generateEventCircles(){
    let circleClass;

    if(timelineData.qid.series.length > 0){
      for(let i = 0; i < timelineData.qid.series.length; i++){

        switch(timelineData.qid.series[i].metricName){
          case 'system.app.log.info':
            circleClass = 'red-circle';
            break;
          case 'system.app.log.error':
            circleClass = 'red-circle';
            break;
          case 'system.app.log.warn':
            circleClass = 'yellow-circle';
            break;
          default:
            circleClass = 'other-circle';
            break;
        }

        for(let j = 0; j < timelineData.qid.series[i].data.length; j++){
          let currentItem = timelineData.qid.series[i].data[j];
          let xVal = Math.floor(xScale(currentItem.time));
          let numEvents = currentItem.value;

          if(timelineStack[xVal] === undefined) {
            timelineStack[xVal] = 0;
          }

          //plot events until vertical limit (5)
          for(var k = 0; k < numEvents && timelineStack[xVal] < 5; k++){
            timelineStack[xVal]++;

            //Append the circle
            if(currentItem){
              timescaleSvg.append('circle').attr('cx', xScale(currentItem.time *1000)).attr('cy', (timelineStack[xVal])*7).attr('r', 2).attr('class', circleClass);
            }
          }
        }
      }
    }
  }
}

angular.module(PKG.name + '.commons')
.directive('myTimeline', function() {
  return {
    templateUrl: 'timeline/timeline.html',
    scope: {
      timelineData: '=?',
      namespaceId: '@',
      appId: '@',
      programType: '@',
      programId: '@',
      runId: '@'
    },
    link: link,
    bindToController: true,
    controller: 'TimelineController',
    controllerAs: 'Timeline'
  };
});
