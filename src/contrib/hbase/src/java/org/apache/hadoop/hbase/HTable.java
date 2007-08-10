/**
 * Copyright 2007 The Apache Software Foundation
 *
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
package org.apache.hadoop.hbase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.filter.RowFilterInterface;
import org.apache.hadoop.hbase.io.BatchUpdate;
import org.apache.hadoop.hbase.io.KeyedData;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ipc.RemoteException;

/**
 * Used to communicate with a single HBase table
 */
public class HTable implements HConstants {
  protected final Log LOG = LogFactory.getLog(this.getClass().getName());

  protected final HConnection connection;
  protected final Text tableName;
  protected final long pause;
  protected final int numRetries;
  protected Random rand;
  protected volatile SortedMap<Text, HRegionLocation> tableServers;
  protected BatchUpdate batch;
  
  // For row mutation operations
  
  protected volatile long currentLockId;
  protected volatile Text currentRegion;
  protected volatile HRegionInterface currentServer;
  protected volatile long clientid;
  
  protected volatile boolean closed;

  protected void checkClosed() {
    if (closed) {
      throw new IllegalStateException("table is closed");
    }
  }
  
  /**
   * Creates an object to access a HBase table
   * 
   * @param conf configuration object
   * @param tableName name of the table
   * @throws IOException
   */
  public HTable(Configuration conf, Text tableName) throws IOException {
    closed = true;
    this.connection = HConnectionManager.getConnection(conf);
    this.tableName = tableName;
    this.pause = conf.getLong("hbase.client.pause", 30 * 1000);
    this.numRetries = conf.getInt("hbase.client.retries.number", 5);
    this.rand = new Random();
    tableServers = connection.getTableServers(tableName);
    this.batch = null;
    this.currentLockId = -1L;
    closed = false;
  }

  /**
   * Find region location hosting passed row using cached info
   * @param row Row to find.
   * @return Location of row.
   */
  HRegionLocation getRegionLocation(Text row) {
    checkClosed();
    if (this.tableServers == null) {
      throw new IllegalStateException("Must open table first");
    }
    
    // Only one server will have the row we are looking for
    Text serverKey = (this.tableServers.containsKey(row)) ?
        row : this.tableServers.headMap(row).lastKey();
    return this.tableServers.get(serverKey);
  }

  /** @return the connection */
  public HConnection getConnection() {
    checkClosed();
    return connection;
  }

  /**
   * Releases resources associated with this table. After calling close(), all
   * other methods will throw an IllegalStateException
   */
  public synchronized void close() {
    closed = true;
    tableServers = null;
    batch = null;
    currentLockId = -1L;
    currentRegion = null;
    currentServer = null;
    clientid = -1L;
    connection.close(tableName);
  }
  
  /**
   * Verifies that no update is in progress
   */
  public synchronized void checkUpdateInProgress() {
    if (batch != null || currentLockId != -1L) {
      throw new IllegalStateException("update in progress");
    }
  }
  

  /** @return the table name */
  public Text getTableName() {
    return this.tableName;
  }

  /**
   * Gets the starting row key for every region in the currently open table
   * @return Array of region starting row keys
   */
  public Text[] getStartKeys() {
    checkClosed();
    Text[] keys = new Text[tableServers.size()];
    int i = 0;
    for(Text key: tableServers.keySet()){
      keys[i++] = key;
    }
    return keys;
  }
  
  /** 
   * Get a single value for the specified row and column
   *
   * @param row row key
   * @param column column name
   * @return value for specified row/column
   * @throws IOException
   */
  public byte[] get(Text row, Text column) throws IOException {
    checkClosed();
    byte [] value = null;
    for(int tries = 0; tries < numRetries; tries++) {
      HRegionLocation r = getRegionLocation(row);
      HRegionInterface server =
        connection.getHRegionConnection(r.getServerAddress());
      
      try {
        value = server.get(r.getRegionInfo().getRegionName(), row, column);
        break;
        
      } catch (IOException e) {
        if (tries == numRetries - 1) {
          if (e instanceof RemoteException) {
            e = RemoteExceptionHandler.decodeRemoteException((RemoteException) e);
          }
          throw e;
        }
        tableServers = connection.reloadTableServers(tableName);
      }
      try {
        Thread.sleep(this.pause);
        
      } catch (InterruptedException x) {
        // continue
      }
    }
    return value;
  }
 
