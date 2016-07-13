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

package co.cask.cdap.data2.metadata.writer;

import co.cask.cdap.data2.metadata.lineage.AccessType;
import co.cask.cdap.data2.metadata.lineage.LineageStore;
import co.cask.cdap.proto.Id;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

/**
 * Writes program-dataset access information into {@link LineageStore}.
 */
public class BasicLineageWriter implements LineageWriter {
  private static final Logger LOG = LoggerFactory.getLogger(BasicLineageWriter.class);

  private final LineageStore lineageStore;

  private final ConcurrentMap<DataAccessKey, Boolean> registered = new ConcurrentHashMap<>();

  @Inject
  BasicLineageWriter(LineageStore lineageStore) {
    this.lineageStore = lineageStore;
  }

  @Override
  public void addAccess(Id.Run run, Id.DatasetInstance datasetInstance, AccessType accessType) {
    addAccess(run, datasetInstance, accessType, null);
  }

  @Override
  public void addAccess(Id.Run run, Id.DatasetInstance datasetInstance, AccessType accessType,
                        @Nullable Id.NamespacedId component) {
    if (alreadyRegistered(run, datasetInstance, accessType, component)) {
      return;
    }

    long accessTime = System.currentTimeMillis();
    LOG.debug("Writing access for run {}, dataset {}, accessType {}, component {}, accessTime = {}",
              run, datasetInstance, accessType, component, accessTime);
    lineageStore.addAccess(run, datasetInstance, accessType, accessTime, component);
  }

  @Override
  public void addAccess(Id.Run run, Id.Stream stream, AccessType accessType) {
    addAccess(run, stream, accessType, null);
  }

  @Override
  public void addAccess(Id.Run run, Id.Stream stream, AccessType accessType, @Nullable Id.NamespacedId component) {
    if (alreadyRegistered(run, stream, accessType, component)) {
      return;
    }

    long accessTime = System.currentTimeMillis();
    LOG.debug("Writing access for run {}, stream {}, accessType {}, component {}, accessTime = {}",
              run, stream, accessType, component, accessTime);
    lineageStore.addAccess(run, stream, accessType, accessTime, component);
  }

  private boolean alreadyRegistered(Id.Run run, Id.NamespacedId data, AccessType accessType,
                                    @Nullable Id.NamespacedId component) {
    return registered.putIfAbsent(new DataAccessKey(run, data, accessType, component), true) != null;
  }

  /**
   * Key used to keep track of whether a particular access has been recorded already or not (for lineage).
   */
  public static final class DataAccessKey {
    private final Id.Run run;
    private final Id.NamespacedId data;
    private final AccessType accessType;
    private final Id.NamespacedId component;

    public DataAccessKey(Id.Run run, Id.NamespacedId data, AccessType accessType, @Nullable Id.NamespacedId component) {
      this.run = run;
      this.data = data;
      this.accessType = accessType;
      this.component = component;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof DataAccessKey)) {
        return false;
      }
      DataAccessKey dataAccessKey = (DataAccessKey) o;
      return Objects.equals(run, dataAccessKey.run) &&
        Objects.equals(data, dataAccessKey.data) &&
        Objects.equals(accessType, dataAccessKey.accessType) &&
        Objects.equals(component, dataAccessKey.component);
    }

    @Override
    public int hashCode() {
      return Objects.hash(run, data, accessType, component);
    }
  }
}
