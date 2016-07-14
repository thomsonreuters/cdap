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
package co.cask.cdap.etl.batch.customaction;

import co.cask.cdap.etl.api.action.SettableArguments;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Default implementation of {@link SettableArguments}.
 */
public class BasicSettableArguments implements SettableArguments {

  private final Map<String, String> options;

  public BasicSettableArguments(Map<String, String> arguments) {
    options = new HashMap<>();
    for (Map.Entry<String, String> argument : arguments.entrySet()) {
      options.put(argument.getKey(), argument.getValue());
    }
  }

  @Override
  public void setOption(String name, String value) {
    options.put(name, value);
  }

  @Override
  public boolean hasOption(String name) {
    return options.containsKey(name);
  }

  @Override
  public String getOption(String name) {
    return options.get(name);
  }

  @Override
  public Map<String, String> asMap() {
    return options;
  }

  @Override
  public Iterator<Map.Entry<String, String>> iterator() {
    return options.entrySet().iterator();
  }
}