  /** 
   * Get the specified number of versions of the specified row and column
   * 
   * @param row         - row key
   * @param column      - column name
   * @param numVersions - number of versions to retrieve
   * @return            - array byte values
   * @throws IOException
   */
  public byte[][] get(Text row, Text column, int numVersions) throws IOException {
    checkClosed();
    byte [][] values = null;
    for (int tries = 0; tries < numRetries; tries++) {
      HRegionLocation r = getRegionLocation(row);
      HRegionInterface server = 
        connection.getHRegionConnection(r.getServerAddress());
      
      try {
        values = server.get(r.getRegionInfo().getRegionName(), row, column,
            numVersions);
        
        break;
        
      } catch (IOException e) {
        if (tries == numRetries - 1) {
          // No more tries
          if (e instanceof RemoteException) {
            e = RemoteExceptionHandler.decodeRemoteException((RemoteException) e);
          }
          throw e;
        }
        tableServers = connection.reloadTableServers(tableName);
      }
      try {
        Thread.sleep(this.pause);
        
      } catch (InterruptedException x) {
        // continue
      }
    }

    if (values != null) {
      ArrayList<byte[]> bytes = new ArrayList<byte[]>();
      for (int i = 0 ; i < values.length; i++) {
        bytes.add(values[i]);
      }
      return bytes.toArray(new byte[values.length][]);
    }
    return null;
  }
  
  /** 
   * Get the specified number of versions of the specified row and column with
   * the specified timestamp.
   *
   * @param row         - row key
   * @param column      - column name
   * @param timestamp   - timestamp
   * @param numVersions - number of versions to retrieve
   * @return            - array of values that match the above criteria
   * @throws IOException
   */
  public byte[][] get(Text row, Text column, long timestamp, int numVersions)
  throws IOException {
    checkClosed();
    byte [][] values = null;
    for (int tries = 0; tries < numRetries; tries++) {
      HRegionLocation r = getRegionLocation(row);
      HRegionInterface server =
        connection.getHRegionConnection(r.getServerAddress());
      
      try {
        values = server.get(r.getRegionInfo().getRegionName(), row, column,
            timestamp, numVersions);
        
        break;
    
      } catch (IOException e) {
        if (tries == numRetries - 1) {
          // No more tries
          if (e instanceof RemoteException) {
            e = RemoteExceptionHandler.decodeRemoteException((RemoteException) e);
          }
          throw e;
        }
        tableServers = connection.reloadTableServers(tableName);
      }
      try {
        Thread.sleep(this.pause);
        
      } catch (InterruptedException x) {
        // continue
      }
    }

    if (values != null) {
      ArrayList<byte[]> bytes = new ArrayList<byte[]>();
      for (int i = 0 ; i < values.length; i++) {
        bytes.add(values[i]);
      }
      return bytes.toArray(new byte[values.length][]);
    }
    return null;
  }
    
  /** 
   * Get all the data for the specified row
   * 
   * @param row         - row key
   * @return            - map of colums to values
   * @throws IOException
   */
  public SortedMap<Text, byte[]> getRow(Text row) throws IOException {
    checkClosed();
    KeyedData[] value = null;
    for (int tries = 0; tries < numRetries; tries++) {
      HRegionLocation r = getRegionLocation(row);
      HRegionInterface server =
        connection.getHRegionConnection(r.getServerAddress());
      
      try {
        value = server.getRow(r.getRegionInfo().getRegionName(), row);
        break;
        
      } catch (IOException e) {
        if (tries == numRetries - 1) {
          // No more tries
          if (e instanceof RemoteException) {
            e = RemoteExceptionHandler.decodeRemoteException((RemoteException) e);
          }
          throw e;
        }
        tableServers = connection.reloadTableServers(tableName);
      }
      try {
        Thread.sleep(this.pause);
        
      } catch (InterruptedException x) {
        // continue
      }
    }
    TreeMap<Text, byte[]> results = new TreeMap<Text, byte[]>();
    if (value != null && value.length != 0) {
      for (int i = 0; i < value.length; i++) {
        results.put(value[i].getKey().getColumn(), value[i].getData());
      }
    }
    return results;
  }

