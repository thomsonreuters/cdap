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

package co.cask.cdap.security.authorization;

import co.cask.cdap.security.spi.authorization.AuthorizationEnforcer;
import com.google.inject.AbstractModule;
import com.google.inject.Scopes;

/**
 * Created by bhooshan on 7/13/16.
 */
public class AuthorizationEnforcementModule extends AbstractModule {
  @Override
  protected void configure() {
    // also bind AuthorizationEnforcementService as a singleton. This binding is used while starting/stopping
    // the service itself.
    bind(AuthorizationEnforcementService.class).to(DefaultAuthorizationEnforcementService.class)
      .in(Scopes.SINGLETON);
    // bind AuthorizationEnforcer to AuthorizationEnforcementService
    bind(AuthorizationEnforcer.class).to(AuthorizationEnforcementService.class).in(Scopes.SINGLETON);
  }
}
