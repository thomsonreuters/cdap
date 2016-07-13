/*
 * Copyright © 2015-2016 Cask Data, Inc.
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

package co.cask.cdap.data2.registry;

import co.cask.cdap.proto.Id;

import java.util.Set;

/**
 * Store program -> dataset/stream usage information. Differs from UsageRegistry in that UsageRegistry does not have
 * a remote implementation, usable from program runtime.
 */
public interface RuntimeUsageRegistry {

  /**
   * Registers usage of a stream by multiple ids.
   *
   * @param users the users of the stream
   * @param streamId the stream
   */
  void registerAll(final Iterable<? extends Id> users, final Id.Stream streamId);

  /**
   * Register usage of a stream by an id.
   *
   * @param user the user of the stream
   * @param streamId the stream
   */
  void register(Id user, Id.Stream streamId);

  /**
   * Registers usage of a stream by multiple ids.
   *
   * @param users the users of the stream
   * @param datasetId the stream
   */
  void registerAll(final Iterable<? extends Id> users, final Id.DatasetInstance datasetId);

  /**
   * Registers usage of a dataset by multiple ids.
   *
   * @param user the user of the dataset
   * @param datasetId the dataset
   */
  void register(Id user, Id.DatasetInstance datasetId);

  /**
   * Registers usage of a dataset by a program.
   *
   * @param programId program
   * @param datasetInstanceId dataset
   */
  void register(final Id.Program programId, final Id.DatasetInstance datasetInstanceId);

  /**
   * Registers usage of a stream by a program.
   *
   * @param programId program
   * @param streamId  stream
   */
  void register(final Id.Program programId, final Id.Stream streamId);
}