  /** 
   * Get a scanner on the current table starting at the specified row.
   * Return the specified columns.
   *
   * @param columns array of columns to return
   * @param startRow starting row in table to scan
   * @return scanner
   * @throws IOException
   */
  public HScannerInterface obtainScanner(Text[] columns,
      Text startRow) throws IOException {
    
    return obtainScanner(columns, startRow, System.currentTimeMillis(), null);
  }
  
  /** 
   * Get a scanner on the current table starting at the specified row.
   * Return the specified columns.
   *
   * @param columns array of columns to return
   * @param startRow starting row in table to scan
   * @param timestamp only return results whose timestamp <= this value
   * @return scanner
   * @throws IOException
   */
  public HScannerInterface obtainScanner(Text[] columns,
      Text startRow, long timestamp) throws IOException {
    
    return obtainScanner(columns, startRow, timestamp, null);
  }
  
  /** 
   * Get a scanner on the current table starting at the specified row.
   * Return the specified columns.
   *
   * @param columns array of columns to return
   * @param startRow starting row in table to scan
   * @param filter a row filter using row-key regexp and/or column data filter.
   * @return scanner
   * @throws IOException
   */
  public HScannerInterface obtainScanner(Text[] columns,
      Text startRow, RowFilterInterface filter) throws IOException { 
    
    return obtainScanner(columns, startRow, System.currentTimeMillis(), filter);
  }
  
  /** 
   * Get a scanner on the current table starting at the specified row.
   * Return the specified columns.
   *
   * @param columns array of columns to return
   * @param startRow starting row in table to scan
   * @param timestamp only return results whose timestamp <= this value
   * @param filter a row filter using row-key regexp and/or column data filter.
   * @return scanner
   * @throws IOException
   */
  public HScannerInterface obtainScanner(Text[] columns,
      Text startRow, long timestamp, RowFilterInterface filter)
  throws IOException {
    
    checkClosed();
    return new ClientScanner(columns, startRow, timestamp, filter);
  }

  /** 
   * Start a batch of row insertions/updates.
   * 
   * No changes are committed until the call to commitBatchUpdate returns.
   * A call to abortBatchUpdate will abandon the entire batch.
   *
   * @param row name of row to be updated
   * @return lockid to be used in subsequent put, delete and commit calls
   */
  public synchronized long startBatchUpdate(final Text row) {
    checkClosed();
    checkUpdateInProgress();
    batch = new BatchUpdate();
    return batch.startUpdate(row);
  }
  
  /** 
   * Abort a batch mutation
   * @param lockid lock id returned by startBatchUpdate
   */
  public synchronized void abortBatch(final long lockid) {
    checkClosed();
    if (batch == null) {
      throw new IllegalStateException("no batch update in progress");
    }
    if (batch.getLockid() != lockid) {
      throw new IllegalArgumentException("invalid lock id " + lockid);
    }
    batch = null;
  }
  
  /** 
   * Finalize a batch mutation
   *
   * @param lockid lock id returned by startBatchUpdate
   * @throws IOException
   */
  public void commitBatch(final long lockid) throws IOException {
    commitBatch(lockid, System.currentTimeMillis());
  }

