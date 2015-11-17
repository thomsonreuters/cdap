import angular from 'angular';
import constants from '../../constants';
import 'angular-ui-router';

export default angular.module(`${constants.app}.feature.home`, [
  'ui.router'
]);
