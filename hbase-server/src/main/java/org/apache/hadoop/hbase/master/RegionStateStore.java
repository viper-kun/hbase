/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.catalog.CatalogTracker;
import org.apache.hadoop.hbase.catalog.MetaEditor;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.master.RegionState.State;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.RegionServerServices;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.ConfigUtil;

import com.google.common.base.Preconditions;

/**
 * A helper to persist region state in meta. We may change this class
 * to StateStore later if we also use it to store other states in meta
 */
@InterfaceAudience.Private
public class RegionStateStore {
  private static final Log LOG = LogFactory.getLog(RegionStateStore.class);

  private volatile HRegion metaRegion;
  private volatile HTableInterface metaTable;
  private volatile boolean initialized;

  private final boolean noPersistence;
  private final CatalogTracker catalogTracker;
  private final Server server;

  /**
   * Returns the {@link ServerName} from catalog table {@link Result}
   * where the region is transitioning. It should be the same as
   * {@link HRegionInfo#getServerName(Result)} if the server is at OPEN state.
   * @param r Result to pull the transitioning server name from
   * @return A ServerName instance or {@link HRegionInfo#getServerName(Result)}
   * if necessary fields not found or empty.
   */
  static ServerName getRegionServer(final Result r) {
    Cell cell = r.getColumnLatestCell(HConstants.CATALOG_FAMILY, HConstants.SERVERNAME_QUALIFIER);
    if (cell == null || cell.getValueLength() == 0) return HRegionInfo.getServerName(r);
    return ServerName.parseServerName(Bytes.toString(cell.getValueArray(),
      cell.getValueOffset(), cell.getValueLength()));
  }

  /**
   * Pull the region state from a catalog table {@link Result}.
   * @param r Result to pull the region state from
   * @return the region state, or OPEN if there's no value written.
   */
  static State getRegionState(final Result r) {
    Cell cell = r.getColumnLatestCell(HConstants.CATALOG_FAMILY, HConstants.STATE_QUALIFIER);
    if (cell == null || cell.getValueLength() == 0) return State.OPEN;
    return State.valueOf(Bytes.toString(cell.getValueArray(),
      cell.getValueOffset(), cell.getValueLength()));
  }

  /**
   * Check if we should persist a state change in meta. Generally it's
   * better to persist all state changes. However, we should not do that
   * if the region is not in meta at all. Based on the state and the
   * previous state, we can identify if a user region has an entry
   * in meta. For example, merged regions are deleted from meta;
   * New merging parents, or splitting daughters are
   * not created in meta yet.
   */
  private boolean shouldPersistStateChange(
      HRegionInfo hri, RegionState state, RegionState oldState) {
    return !hri.isMetaRegion() && !RegionStates.isOneOfStates(
      state, State.MERGING_NEW, State.SPLITTING_NEW, State.MERGED)
      && !(RegionStates.isOneOfStates(state, State.OFFLINE)
        && RegionStates.isOneOfStates(oldState, State.MERGING_NEW,
          State.SPLITTING_NEW, State.MERGED));
  }

  RegionStateStore(final Server server) {
    Configuration conf = server.getConfiguration();
    // No need to persist if using ZK but not migrating
    noPersistence = ConfigUtil.useZKForAssignment(conf)
      && !conf.getBoolean("hbase.assignment.usezk.migrating", false);
    catalogTracker = server.getCatalogTracker();
    this.server = server;
    initialized = false;
  }

  @SuppressWarnings("deprecation")
  void start() throws IOException {
    if (!noPersistence) {
      if (server instanceof RegionServerServices) {
        metaRegion = ((RegionServerServices)server).getFromOnlineRegions(
          HRegionInfo.FIRST_META_REGIONINFO.getEncodedName());
      }
      if (metaRegion == null) {
        metaTable = new HTable(TableName.META_TABLE_NAME,
          catalogTracker.getConnection());
      }
    }
    initialized = true;
  }

  void stop() {
    initialized = false;
    if (metaTable != null) {
      try {
        metaTable.close();
      } catch (IOException e) {
        LOG.info("Got exception in closing meta table", e);
      } finally {
        metaTable = null;
      }
    }
  }

  @SuppressWarnings("deprecation")
  void updateRegionState(long openSeqNum,
      RegionState newState, RegionState oldState) {
    if (noPersistence || !initialized) {
      return;
    }

    HRegionInfo hri = newState.getRegion();
    if (!shouldPersistStateChange(hri, newState, oldState)) {
      return;
    }

    ServerName oldServer = oldState != null ? oldState.getServerName() : null;
    ServerName serverName = newState.getServerName();
    State state = newState.getState();

    try {
      Put put = new Put(hri.getRegionName());
      StringBuilder info = new StringBuilder("Updating row ");
      info.append(hri.getRegionNameAsString()).append(" with state=").append(state);
      if (serverName != null && !serverName.equals(oldServer)) {
        put.addImmutable(HConstants.CATALOG_FAMILY, HConstants.SERVERNAME_QUALIFIER,
          Bytes.toBytes(serverName.getServerName()));
        info.append("&sn=").append(serverName);
      }
      if (openSeqNum >= 0) {
        Preconditions.checkArgument(state == State.OPEN
          && serverName != null, "Open region should be on a server");
        put.addImmutable(HConstants.CATALOG_FAMILY, HConstants.SERVER_QUALIFIER,
          Bytes.toBytes(serverName.getHostAndPort()));
        put.addImmutable(HConstants.CATALOG_FAMILY, HConstants.STARTCODE_QUALIFIER,
          Bytes.toBytes(serverName.getStartcode()));
        put.addImmutable(HConstants.CATALOG_FAMILY, HConstants.SEQNUM_QUALIFIER,
          Bytes.toBytes(openSeqNum));
        info.append("&openSeqNum=").append(openSeqNum);
        info.append("&server=").append(serverName);
      }
      put.addImmutable(HConstants.CATALOG_FAMILY, HConstants.STATE_QUALIFIER,
        Bytes.toBytes(state.name()));
      LOG.info(info);

      // Persist the state change to meta
      if (metaRegion != null) {
        try {
          // Assume meta is pinned to master.
          // At least, that's what we want.
          metaRegion.put(put);
          return; // Done here
        } catch (Throwable t) {
          // In unit tests, meta could be moved away by intention
          // So, the shortcut is gone. We won't try to establish the
          // shortcut any more because we prefer meta to be pinned
          // to the master
          synchronized (this) {
            if (metaRegion != null) {
              LOG.info("Meta region shortcut failed", t);
              metaTable = new HTable(TableName.META_TABLE_NAME,
                catalogTracker.getConnection());
              metaRegion = null;
            }
          }
        }
      }
      synchronized(metaTable) {
        metaTable.put(put);
      }
    } catch (IOException ioe) {
      LOG.error("Failed to persist region state " + newState, ioe);
      server.abort("Failed to update region location", ioe);
    }
  }

  void splitRegion(HRegionInfo p,
      HRegionInfo a, HRegionInfo b, ServerName sn) throws IOException {
    MetaEditor.splitRegion(catalogTracker, p, a, b, sn);
  }

  void mergeRegions(HRegionInfo p,
      HRegionInfo a, HRegionInfo b, ServerName sn) throws IOException {
    MetaEditor.mergeRegions(catalogTracker, p, a, b, sn);
  }
}