  /** 
   * Finalize a batch mutation
   *
   * @param lockid lock id returned by startBatchUpdate
   * @param timestamp time to associate with all the changes
   * @throws IOException
   */
  public synchronized void commitBatch(final long lockid, final long timestamp)
  throws IOException {

    checkClosed();
    if (batch == null) {
      throw new IllegalStateException("no batch update in progress");
    }
    if (batch.getLockid() != lockid) {
      throw new IllegalArgumentException("invalid lock id " + lockid);
    }
    
    try {
      for (int tries = 0; tries < numRetries; tries++) {
        HRegionLocation r = getRegionLocation(batch.getRow());
        HRegionInterface server =
          connection.getHRegionConnection(r.getServerAddress());

        try {
          server.batchUpdate(r.getRegionInfo().getRegionName(), timestamp, batch);
          break;

        } catch (IOException e) {
          if (tries < numRetries -1) {
            tableServers = connection.reloadTableServers(tableName);

          } else {
            if (e instanceof RemoteException) {
              e = RemoteExceptionHandler.decodeRemoteException((RemoteException) e);
            }
            throw e;
          }
        }
        try {
          Thread.sleep(pause);

        } catch (InterruptedException e) {
        }
      }
    } finally {
      batch = null;
    }
  }
  
  /** 
   * Start an atomic row insertion/update.  No changes are committed until the 
   * call to commit() returns. A call to abort() will abandon any updates in progress.
   *
   * Callers to this method are given a lease for each unique lockid; before the
   * lease expires, either abort() or commit() must be called. If it is not 
   * called, the system will automatically call abort() on the client's behalf.
   *
   * The client can gain extra time with a call to renewLease().
   * Start an atomic row insertion or update
   * 
   * @param row Name of row to start update against.
   * @return Row lockid.
   * @throws IOException
   */
  public synchronized long startUpdate(final Text row) throws IOException {
    checkClosed();
    checkUpdateInProgress();
    for (int tries = 0; tries < numRetries; tries++) {
      IOException e = null;
      HRegionLocation info = getRegionLocation(row);
      try {
        currentServer =
          connection.getHRegionConnection(info.getServerAddress());

        currentRegion = info.getRegionInfo().getRegionName();
        clientid = rand.nextLong();
        currentLockId = currentServer.startUpdate(currentRegion, clientid, row);

        break;

      } catch (IOException ex) {
        e = ex;
      }
      if (tries < numRetries - 1) {
        try {
          Thread.sleep(this.pause);

        } catch (InterruptedException ex) {
        }
        try {
          tableServers = connection.reloadTableServers(tableName);

        } catch (IOException ex) {
          e = ex;
        }
      } else {
        if (e instanceof RemoteException) {
          e = RemoteExceptionHandler.decodeRemoteException((RemoteException) e);
        }
        throw e;
      }
    }
    return currentLockId;
  }
  
  /** 
   * Change a value for the specified column.
   * Runs {@link #abort(long)} if exception thrown.
   *
   * @param lockid lock id returned from startUpdate
   * @param column column whose value is being set
   * @param val new value for column
   * @throws IOException
   */
  public void put(long lockid, Text column, byte val[]) throws IOException {
    checkClosed();
    if (val == null) {
      throw new IllegalArgumentException("value cannot be null");
    }
    if (batch != null) {
      batch.put(lockid, column, val);
      return;
    }
    
    if (lockid != currentLockId) {
      throw new IllegalArgumentException("invalid lockid");
    }
    try {
      this.currentServer.put(this.currentRegion, this.clientid, lockid, column,
        val);
    } catch (IOException e) {
      try {
        this.currentServer.abort(this.currentRegion, this.clientid, lockid);
      } catch (IOException e2) {
        LOG.warn(e2);
      }
      this.currentServer = null;
      this.currentRegion = null;
      if (e instanceof RemoteException) {
        e = RemoteExceptionHandler.decodeRemoteException((RemoteException) e);
      }
      throw e;
    }
  }
  
