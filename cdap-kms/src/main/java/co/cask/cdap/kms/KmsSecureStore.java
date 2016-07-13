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
import co.cask.cdap.api.security.store.SecureStoreData;
import co.cask.cdap.api.security.store.SecureStoreManager;
import co.cask.cdap.api.security.store.SecureStoreMetadata;
import com.google.inject.Singleton;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.crypto.key.KeyProvider;
import org.apache.hadoop.crypto.key.KeyProviderDelegationTokenExtension;
import org.apache.hadoop.crypto.key.kms.KMSClientProvider;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

@Singleton
public class KmsSecureStore implements SecureStore, SecureStoreManager {

  private final KeyProvider provider;
  private final Configuration conf;

  public KeyProvider getProvider() {
    return provider;
  }

  public KmsSecureStore(Configuration conf) throws IOException, URISyntaxException {
    URI providerUri = new URI("kms://http@146.234.154.104.bc.googleusercontent.com:16000/kms/");
    this.conf = conf;
    provider = KMSClientProvider.Factory.get(providerUri, conf);
  }

  @Override
  public void put(String name, byte[] data, String description, Map<String, String> properties) throws IOException {
    KeyProvider.Options options = new KeyProvider.Options(conf);
    options.setDescription(description);
    options.setAttributes(properties);
    options.setBitLength(data.length * Byte.SIZE);
    provider.createKey(name, data, options);
  }

  @Override
  public void delete(String name) throws IOException {

  }

  @Override
  public List<SecureStoreMetadata> list() throws IOException {
    return null;
  }

  @Override
  public SecureStoreData get(String name) throws IOException {
    return null;
  }

  public static void main(String[] args) {
    /*
        try {
      LOG.warn("nsquare: Before accessing provider.");
      Configuration conf = new Configuration();
      conf.set("hadoop.security.authentication", "kerberos");
      UserGroupInformation.setConfiguration(conf);
      conf.set("hadoop.kms.authentication.token.validity", "1");
      conf.set("hadoop.kms.authentication.type", "kerberos");
      conf.set("hadoop.kms.authentication.kerberos.keytab",
                         "/tmp/yarn.keytab");
      conf.set("hadoop.kms.authentication.kerberos.principal", "yarn");
      conf.set("hadoop.kms.authentication.kerberos.name.rules", "DEFAULT");
      URI providerUri = new URI("kms://http@146.234.154.104.bc.googleusercontent.com:16000/kms/");
      KeyProvider provider = KeyProviderFactory.get(providerUri, conf);
      final KeyProvider.Options options = KeyProvider.options(conf);
      String keyName = "testkey1";
      options.setDescription(keyName);
      options.setBitLength(128);
      provider.createKey(keyName, options);

      provider.flush();
      LOG.warn("nsquare: Before logging the keys.");
      for (String k :provider.getKeys()) {
        LOG.warn("nsquare: " + k);
      }
    } catch (IOException | URISyntaxException | NoSuchAlgorithmException e) {
      LOG.warn("nsquare: " + e.getMessage());
      e.printStackTrace();
    }
     */
  }
}
