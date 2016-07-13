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

package co.cask.cdap.kms;

import co.cask.cdap.api.security.store.SecureStore;
import co.cask.cdap.api.security.store.SecureStoreManager;
import co.cask.cdap.common.conf.CConfiguration;
import org.apache.commons.io.Charsets;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class KmsSecureStoreTest {
  private static final String KEY1 = "key1";
  private static final String VALUE1 = "value1";
  private static final String DESCRIPTION1 = "This is the first key.";
  private static final Map<String, String> PROPERTIES_1 = new HashMap<>();

  private SecureStoreManager secureStoreManager;
  private SecureStore secureStore;
  private Configuration conf;

  static {
    PROPERTIES_1.put("Prop1", "Val1");
  }


  @Before
  public void setUp() throws Exception {
    conf = new Configuration();
    KmsSecureStore kmsSecureStore = new KmsSecureStore(conf);
    secureStore = kmsSecureStore;
    secureStoreManager = kmsSecureStore;

  }

  @Test
  public void put() throws Exception {
    conf.set("hadoop.security.authentication", "kerberos");
    conf.set("hadoop.kms.authentication.token.validity", "1");
    conf.set("hadoop.kms.authentication.type", "kerberos");
    conf.set("hadoop.kms.authentication.kerberos.principal", "cdap");
    conf.set("hadoop.kms.authentication.kerberos.name.rules", "DEFAULT");
    UserGroupInformation.setConfiguration(conf);

    secureStoreManager.put(KEY1, VALUE1.getBytes(Charsets.UTF_8), DESCRIPTION1, PROPERTIES_1);
  }

  public void delete() throws Exception {

  }

  @Test
  public void list() throws Exception {

  }

  @Test
  public void get() throws Exception {

  }
}