  /** 
   * Delete the value for a column
   *
   * @param lockid              - lock id returned from startUpdate
   * @param column              - name of column whose value is to be deleted
   * @throws IOException
   */
  public void delete(long lockid, Text column) throws IOException {
    checkClosed();
    if (batch != null) {
      batch.delete(lockid, column);
      return;
    }
    
    if (lockid != currentLockId) {
      throw new IllegalArgumentException("invalid lockid");
    }
    try {
      this.currentServer.delete(this.currentRegion, this.clientid, lockid,
        column);
    } catch (IOException e) {
      try {
        this.currentServer.abort(this.currentRegion, this.clientid, lockid);
      } catch(IOException e2) {
        LOG.warn(e2);
      }
      this.currentServer = null;
      this.currentRegion = null;
      if (e instanceof RemoteException) {
        e = RemoteExceptionHandler.decodeRemoteException((RemoteException) e);
      }
      throw e;
    }
  }
  
  /** 
   * Abort a row mutation
   *
   * @param lockid              - lock id returned from startUpdate
   * @throws IOException
   */
  public synchronized void abort(long lockid) throws IOException {
    checkClosed();
    if (batch != null) {
      abortBatch(lockid);
      return;
    }

    if (lockid != currentLockId) {
      throw new IllegalArgumentException("invalid lockid");
    }
    
    try {
      try {
        this.currentServer.abort(this.currentRegion, this.clientid, lockid);
      } catch (IOException e) {
        this.currentServer = null;
        this.currentRegion = null;
        if (e instanceof RemoteException) {
          e = RemoteExceptionHandler.decodeRemoteException((RemoteException) e);
        }
        throw e;
      }
    } finally {
      currentLockId = -1L;
    }
  }
  
  /** 
   * Finalize a row mutation
   *
   * @param lockid              - lock id returned from startUpdate
   * @throws IOException
   */
  public void commit(long lockid) throws IOException {
    commit(lockid, System.currentTimeMillis());
  }

  /** 
   * Finalize a row mutation
   *
   * @param lockid              - lock id returned from startUpdate
   * @param timestamp           - time to associate with the change
   * @throws IOException
   */
  public synchronized void commit(long lockid, long timestamp) throws IOException {
    checkClosed();
    if (batch != null) {
      commitBatch(lockid, timestamp);
      return;
    }

    if (lockid != currentLockId) {
      throw new IllegalArgumentException("invalid lockid");
    }

    try {
      try {
        this.currentServer.commit(this.currentRegion, this.clientid, lockid,
            timestamp);

      } catch (IOException e) {
        this.currentServer = null;
        this.currentRegion = null;
        if(e instanceof RemoteException) {
          e = RemoteExceptionHandler.decodeRemoteException((RemoteException) e);
        }
        throw e;
      }
    } finally {
      currentLockId = -1L;
    }
  }
  
  /**
   * Renew lease on update
   * 
   * @param lockid              - lock id returned from startUpdate
   * @throws IOException
   */
  public synchronized void renewLease(long lockid) throws IOException {
    checkClosed();
    if (batch != null) {
      return;
    }

    if (lockid != currentLockId) {
      throw new IllegalArgumentException("invalid lockid");
    }
    try {
      this.currentServer.renewLease(lockid, this.clientid);
    } catch (IOException e) {
      try {
        this.currentServer.abort(this.currentRegion, this.clientid, lockid);
      } catch (IOException e2) {
        LOG.warn(e2);
      }
      this.currentServer = null;
      this.currentRegion = null;
      if (e instanceof RemoteException) {
        e = RemoteExceptionHandler.decodeRemoteException((RemoteException) e);
      }
      throw e;
    }
  }

  /**
   * Implements the scanner interface for the HBase client.
   * If there are multiple regions in a table, this scanner will iterate
   * through them all.
   */
  protected class ClientScanner implements HScannerInterface {
    private final Text EMPTY_COLUMN = new Text();
    private Text[] columns;
    private Text startRow;
    private long scanTime;
    @SuppressWarnings("hiding")
    private boolean closed;
    private AtomicReferenceArray<HRegionLocation> regions;
    @SuppressWarnings("hiding")
    private int currentRegion;
    private HRegionInterface server;
    private long scannerId;
    private RowFilterInterface filter;
    
