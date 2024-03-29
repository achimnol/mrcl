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
package org.apache.hama.matrix;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.IdentityTableReducer;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.mapreduce.TableOutputFormat;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hama.Constants;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.io.BlockID;
import org.apache.hama.io.Pair;
import org.apache.hama.io.VectorUpdate;
import org.apache.hama.mapreduce.CollectBlocksMapper;
import org.apache.hama.mapreduce.DummyMapper;
import org.apache.hama.mapreduce.PivotInputFormat;
import org.apache.hama.mapreduce.RandomMatrixMapper;
import org.apache.hama.mapreduce.RandomMatrixReducer;
import org.apache.hama.mapreduce.RotationInputFormat;
import org.apache.hama.matrix.algebra.BlockMultMap;
import org.apache.hama.matrix.algebra.BlockMultReduce;
import org.apache.hama.matrix.algebra.DenseMatrixVectorMultMap;
import org.apache.hama.matrix.algebra.DenseMatrixVectorMultReduce;
import org.apache.hama.matrix.algebra.JacobiInitMap;
import org.apache.hama.matrix.algebra.MatrixAdditionMap;
import org.apache.hama.matrix.algebra.MatrixAdditionReduce;
import org.apache.hama.matrix.algebra.PivotMap;
import org.apache.hama.util.BytesUtil;
import org.apache.hama.util.RandomVariable;

/**
 * This class represents a dense matrix.
 */
public class DenseMatrix extends AbstractMatrix implements Matrix {
  static private final String TABLE_PREFIX = DenseMatrix.class.getSimpleName();
  static private final Path TMP_DIR = new Path(DenseMatrix.class
      .getSimpleName()
      + "_TMP_dir");

  /**
   * Construct a raw matrix. Just create a table in HBase.
   * 
   * @param conf configuration object
   * @param m the number of rows.
   * @param n the number of columns.
   * @throws IOException throw the exception to let the user know what happend,
   *                 if we didn't create the matrix successfully.
   */
  public DenseMatrix(HamaConfiguration conf, int m, int n) throws IOException {
    setConfiguration(conf);

    tryToCreateTable(TABLE_PREFIX);
    closed = false;
    this.setDimension(m, n);
  }

  /**
   * Create/load a matrix aliased as 'matrixName'.
   * 
   * @param conf configuration object
   * @param matrixName the name of the matrix
   * @param force if force is true, a new matrix will be created no matter
   *                'matrixName' has aliased to an existed matrix; otherwise,
   *                just try to load an existed matrix alised 'matrixName'.
   * @throws IOException
   */
  public DenseMatrix(HamaConfiguration conf, String matrixName, boolean force)
      throws IOException {
    setConfiguration(conf);
    // if force is set to true:
    // 1) if this matrixName has aliase to other matrix, we will remove
    // the old aliase, create a new matrix table, and aliase to it.

    // 2) if this matrixName has no aliase to other matrix, we will create
    // a new matrix table, and alise to it.
    //
    // if force is set to false, we just try to load an existed matrix alised
    // as 'matrixname'.

    boolean existed = hamaAdmin.matrixExists(matrixName);

    if (force) {
      if (existed) {
        // remove the old aliase
        hamaAdmin.delete(matrixName);
      }
      // create a new matrix table.
      tryToCreateTable(TABLE_PREFIX);
      // save the new aliase relationship
      save(matrixName);
    } else {
      if (existed) {
        // try to get the actual path of the table
        matrixPath = hamaAdmin.getPath(matrixName);
        // load the matrix
        table = new HTable(conf, matrixPath);
        // increment the reference
        incrementAndGetRef();
      } else {
        throw new IOException("Try to load non-existed matrix alised as "
            + matrixName);
      }
    }

    closed = false;
  }

  /**
   * Load a matrix from an existed matrix table whose tablename is 'matrixpath' !!
   * It is an internal used for map/reduce.
   * 
   * @param conf configuration object
   * @param matrixpath
   * @throws IOException
   * @throws IOException
   */
  public DenseMatrix(HamaConfiguration conf, String matrixpath)
      throws IOException {
    setConfiguration(conf);
    matrixPath = matrixpath;
    // load the matrix
    table = new HTable(conf, matrixPath);
    // TODO: now we don't increment the reference of the table
    // for it's an internal use for map/reduce.
    // if we want to increment the reference of the table,
    // we don't know where to call Matrix.close in Add & Mul map/reduce
    // process to decrement the reference. It seems difficulty.
  }

