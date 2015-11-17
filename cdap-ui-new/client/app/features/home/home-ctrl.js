import angular from 'angular';
import constants from '../../constants';

class HomeController {
  constructor() {
    this.property1 = 'Property1';
  }
}

HomeController.$inject = [];
angular.module(`${constants.app}.feature.home`)
  .controller('HomeController', HomeController);
