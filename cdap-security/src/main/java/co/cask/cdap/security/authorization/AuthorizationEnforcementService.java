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

import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.proto.id.EntityId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.security.Action;
import co.cask.cdap.proto.security.Principal;
import co.cask.cdap.proto.security.Privilege;
import co.cask.cdap.security.spi.authorization.AuthorizationEnforcer;
import co.cask.cdap.security.spi.authorization.Authorizer;
import co.cask.cdap.security.spi.authorization.UnauthorizedException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A {@link AuthorizationEnforcer} used to enforce authorization policies in programs. If caching is enabled using
 * {@link co.cask.cdap.common.conf.Constants.Security.Authorization#CACHE_ENABLED}, authorization policies are cached
 * locally. A thread refreshes the cached policies periodically.
 */
@Singleton
public class AuthorizationEnforcementService extends AbstractScheduledService implements AuthorizationEnforcer {

  private static final Logger LOG = LoggerFactory.getLogger(AuthorizationEnforcementService.class);

  private final AuthorizerInstantiator authorizerInstantiator;
  private final boolean authorizationEnabled;
  private final boolean cacheEnabled;
  private final int cacheTtlSeconds;
  private final Map<Principal, Set<Privilege>> authPolicyCache;
  private final Lock readLock;
  private final Lock writeLock;

  @Inject
  public AuthorizationEnforcementService(AuthorizerInstantiator authorizerInstantiator, CConfiguration cConf) {
    this.authorizerInstantiator = authorizerInstantiator;
    this.authorizationEnabled = cConf.getBoolean(Constants.Security.Authorization.ENABLED);
    this.cacheEnabled = cConf.getBoolean(Constants.Security.Authorization.CACHE_ENABLED);
    this.cacheTtlSeconds = cConf.getInt(Constants.Security.Authorization.CACHE_TTL_SECS);
    this.authPolicyCache = new HashMap<>();
    ReadWriteLock lock = new ReentrantReadWriteLock();
    this.readLock = lock.readLock();
    this.writeLock = lock.writeLock();
  }

  @Override
  protected Scheduler scheduler() {
    return Scheduler.newFixedDelaySchedule(0, cacheTtlSeconds, TimeUnit.SECONDS);
  }

  @Override
  protected void runOneIteration() throws Exception {
    if (!authorizationEnabled || !cacheEnabled) {
      return;
    }
    LOG.debug("Running authorization enforcement service iteration...");
    // we want to block all access to the cache while the cache update is running
    writeLock.lock();
    try {
      // TODO: Should we clear the cache first? That would clear entries for potentially stale users, for whom all
      // privileges have been revoked. However, authorization still wouldn't be affected, because for such users, after
      // the iteration is completed, the cache will contain an empty set.
      for (Principal principal : authPolicyCache.keySet()) {
        updatePrivileges(principal);
      }
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Update cache with privileges for the specified principal. Invoked by the thread that updates the cache as well as
   * by app fabric just before starting the program, to make sure that the privileges of the user running the program
   * are cached when the program is run.
   *
   * @param principal the {@link Principal} whose privileges are to be cached.
   */
  public void updatePrivileges(Principal principal) throws Exception {
    if (!authorizationEnabled || !cacheEnabled) {
      return;
    }
    Authorizer authorizer = authorizerInstantiator.get();
    Set<Privilege> privileges = authorizer.listPrivileges(principal);
    LOG.info("Granting read write on default namespace to {}", principal);
    authorizer.grant(NamespaceId.DEFAULT, new Principal("admins", Principal.PrincipalType.ROLE),
                     ImmutableSet.of(Action.READ, Action.WRITE));
    LOG.info("Granted read write on default namespace to {}", principal);
    authPolicyCache.put(principal, privileges);
    LOG.trace("Updated privileges for principal {} as {}", principal, privileges);
  }

  @Override
  protected void startUp() throws Exception {
    super.startUp();
    LOG.info("Starting authorization enforcement service...");
    if (!authorizationEnabled && cacheEnabled) {
      LOG.warn("Authorization policy caching is enabled ({} is set to true), however, no privileges will be cached " +
                 "because authorization is disabled ({} is set to false). ",
               Constants.Security.Authorization.CACHE_ENABLED, Constants.Security.Authorization.ENABLED);
    }
  }

  @Override
  public void enforce(EntityId entity, Principal principal, Action action) throws Exception {
    if (!authorizationEnabled) {
      return;
    }
    if (!cacheEnabled) {
      authorizerInstantiator.get().enforce(entity, principal, action);
      return;
    }
    readLock.lock();
    try {
      if (authPolicyCache.containsKey(principal)) {
        Set<Privilege> privileges = authPolicyCache.get(principal);
        if (privileges.contains(new Privilege(entity, action))) {
          return;
        }
      }
    } finally {
      readLock.unlock();
    }
    throw new UnauthorizedException(principal, action, entity);
  }

  @Override
  protected void shutDown() throws Exception {
    super.shutDown();
    LOG.info("Shutting down authorization enforcement service...");
  }

  @VisibleForTesting
  Map<Principal, Set<Privilege>> getCache() {
    return authPolicyCache;
  }
}
