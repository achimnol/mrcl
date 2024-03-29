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
package org.apache.hama;

import java.io.IOException;

import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.RegionException;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hama.matrix.DenseMatrix;
import org.apache.hama.matrix.Matrix;
import org.apache.hama.matrix.SparseMatrix;
import org.apache.log4j.Logger;

/**
 * An Implementation of {@link org.apache.hama.HamaAdmin} to manage the matrix's
 * namespace, and table allocation & garbage collection.
 */
public class HamaAdminImpl implements HamaAdmin {
  static final Logger LOG = Logger.getLogger(HamaAdminImpl.class);
  protected HamaConfiguration conf;
  protected HBaseAdmin admin;
  protected HTable table;

  /**
   * Constructor
   * 
   * @param conf
   * @throws MasterNotRunningException
   */
  public HamaAdminImpl(HamaConfiguration conf) throws MasterNotRunningException {
    this.conf = conf;
    this.admin = new HBaseAdmin(conf);
    initialJob();
  }

  /**
   * Constructor
   * 
   * @param conf
   * @param admin
   */
  public HamaAdminImpl(HamaConfiguration conf, HBaseAdmin admin) {
    this.conf = conf;
    this.admin = admin;
    initialJob();
  }

  /**
   * Initializing the admin.
   */
  private void initialJob() {
    try {
      if (!admin.tableExists(Constants.ADMINTABLE)) {
        HTableDescriptor tableDesc = new HTableDescriptor(Constants.ADMINTABLE);
        tableDesc.addFamily(new HColumnDescriptor(Constants.PATHCOLUMN));
        admin.createTable(tableDesc);
      }

      table = new HTable(conf, Constants.ADMINTABLE);
      table.setAutoFlush(true);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * @param name
   * @return real table name
   */
  public String getPath(String name) {
    try {
      Get get = new Get(Bytes.toBytes(name));
      get.addFamily(Bytes.toBytes(Constants.PATHCOLUMN));
      byte[] result = table.get(get).getValue(
          Bytes.toBytes(Constants.PATHCOLUMN), null);
      return Bytes.toString(result);
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  public boolean matrixExists(String matrixName) {
    try {
      Get get = new Get(Bytes.toBytes(matrixName));
      get.addFamily(Bytes.toBytes(Constants.PATHCOLUMN));
      byte[] result = table.get(get).getValue(
          Bytes.toBytes(Constants.PATHCOLUMN), null);

      return (result == null) ? false : true;
    } catch (IOException e) {
      e.printStackTrace();
      return false;
    }
  }

  public boolean save(Matrix mat, String aliaseName) {
    boolean result = false;

    // we just store the name -> path(tablename) here.
    // the matrix type is stored in its hbase table. we don't need to store
    // again.

    Put put = new Put(Bytes.toBytes(aliaseName));
    put.add(Bytes.toBytes(Constants.PATHCOLUMN), null, Bytes.toBytes(mat
        .getPath()));

    try {
      table.put(put);

      result = true;
    } catch (IOException e) {
      e.printStackTrace();
    }

    return result;
  }

  /** remove the entry of 'matrixName' in admin table. * */
  private void removeEntry(String matrixName) throws IOException {
    Delete del = new Delete(Bytes.toBytes(matrixName));
    table.delete(del);
  }

  private int getReference(String tableName) throws IOException {
    HTable matrix = new HTable(conf, tableName);

    Get get = new Get(Bytes.toBytes(Constants.METADATA));
    get.addFamily(Constants.ATTRIBUTE);
    byte[] result = matrix.get(get).getValue(
        Constants.ATTRIBUTE,
        Bytes.toBytes(Constants.METADATA_REFERENCE));

    return (result == null) ? 0 : Bytes.toInt(result);
  }

  private void clearAliaseInfo(String tableName) throws IOException {
    HTable matrix = new HTable(conf, tableName);
    Delete del = new Delete(Bytes.toBytes(Constants.METADATA));
    del.deleteColumns(Bytes.toBytes(Constants.ALIASEFAMILY), Bytes
        .toBytes("name"));
    matrix.delete(del);
  }

  /**
   * we remove the aliase entry store in Admin table, and clear the aliase info
   * store in matrix table. And check the reference of the matrix table:
   * 
   * 1) if the reference of the matrix table is zero: we delete the table. 2) if
   * the reference of the matrix table is not zero: we let the matrix who still
   * reference the table to do the garbage collection.
   */
  public void delete(String matrixName) throws IOException {
    if (matrixExists(matrixName)) {
      String tablename = getPath(matrixName);

      // i) remove the aliase entry first.
      removeEntry(matrixName);

      if (tablename == null) { // a matrixName point to a null table. we delete
        // the entry.
        return;
      }

      if (!admin.tableExists(tablename)) { // have not specified table.
        return;
      }

      // ii) clear the aliase info store in matrix table.
      clearAliaseInfo(tablename);

      if (getReference(tablename) <= 0) { // no reference, do gc!!
        if (admin.isTableEnabled(tablename)) {
          while (admin.isTableEnabled(tablename)) {
            try {
              admin.disableTable(tablename);
            } catch (RegionException e) {
              LOG.warn(e);
            }
          }

          admin.deleteTable(tablename);
        }
      }
    }
  }

  @Override
  public Matrix getMatrix(String matrixName) throws IOException {
    String path = getPath(matrixName);
    if (getType(path).equals("SparseMatrix"))
      return new SparseMatrix(conf, path);
    else
      return new DenseMatrix(conf, path);
  }

  private String getType(String path) {
    try {
      HTable table = new HTable(conf, path);

      Get get = new Get(Bytes.toBytes(Constants.METADATA));
      get.addFamily(Constants.ATTRIBUTE);
      byte[] result = table.get(get).getValue(
          Constants.ATTRIBUTE, Bytes.toBytes("type"));

      return Bytes.toString(result);
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }
}
