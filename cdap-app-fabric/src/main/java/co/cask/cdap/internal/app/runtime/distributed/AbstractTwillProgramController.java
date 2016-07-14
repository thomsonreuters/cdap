/*
 * Copyright © 2014 Cask Data, Inc.
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
package co.cask.cdap.internal.app.runtime.distributed;

import co.cask.cdap.app.runtime.ProgramController;
import co.cask.cdap.internal.app.runtime.AbstractProgramController;
import co.cask.cdap.kms.KmsSecureStore;
import co.cask.cdap.proto.Id;
import com.google.common.util.concurrent.Futures;
import org.apache.commons.io.Charsets;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.crypto.key.KeyProvider;
import org.apache.hadoop.crypto.key.KeyProviderFactory;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.twill.api.RunId;
import org.apache.twill.api.TwillController;
import org.apache.twill.common.Threads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * A {@link ProgramController} that control program through twill.
 */
public abstract class AbstractTwillProgramController extends AbstractProgramController implements ProgramController {

  private static final Logger LOG = LoggerFactory.getLogger(AbstractTwillProgramController.class);

  protected final Id.Program programId;
  private final TwillController twillController;
  private volatile boolean stopRequested;

  protected AbstractTwillProgramController(Id.Program programId, TwillController twillController, RunId runId) {
    super(programId, runId);
    this.programId = programId;
    this.twillController = twillController;
    kms();
  }

  private void kms() {
    LOG.warn("nsquare: Before adding key.");
    Configuration conf = new Configuration();
    long time = System.currentTimeMillis();
    String key1 = String.valueOf(time);
    LOG.warn("nsquare: Key: " + time);
    String value1 = "value";
    String description1 = "This is the first key.";
    Map<String, String> properties1 = new HashMap<>();
    LOG.warn("nsquare: Before try.");
    try {
      KmsSecureStore kmsSecureStore = new KmsSecureStore(conf);
      LOG.warn("nsquare: Initialized the store.");
      conf.set("hadoop.security.authentication", "kerberos");
      conf.set("hadoop.kms.authentication.token.validity", "1");
      conf.set("hadoop.kms.authentication.type", "kerberos");
      conf.set("hadoop.kms.authentication.kerberos.principal", "cdap");
      conf.set("hadoop.kms.authentication.kerberos.name.rules", "DEFAULT");
      UserGroupInformation.setConfiguration(conf);
      LOG.warn("nsquare: Set the config.");
      kmsSecureStore.put(key1, value1.getBytes(Charsets.UTF_8), description1, properties1);
      LOG.warn("nsquare: Put the key.");
      kmsSecureStore.getProvider().flush();
      LOG.warn("nsquare: After flush.");
    } catch (IOException | URISyntaxException e) {
      e.printStackTrace();
    }
  }
  /**
   * Get the RunId associated with the Twill controller.
   * @return the Twill RunId
   */
  public RunId getTwillRunId() {
    return twillController.getRunId();
  }

  /**
   * Starts listening to TwillController state changes. For internal use only.
   * The listener cannot be binded in constructor to avoid reference leak.
   *
   * @return this instance.
   */
  public ProgramController startListen() {
    twillController.onRunning(new Runnable() {
      @Override
      public void run() {
        LOG.info("Twill program running: {} {}", programId, twillController.getRunId());
        started();
      }
    }, Threads.SAME_THREAD_EXECUTOR);

    twillController.onTerminated(new Runnable() {
      @Override
      public void run() {
        LOG.info("Twill program terminated: {} {}", programId, twillController.getRunId());
        if (stopRequested) {
          // Service was killed
          stop();
        } else {
          try {
            // This never blocks since the twill controller is already terminated. It will throw exception if
            // the twill program failed.
            twillController.awaitTerminated();
            // Service completed by itself. Simply signal the state change of this controller.
            complete();
          } catch (Exception e) {
            error(e);
          }
        }
      }
    }, Threads.SAME_THREAD_EXECUTOR);
    return this;
  }

  @Override
  protected final void doSuspend() throws Exception {
    twillController.sendCommand(ProgramCommands.SUSPEND).get();
  }

  @Override
  protected final void doResume() throws Exception {
    twillController.sendCommand(ProgramCommands.RESUME).get();
  }

  @Override
  protected final void doStop() throws Exception {
    stopRequested = true;
    Futures.getUnchecked(twillController.terminate());
  }
}
