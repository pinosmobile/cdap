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

package co.cask.cdap.common.zookeeper;

import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Service;
import org.apache.twill.zookeeper.ZKClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Util class used to refactor ZooKeeper functions.
 */
public class ZooKeeperUtils {

  private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperUtils.class);

  private ZooKeeperUtils(){}

  public static void connectWithTimeout(ZKClientService zkClientService, CConfiguration cConf) throws Exception {
    ListenableFuture<Service.State> startFunction = zkClientService.start();
    try {
      startFunction.get(cConf.getLong(Constants.Zookeeper.CFG_CLIENT_TIMEOUT_MILLIS), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      LOG.error("Connection timed out while trying to start ZooKeeper client. Please verify that the ZooKeeper " +
                  "quorum settings are correct. Currently configured as: {}", cConf.get(Constants.Zookeeper.QUORUM), e);
      throw e;
    } catch (InterruptedException e) {
      LOG.error("Interrupted while waiting to start ZooKeeper client.", e);
      throw e;
    } catch (ExecutionException e) {
      LOG.error("Exception while trying to start ZooKeeper client.", e);
      throw e;
    }
  }
}
