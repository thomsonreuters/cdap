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

package co.cask.cdap.data2.datafabric.dataset.service.executor;

import co.cask.cdap.api.dataset.DatasetAdmin;
import co.cask.cdap.data2.security.ImpersonationUtils;
import com.google.common.base.Throwables;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.concurrent.Callable;

/**
 * A {link DatasetAdmin} that executes operations as a given {@link UserGroupInformation}.
 */
class ImpersonatingDatasetAdmin implements DatasetAdmin {

  private static final Logger LOG = LoggerFactory.getLogger(ImpersonatingDatasetAdmin.class);

  private final DatasetAdmin delegate;
  private final UserGroupInformation ugi;

  ImpersonatingDatasetAdmin(DatasetAdmin delegate, UserGroupInformation ugi) {
    this.delegate = delegate;
    this.ugi = ugi;
  }

  @Override
  public boolean exists() throws IOException {
    return execute(new Callable<Boolean>() {
      @Override
      public Boolean call() throws Exception {
        return delegate.exists();
      }
    });
  }

  @Override
  public void create() throws IOException {
    execute(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        delegate.create();
        return null;
      }
    });
  }

  @Override
  public void drop() throws IOException {
    execute(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        delegate.drop();
        return null;
      }
    });
  }

  @Override
  public void truncate() throws IOException {
    execute(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        delegate.truncate();
        return null;
      }
    });
  }

  @Override
  public void upgrade() throws IOException {
    execute(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        delegate.upgrade();
        return null;
      }
    });
  }

  @Override
  public void close() throws IOException {
    execute(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        delegate.close();
        return null;
      }
    });
  }

  // helper method to execute a callable, while declaring only IOException as being thrown
  private <T> T execute(final Callable<T> callable) throws IOException {
    try {
      return ImpersonationUtils.doAs(ugi, callable);
    } catch (IOException ioe) {
      throw ioe;
    } catch (Throwable t) {
      if (!(t instanceof RuntimeException)) {
        // since the callables we execute only throw IOException (besides runtime exception), this should never happen
        LOG.warn("Unexpected exception while executing dataset admin operation as {}.", ugi.getUserName(),  t);
      }
      // the only checked exception that the Callables in this class is IOException, and we handle that in the previous
      // catch statement. So, no checked exceptions should be wrapped by the following statement. However, we need it
      // because ImpersonationUtils#doAs declares 'throws Throwable', because it can throw other checked exceptions
      // in the general case
      throw Throwables.propagate(t);
    }
  }
}
