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
package org.apache.hadoop.hbase.io;
import org.apache.hadoop.hbase.HStoreKey;
import org.apache.hadoop.io.*;

import java.io.*;

/*******************************************************************************
 * KeyedData is just a data pair.
 * It includes an HStoreKey and some associated data.
 ******************************************************************************/
public class KeyedData implements Writable {
  HStoreKey key;
  byte [] data;

  /** Default constructor. Used by Writable interface */
  public KeyedData() {
    this.key = new HStoreKey();
  }

  /**
   * Create a KeyedData object specifying the parts
   * @param key HStoreKey
   * @param data
   */
  public KeyedData(HStoreKey key, byte [] data) {
    this.key = key;
    this.data = data;
  }

  /** @return returns the key */
  public HStoreKey getKey() {
    return key;
  }

  /** @return - returns the value */
  public byte [] getData() {
    return data;
  }

  //////////////////////////////////////////////////////////////////////////////
  // Writable
  //////////////////////////////////////////////////////////////////////////////

  /* (non-Javadoc)
   * @see org.apache.hadoop.io.Writable#write(java.io.DataOutput)
   */
  public void write(DataOutput out) throws IOException {
    key.write(out);
    out.writeInt(this.data.length);
    out.write(this.data);
  }
  
  /* (non-Javadoc)
   * @see org.apache.hadoop.io.Writable#readFields(java.io.DataInput)
   */
  public void readFields(DataInput in) throws IOException {
    key.readFields(in);
    this.data = new byte[in.readInt()];
    in.readFully(this.data);
  }
}