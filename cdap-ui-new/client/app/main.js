import angular from 'angular';
import constants from './constants';
import home from './features/home/routes';

angular.module(constants.app, [
    angular.module(`${constants.app}.feature`, [
      `${constants.app}.feature.home`
    ]).name
  ])

  .config(['$locationProvider', ($locationProvider) => {
    $locationProvider.html5Mode(true);
  }])

  .run(() => {
    console.log('awesome! in: ', constants.app);
  });
