import angular from 'angular';
import home from './module';
import homectrl from './home-ctrl';
import constants from '../../constants';

angular.module(`${constants.app}.feature.home`)
  .config(['$stateProvider', ($stateProvider) => {
    $stateProvider
      .state('home', {
        url: '/home',
        templateUrl: '/assets/app/features/home/home.html',
        controller: 'HomeController',
        controllerAs: 'HomeCtrl'
      });
  }]);