  /**
   * Create an m-by-n constant matrix.
   * 
   * @param conf configuration object
   * @param m the number of rows.
   * @param n the number of columns.
   * @param s fill the matrix with this scalar value.
   * @throws IOException throw the exception to let the user know what happend,
   *                 if we didn't create the matrix successfully.
   */
  public DenseMatrix(HamaConfiguration conf, int m, int n, double s)
      throws IOException {
    setConfiguration(conf);

    tryToCreateTable(TABLE_PREFIX);

    closed = false;

    for (int i = 0; i < m; i++) {
      for (int j = 0; j < n; j++) {
        set(i, j, s);
      }
    }

    setDimension(m, n);
  }

  /**
   * Generate matrix with random elements
   * 
   * @param conf configuration object
   * @param m the number of rows.
   * @param n the number of columns.
   * @return an m-by-n matrix with uniformly distributed random elements.
   * @throws IOException
   */
  public static DenseMatrix random(HamaConfiguration conf, int m, int n)
      throws IOException {
    DenseMatrix rand = new DenseMatrix(conf, m, n);
    DenseVector vector = new DenseVector();
    LOG.info("Create the " + m + " * " + n + " random matrix : "
        + rand.getPath());

    for (int i = 0; i < m; i++) {
      vector.clear();
      for (int j = 0; j < n; j++) {
        vector.set(j, RandomVariable.rand());
      }
      rand.setRow(i, vector);
    }

    return rand;
  }