    private void loadRegions() {
      checkClosed();
      Text firstServer = null;
      if (this.startRow == null || this.startRow.getLength() == 0) {
        firstServer = tableServers.firstKey();

      } else if(tableServers.containsKey(startRow)) {
        firstServer = startRow;

      } else {
        firstServer = tableServers.headMap(startRow).lastKey();
      }
      Collection<HRegionLocation> info =
        tableServers.tailMap(firstServer).values();
      
      this.regions = new AtomicReferenceArray<HRegionLocation>(
          info.toArray(new HRegionLocation[info.size()]));
    }
    
    protected ClientScanner(Text[] columns, Text startRow, long timestamp,
        RowFilterInterface filter) throws IOException {
      
      this.columns = columns;
      this.startRow = startRow;
      this.scanTime = timestamp;
      this.closed = false;
      this.filter = filter;
      if (filter != null) {
        filter.validate(columns);
      }
      loadRegions();
      this.currentRegion = -1;
      this.server = null;
      this.scannerId = -1L;
      nextScanner();
    }
    
    /*
     * Gets a scanner for the next region.
     * Returns false if there are no more scanners.
     */
    private boolean nextScanner() throws IOException {
      checkClosed();
      if (this.scannerId != -1L) {
        this.server.close(this.scannerId);
        this.scannerId = -1L;
      }
      this.currentRegion += 1;
      if (this.currentRegion == this.regions.length()) {
        close();
        return false;
      }
      try {
        for (int tries = 0; tries < numRetries; tries++) {
          HRegionLocation r = this.regions.get(currentRegion);
          this.server =
            connection.getHRegionConnection(r.getServerAddress());
          
          try {
            if (this.filter == null) {
              this.scannerId =
                this.server.openScanner(r.getRegionInfo().getRegionName(),
                    this.columns, currentRegion == 0 ? this.startRow
                        : EMPTY_START_ROW, scanTime, null);
              
            } else {
              this.scannerId =
                this.server.openScanner(r.getRegionInfo().getRegionName(),
                    this.columns, currentRegion == 0 ? this.startRow
                        : EMPTY_START_ROW, scanTime, filter);
            }

            break;
        
          } catch (IOException e) {
            if (tries == numRetries - 1) {
              // No more tries
              if (e instanceof RemoteException) {
                e = RemoteExceptionHandler.decodeRemoteException((RemoteException) e);
              }
              throw e;
            }
            tableServers = connection.reloadTableServers(tableName);
            loadRegions();
          }
        }

      } catch (IOException e) {
        close();
        if (e instanceof RemoteException) {
          e = RemoteExceptionHandler.decodeRemoteException((RemoteException) e);
        }
        throw e;
      }
      return true;
    }
    
    /**
     * {@inheritDoc}
     */
    public boolean next(HStoreKey key, TreeMap<Text, byte[]> results) throws IOException {
      checkClosed();
      if (this.closed) {
        return false;
      }
      KeyedData[] values = null;
      do {
        values = this.server.next(this.scannerId);
      } while (values != null && values.length == 0 && nextScanner());

      if (values != null && values.length != 0) {
        for (int i = 0; i < values.length; i++) {
          key.setRow(values[i].getKey().getRow());
          key.setVersion(values[i].getKey().getTimestamp());
          key.setColumn(EMPTY_COLUMN);
          results.put(values[i].getKey().getColumn(), values[i].getData());
        }
      }
      return values == null ? false : values.length != 0;
    }

    /**
     * {@inheritDoc}
     */
    public void close() throws IOException {
      checkClosed();
      if (this.scannerId != -1L) {
        this.server.close(this.scannerId);
        this.scannerId = -1L;
      }
      this.server = null;
      this.closed = true;
    }
  }
}