  /**
   * Generate matrix with random elements using Map/Reduce
   * 
   * @param conf configuration object
   * @param m the number of rows.
   * @param n the number of columns.
   * @return an m-by-n matrix with uniformly distributed random elements.
   * @throws IOException
   */
  public static DenseMatrix random_mapred(HamaConfiguration conf, int m, int n)
      throws IOException {
    DenseMatrix rand = new DenseMatrix(conf, m, n);
    LOG.info("Create the " + m + " * " + n + " random matrix : "
        + rand.getPath());

    Job job = new Job(conf, "random matrix MR job : " + rand.getPath());
    final Path inDir = new Path(TMP_DIR, "in");
    FileInputFormat.setInputPaths(job, inDir);
    job.setMapperClass(RandomMatrixMapper.class);

    job.setMapOutputKeyClass(IntWritable.class);
    job.setMapOutputValueClass(MapWritable.class);

    job.getConfiguration().setInt("matrix.column", n);
    job.getConfiguration().set("matrix.type", TABLE_PREFIX);
    job.getConfiguration().set("matrix.density", "100");

    job.setInputFormatClass(SequenceFileInputFormat.class);
    final FileSystem fs = FileSystem.get(job.getConfiguration());
    int interval = m / conf.getNumMapTasks();

    // generate an input file for each map task
    for (int i = 0; i < conf.getNumMapTasks(); ++i) {
      final Path file = new Path(inDir, "part" + i);
      final IntWritable start = new IntWritable(i * interval);
      IntWritable end = null;
      if ((i + 1) != conf.getNumMapTasks()) {
        end = new IntWritable(((i * interval) + interval) - 1);
      } else {
        end = new IntWritable(m - 1);
      }
      final SequenceFile.Writer writer = SequenceFile.createWriter(fs, job
          .getConfiguration(), file, IntWritable.class, IntWritable.class,
          CompressionType.NONE);
      try {
        writer.append(start, end);
      } finally {
        writer.close();
      }
      System.out.println("Wrote input for Map #" + i);
    }

    job.setOutputFormatClass(TableOutputFormat.class);
    job.setReducerClass(RandomMatrixReducer.class);
    job.getConfiguration().set(TableOutputFormat.OUTPUT_TABLE, rand.getPath());
    job.setOutputKeyClass(ImmutableBytesWritable.class);
    job.setOutputValueClass(Writable.class);

    try {
      job.waitForCompletion(true);
    } catch (InterruptedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (ClassNotFoundException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    fs.delete(TMP_DIR, true);
    return rand;
  }

  /**
   * Generate identity matrix
   * 
   * @param conf configuration object
   * @param m the number of rows.
   * @param n the number of columns.
   * @return an m-by-n matrix with ones on the diagonal and zeros elsewhere.
   * @throws IOException
   */
  public static DenseMatrix identity(HamaConfiguration conf, int m, int n)
      throws IOException {
    DenseMatrix identity = new DenseMatrix(conf, m, n);
    LOG.info("Create the " + m + " * " + n + " identity matrix : "
        + identity.getPath());

    for (int i = 0; i < m; i++) {
      DenseVector vector = new DenseVector();
      for (int j = 0; j < n; j++) {
        vector.set(j, (i == j ? 1.0 : 0.0));
      }
      identity.setRow(i, vector);
    }

    return identity;
  }

  /**
   * Gets the double value of (i, j)
   * 
   * @param i ith row of the matrix
   * @param j jth column of the matrix
   * @return the value of entry, or zero If entry is null
   * @throws IOException
   */
  public double get(int i, int j) throws IOException {
    if (this.getRows() < i || this.getColumns() < j)
      throw new ArrayIndexOutOfBoundsException(i + ", " + j);

    Get get = new Get(BytesUtil.getRowIndex(i));
    get.addColumn(Constants.COLUMNFAMILY);
    byte[] result = table.get(get).getValue(Constants.COLUMNFAMILY,
        Bytes.toBytes(String.valueOf(j)));

    if (result == null)
      throw new NullPointerException("Unexpected null");

    return Bytes.toDouble(result);
  }

  /**
   * Gets the vector of row
   * 
   * @param i the row index of the matrix
   * @return the vector of row
   * @throws IOException
   */
  public DenseVector getRow(int i) throws IOException {
    Get get = new Get(BytesUtil.getRowIndex(i));
    get.addFamily(Constants.COLUMNFAMILY);
    Result r = table.get(get);
    return new DenseVector(r);
  }

  /**
   * Gets the vector of column
   * 
   * @param j the column index of the matrix
   * @return the vector of column
   * @throws IOException
   */
  public DenseVector getColumn(int j) throws IOException {
    Scan scan = new Scan();
    scan.addColumn(Constants.COLUMNFAMILY, Bytes.toBytes(String.valueOf(j)));
    ResultScanner s = table.getScanner(scan);
    Result r = null;

    MapWritable trunk = new MapWritable();
    while ((r = s.next()) != null) {
      byte[] value = r.getValue(Constants.COLUMNFAMILY, Bytes.toBytes(String
          .valueOf(j)));
      LOG.info(Bytes.toString(r.getRow()));
      trunk.put(new IntWritable(BytesUtil.getRowIndex(r.getRow())),
          new DoubleWritable(Bytes.toDouble(value)));
    }

    return new DenseVector(trunk);
  }

  /** {@inheritDoc} */
  public void set(int i, int j, double value) throws IOException {
    if (this.getRows() < i || this.getColumns() < j)
      throw new ArrayIndexOutOfBoundsException(this.getRows() + ", "
          + this.getColumns() + ": " + i + ", " + j);
    VectorUpdate update = new VectorUpdate(i);
    update.put(j, value);
    table.put(update.getPut());
  }

  /**
   * Set the row of a matrix to a given vector
   * 
   * @param row
   * @param vector
   * @throws IOException
   */
  public void setRow(int row, Vector vector) throws IOException {
    if (this.getRows() < row || this.getColumns() < vector.size())
      throw new ArrayIndexOutOfBoundsException(row);

    VectorUpdate update = new VectorUpdate(row);
    update.putAll(vector.getEntries());
    table.put(update.getPut());
  }

  /**
   * Set the column of a matrix to a given vector
   * 
   * @param column
   * @param vector
   * @throws IOException
   */
  public void setColumn(int column, Vector vector) throws IOException {
    if (this.getColumns() < column || this.getRows() < vector.size())
      throw new ArrayIndexOutOfBoundsException(column);

    for (Map.Entry<Writable, Writable> e : vector.getEntries().entrySet()) {
      int key = ((IntWritable) e.getKey()).get();
      double value = ((DoubleWritable) e.getValue()).get();
      VectorUpdate update = new VectorUpdate(key);
      update.put(column, value);
      table.put(update.getPut());
    }
  }

  /**
   * C = alpha*B + A
   * 
   * @param alpha
   * @param B
   * @return C
   * @throws IOException
   */
  public DenseMatrix add(double alpha, Matrix B) throws IOException {
    ensureForAddition(B);

    DenseMatrix result = new DenseMatrix(config, this.getRows(), this
        .getColumns());
    Job job = new Job(config, "addition MR job" + result.getPath());

    Scan scan = new Scan();
    scan.addFamily(Constants.COLUMNFAMILY);
    job.getConfiguration().set(MatrixAdditionMap.MATRIX_SUMMANDS, B.getPath());
    job.getConfiguration().set(MatrixAdditionMap.MATRIX_ALPHAS,
        Double.toString(alpha));

    TableMapReduceUtil.initTableMapperJob(this.getPath(), scan,
        MatrixAdditionMap.class, IntWritable.class, MapWritable.class, job);
    TableMapReduceUtil.initTableReducerJob(result.getPath(),
        MatrixAdditionReduce.class, job);
    try {
      job.waitForCompletion(true);
    } catch (InterruptedException e) {
      e.printStackTrace();
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }

    return result;
  }

  /**
   * C = B + A
   * 
   * @param B
   * @return C
   * @throws IOException
   */
  public DenseMatrix add(Matrix B) throws IOException {
    return add(1.0, B);
  }

  public DenseMatrix add(Matrix... matrices) throws IOException {
    // ensure all the matrices are suitable for addition.
    for (Matrix m : matrices) {
      ensureForAddition(m);
    }

    DenseMatrix result = new DenseMatrix(config, this.getRows(), this
        .getColumns());

    StringBuilder summandList = new StringBuilder();
    StringBuilder alphaList = new StringBuilder();
    for (Matrix m : matrices) {
      summandList.append(m.getPath());
      summandList.append(",");
      alphaList.append("1");
      alphaList.append(",");
    }
    summandList.deleteCharAt(summandList.length() - 1);
    alphaList.deleteCharAt(alphaList.length() - 1);

    Job job = new Job(config, "addition MR job" + result.getPath());

    Scan scan = new Scan();
    scan.addFamily(Constants.COLUMNFAMILY);
    job.getConfiguration().set(MatrixAdditionMap.MATRIX_SUMMANDS,
        summandList.toString());
    job.getConfiguration().set(MatrixAdditionMap.MATRIX_ALPHAS,
        alphaList.toString());

    TableMapReduceUtil.initTableMapperJob(this.getPath(), scan,
        MatrixAdditionMap.class, IntWritable.class, MapWritable.class, job);
    TableMapReduceUtil.initTableReducerJob(result.getPath(),
        MatrixAdditionReduce.class, job);
    try {
      job.waitForCompletion(true);
    } catch (InterruptedException e) {
      e.printStackTrace();
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }

    return result;
  }

  private void ensureForAddition(Matrix m) throws IOException {
    if (getRows() != m.getRows() || getColumns() != m.getColumns()) {
      throw new IOException(
          "Matrices' rows and columns should be same while A+B.");
    }
  }

  /**
   * C = A*B using iterative method
   * 
   * @param B
   * @return C
   * @throws IOException
   */
  public DenseMatrix mult(Matrix B) throws IOException {
    ensureForMultiplication(B);
    int columns = 0;
    if (B.getColumns() == 1 || this.getColumns() == 1)
      columns = 1;
    else
      columns = this.getColumns();

    DenseMatrix result = new DenseMatrix(config, this.getRows(), columns);
    List<Job> jobId = new ArrayList<Job>();

    for (int i = 0; i < this.getRows(); i++) {
      Job job = new Job(config, "multiplication MR job : " + result.getPath()
          + " " + i);

      Scan scan = new Scan();
      scan.addFamily(Constants.COLUMNFAMILY);
      job.getConfiguration().set(DenseMatrixVectorMultMap.MATRIX_A,
          this.getPath());
      job.getConfiguration().setInt(DenseMatrixVectorMultMap.ITH_ROW, i);

      TableMapReduceUtil.initTableMapperJob(B.getPath(), scan,
          DenseMatrixVectorMultMap.class, IntWritable.class, MapWritable.class,
          job);
      TableMapReduceUtil.initTableReducerJob(result.getPath(),
          DenseMatrixVectorMultReduce.class, job);
      try {
        job.waitForCompletion(false);
        jobId.add(job);
      } catch (InterruptedException e) {
        e.printStackTrace();
      } catch (ClassNotFoundException e) {
        e.printStackTrace();
      }
    }

    while (checkAllJobs(jobId) == false) {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }

    return result;
  }

  /**
   * C = A * B using Blocking algorithm
   * 
   * @param B
   * @param blocks the number of blocks
   * @return C
   * @throws IOException
   */
  public DenseMatrix mult(Matrix B, int blocks) throws IOException {
    ensureForMultiplication(B);

    String collectionTable = "collect_" + RandomVariable.randMatrixPath();
    HTableDescriptor desc = new HTableDescriptor(collectionTable);
    desc
        .addFamily(new HColumnDescriptor(Bytes.toBytes(Constants.BLOCK)));
    this.admin.createTable(desc);
    LOG.info("Collect Blocks");

    collectBlocksMapRed(this.getPath(), collectionTable, blocks, true);
    collectBlocksMapRed(B.getPath(), collectionTable, blocks, false);

    DenseMatrix result = new DenseMatrix(config, this.getRows(), this
        .getColumns());

    Job job = new Job(config, "multiplication MR job : " + result.getPath());

    Scan scan = new Scan();
    scan.addFamily(Bytes.toBytes(Constants.BLOCK));

    TableMapReduceUtil.initTableMapperJob(collectionTable, scan,
        BlockMultMap.class, BlockID.class, BytesWritable.class, job);
    TableMapReduceUtil.initTableReducerJob(result.getPath(),
        BlockMultReduce.class, job);

    try {
      job.waitForCompletion(true);
    } catch (InterruptedException e) {
      e.printStackTrace();
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }

    hamaAdmin.delete(collectionTable);
    return result;
  }

  private void ensureForMultiplication(Matrix m) throws IOException {
    if (getColumns() != m.getRows()) {
      throw new IOException("A's columns should equal with B's rows while A*B.");
    }
  }

  /**
   * C = alpha*A*B + C
   * 
   * @param alpha
   * @param B
   * @param C
   * @return C
   * @throws IOException
   */
  public Matrix multAdd(double alpha, Matrix B, Matrix C) throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  /**
   * Computes the given norm of the matrix
   * 
   * @param type
   * @return norm of the matrix
   * @throws IOException
   */
  public double norm(Norm type) throws IOException {
    if (type == Norm.One)
      return getNorm1();
    else if (type == Norm.Frobenius)
      return getFrobenius();
    else if (type == Norm.Infinity)
      return getInfinity();
    else
      return getMaxvalue();
  }

  /**
   * Returns type of matrix
   */
  public String getType() {
    return this.getClass().getSimpleName();
  }

  /**
   * Returns the sub matrix formed by selecting certain rows and columns from a
   * bigger matrix. The sub matrix is a in-memory operation only.
   * 
   * @param i0 the start index of row
   * @param i1 the end index of row
   * @param j0 the start index of column
   * @param j1 the end index of column
   * @return the sub matrix of matrix
   * @throws IOException
   */
  public SubMatrix subMatrix(int i0, int i1, int j0, int j1) throws IOException {
    int columnSize = (j1 - j0) + 1;
    SubMatrix result = new SubMatrix((i1 - i0) + 1, columnSize);

    Scan scan = new Scan();
    for (int j = j0, jj = 0; j <= j1; j++, jj++) {
      scan.addColumn(Constants.COLUMNFAMILY, Bytes.toBytes(String.valueOf(j)));
    }
    scan.setStartRow(BytesUtil.getRowIndex(i0));
    scan.setStopRow(BytesUtil.getRowIndex(i1 + 1));
    ResultScanner s = table.getScanner(scan);
    Iterator<Result> it = s.iterator();

    int i = 0;
    Result rs = null;
    while (it.hasNext()) {
      rs = it.next();
      for (int j = j0, jj = 0; j <= j1; j++, jj++) {
        byte[] vv = rs.getValue(Constants.COLUMNFAMILY, Bytes.toBytes(String
            .valueOf(j)));
        result.set(i, jj, vv);
      }
      i++;
    }

    return result;
  }

  /**
   * Collect Blocks
   * 
   * @param path a input path
   * @param collectionTable the collection table
   * @param blockNum the number of blocks
   * @param bool
   * @throws IOException
   */
  public void collectBlocksMapRed(String path, String collectionTable,
      int blockNum, boolean bool) throws IOException {
    double blocks = Math.pow(blockNum, 0.5);
    if (!String.valueOf(blocks).endsWith(".0"))
      throw new IOException("can't divide.");

    int block_size = (int) blocks;
    Job job = new Job(config, "Blocking MR job" + getPath());

    Scan scan = new Scan();
    scan.addFamily(Constants.COLUMNFAMILY);

    job.getConfiguration().set(CollectBlocksMapper.BLOCK_SIZE,
        String.valueOf(block_size));
    job.getConfiguration().set(CollectBlocksMapper.ROWS,
        String.valueOf(this.getRows()));
    job.getConfiguration().set(CollectBlocksMapper.COLUMNS,
        String.valueOf(this.getColumns()));
    job.getConfiguration().setBoolean(CollectBlocksMapper.MATRIX_POS, bool);

    TableMapReduceUtil.initTableMapperJob(path, scan,
        org.apache.hama.mapreduce.CollectBlocksMapper.class, BlockID.class,
        MapWritable.class, job);
    TableMapReduceUtil.initTableReducerJob(collectionTable,
        org.apache.hama.mapreduce.CollectBlocksReducer.class, job);

    try {
      job.waitForCompletion(true);
    } catch (InterruptedException e) {
      e.printStackTrace();
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }
  }

  /**
   * Compute all the eigen values. Note: all the eigen values are collected in
   * the "eival:value" column, and the eigen vector of a specified eigen value
   * is collected in the "eivec:" column family in the same row.
   * 
   * TODO: we may need to expose the interface to access the eigen values and
   * vectors
   * 
   * @param imax limit the loops of the computation
   * @throws IOException
   */
  public void jacobiEigenValue(int imax) throws IOException {
    /*
     * Initialization A M/R job is used for initialization(such as, preparing a
     * matrx copy of the original in "eicol:" family.)
     */
    // initialization
    Job job = new Job(config, "JacobiEigen initialization MR job" + getPath());

    Scan scan = new Scan();
    scan.addFamily(Constants.COLUMNFAMILY);

    TableMapReduceUtil.initTableMapperJob(getPath(), scan, JacobiInitMap.class,
        ImmutableBytesWritable.class, Put.class, job);
    TableMapReduceUtil.initTableReducerJob(getPath(),
        IdentityTableReducer.class, job);

    try {
      job.waitForCompletion(true);
    } catch (InterruptedException e) {
      e.printStackTrace();
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }

    final FileSystem fs = FileSystem.get(config);
    Pair pivotPair = new Pair();
    DoubleWritable pivotWritable = new DoubleWritable();
    VectorUpdate vu;

    // loop
    int size = this.getRows();
    int state = size;
    int pivot_row, pivot_col;
    double pivot;
    double s, c, t, y;

    int icount = 0;
    while (state != 0 && icount < imax) {
      icount = icount + 1;
      /*
       * Find the pivot and its index(pivot_row, pivot_col) A M/R job is used to
       * scan all the "eival:ind" to get the max absolute value of each row, and
       * do a MAX aggregation of these max values to get the max value in the
       * matrix.
       */
      Path outDir = new Path(new Path(getType() + "_TMP_FindPivot_dir_"
          + System.currentTimeMillis()), "out");
      if (fs.exists(outDir))
        fs.delete(outDir, true);

      job = new Job(config, "Find Pivot MR job" + getPath());

      scan = new Scan();
      scan.addFamily(Bytes.toBytes(Constants.EI));

      job.setInputFormatClass(PivotInputFormat.class);
      job.setMapOutputKeyClass(Pair.class);
      job.setMapOutputValueClass(DoubleWritable.class);
      job.setMapperClass(PivotMap.class);
      job.getConfiguration().set(PivotInputFormat.INPUT_TABLE, getPath());
      job.getConfiguration().set(PivotInputFormat.SCAN,
          PivotInputFormat.convertScanToString(scan));

      job.setOutputKeyClass(Pair.class);
      job.setOutputValueClass(DoubleWritable.class);
      job.setOutputFormatClass(SequenceFileOutputFormat.class);
      SequenceFileOutputFormat.setOutputPath(job, outDir);

      try {
        job.waitForCompletion(true);
      } catch (InterruptedException e) {
        e.printStackTrace();
      } catch (ClassNotFoundException e) {
        e.printStackTrace();
      }

      // read outputs
      Path inFile = new Path(outDir, "part-r-00000");
      SequenceFile.Reader reader = new SequenceFile.Reader(fs, inFile, config);
      try {
        reader.next(pivotPair, pivotWritable);
        pivot_row = pivotPair.getRow();
        pivot_col = pivotPair.getColumn();
        pivot = pivotWritable.get();
      } finally {
        reader.close();
      }
      fs.delete(outDir, true);
      fs.delete(outDir.getParent(), true);
      
      if(pivot_row == 0 && pivot_col == 0)
        break; // stop the iterations
      
      /*
       * Calculation
       * 
       * Compute the rotation parameters of next rotation.
       */
      Get get = new Get(BytesUtil.getRowIndex(pivot_row));
      get.addFamily(Bytes.toBytes(Constants.EI));
      Result r = table.get(get);
      double e1 = Bytes.toDouble(r.getValue(Bytes
          .toBytes(Constants.EI), Bytes
          .toBytes(Constants.EIVAL)));

      get = new Get(BytesUtil.getRowIndex(pivot_col));
      get.addFamily(Bytes.toBytes(Constants.EI));
      r = table.get(get);
      double e2 = Bytes.toDouble(r.getValue(Bytes
          .toBytes(Constants.EI), Bytes
          .toBytes(Constants.EIVAL)));
      
      y = (e2 - e1) / 2;
      t = Math.abs(y) + Math.sqrt(pivot * pivot + y * y);
      s = Math.sqrt(pivot * pivot + t * t);
      c = t / s;
      s = pivot / s;
      t = (pivot * pivot) / t;
      if (y < 0) {
        s = -s;
        t = -t;
      }

      /*
       * Upate the pivot and the eigen values indexed by the pivot
       */
      vu = new VectorUpdate(pivot_row);
      vu.put(Constants.EICOL, pivot_col, 0);
      table.put(vu.getPut());

      state = update(pivot_row, -t, state);
      state = update(pivot_col, t, state);

      /*
       * Rotation the matrix
       */
      job = new Job(config, "Rotation Matrix MR job" + getPath());

      scan = new Scan();
      scan.addFamily(Bytes.toBytes(Constants.EI));

      job.getConfiguration().setInt(Constants.PIVOTROW, pivot_row);
      job.getConfiguration().setInt(Constants.PIVOTCOL, pivot_col);
      job.getConfiguration().set(Constants.PIVOTSIN, String.valueOf(s));
      job.getConfiguration().set(Constants.PIVOTCOS, String.valueOf(c));

      job.setInputFormatClass(RotationInputFormat.class);
      job.setMapOutputKeyClass(NullWritable.class);
      job.setMapOutputValueClass(NullWritable.class);
      job.setMapperClass(DummyMapper.class);
      job.getConfiguration().set(RotationInputFormat.INPUT_TABLE, getPath());
      job.getConfiguration().set(RotationInputFormat.SCAN,
          PivotInputFormat.convertScanToString(scan));
      job.setOutputFormatClass(NullOutputFormat.class);

      try {
        job.waitForCompletion(true);
      } catch (InterruptedException e) {
        e.printStackTrace();
      } catch (ClassNotFoundException e) {
        e.printStackTrace();
      }

      // rotate eigenvectors
      LOG.info("rotating eigenvector");
      for (int i = 0; i < size; i++) {
        get = new Get(BytesUtil.getRowIndex(pivot_row));
        e1 = Bytes.toDouble(table.get(get).getValue(
            Bytes.toBytes(Constants.EIVEC),
            Bytes.toBytes(String.valueOf(i))));

        get = new Get(BytesUtil.getRowIndex(pivot_col));
        e2 = Bytes.toDouble(table.get(get).getValue(
            Bytes.toBytes(Constants.EIVEC),
            Bytes.toBytes(String.valueOf(i))));

        vu = new VectorUpdate(pivot_row);
        vu.put(Constants.EIVEC, i, c * e1 - s * e2);
        table.put(vu.getPut());

        vu = new VectorUpdate(pivot_col);
        vu.put(Constants.EIVEC, i, s * e1 + c * e2);
        table.put(vu.getPut());
      }

      LOG.info("update index...");
      // update index array
      maxind(pivot_row, size);
      maxind(pivot_col, size);
    }
  }

  void maxind(int row, int size) throws IOException {
    int m = row + 1;
    Get get = null;
    if (row + 2 < size) {
      get = new Get(BytesUtil.getRowIndex(row));

      double max = Bytes.toDouble(table.get(get).getValue(
          Bytes.toBytes(Constants.EICOL),
          Bytes.toBytes(String.valueOf(m))));
      double val;
      for (int i = row + 2; i < size; i++) {
        get = new Get(BytesUtil.getRowIndex(row));
        val = Bytes.toDouble(table.get(get).getValue(
            Bytes.toBytes(Constants.EICOL),
            Bytes.toBytes(String.valueOf(i))));
        if (Math.abs(val) > Math.abs(max)) {
          m = i;
          max = val;
        }
      }
    }

    VectorUpdate vu = new VectorUpdate(row);
    vu.put(Constants.EI, "ind", String.valueOf(m));
    table.put(vu.getPut());
  }

  int update(int row, double value, int state) throws IOException {
    Get get = new Get(BytesUtil.getRowIndex(row));
    double e = Bytes.toDouble(table.get(get).getValue(
        Bytes.toBytes(Constants.EI),
        Bytes.toBytes(Constants.EIVAL)));
    int changed = BytesUtil.bytesToInt(table.get(get).getValue(
        Bytes.toBytes(Constants.EI),
        Bytes.toBytes("changed")));
    double y = e;
    e += value;
    
    VectorUpdate vu = new VectorUpdate(row);
    vu.put(Constants.EI, Constants.EIVAL, e);

    if (changed == 1 && (Math.abs(y - e) < .0000001)) { // y == e) {
      changed = 0;
      vu.put(Constants.EI,
          Constants.EICHANGED, String.valueOf(changed));

      state--;
    } else if (changed == 0 && (Math.abs(y - e) > .0000001)) {
      changed = 1;
      vu.put(Constants.EI,
          Constants.EICHANGED, String.valueOf(changed));

      state++;
    }
    table.put(vu.getPut());
    return state;
  }

  // for test
  boolean verifyEigenValue(double[] e, double[][] E) throws IOException {
    boolean success = true;
    double e1, ev;
    Get get = null;
    for (int i = 0; i < e.length; i++) {
      get = new Get(BytesUtil.getRowIndex(i));
      e1 = Bytes.toDouble(table.get(get).getValue(
          Bytes.toBytes(Constants.EI),
          Bytes.toBytes(Constants.EIVAL)));
      success &= ((Math.abs(e1 - e[i]) < .0000001));
      if (!success)
        return success;

      for (int j = 0; j < E[i].length; j++) {
        get = new Get(BytesUtil.getRowIndex(i));
        ev = Bytes.toDouble(table.get(get).getValue(
            Bytes.toBytes(Constants.EIVEC),
            Bytes.toBytes(String.valueOf(j))));
        success &= ((Math.abs(ev - E[i][j]) < .0000001));
        if (!success)
          return success;
      }
    }
    return success;
  }
}
