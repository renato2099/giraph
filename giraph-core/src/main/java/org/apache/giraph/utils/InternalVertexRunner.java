/*
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

package org.apache.giraph.utils;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import org.apache.giraph.conf.GiraphConfiguration;
import org.apache.giraph.conf.GiraphConstants;
import org.apache.giraph.io.formats.GiraphFileInputFormat;
import org.apache.giraph.io.formats.InMemoryVertexOutputFormat;
import org.apache.giraph.job.GiraphJob;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.log4j.Logger;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A base class for running internal tests on a vertex
 *
 * Extending classes only have to invoke the run() method to test their vertex.
 * All data is written to a local tmp directory that is removed afterwards.
 * A local zookeeper instance is started in an extra thread and
 * shutdown at the end.
 *
 * Heavily inspired from Apache Mahout's MahoutTestCase
 */
@SuppressWarnings("unchecked")
public class InternalVertexRunner {
  /** Range of ZooKeeper ports to use for tests */
  public static final int LOCAL_ZOOKEEPER_PORT_FROM = 22182;
  /** Range of ZooKeeper ports to use for tests */
  public static final int LOCAL_ZOOKEEPER_PORT_TO = 65535;

  /** Logger */
  private static final Logger LOG =
      Logger.getLogger(InternalVertexRunner.class);

  /** Don't construct */
  private InternalVertexRunner() { }

  /**
   * Attempts to run the vertex internally in the current JVM, reading from and
   * writing to a temporary folder on local disk. Will start its own zookeeper
   * instance.
   *
   * @param conf GiraphClasses specifying which types to use
   * @param vertexInputData linewise vertex input data
   * @return linewise output data, or null if job fails
   * @throws Exception if anything goes wrong
   */
  public static Iterable<String> run(
      GiraphConfiguration conf,
      String[] vertexInputData) throws Exception {
    return run(conf, vertexInputData, null);
  }

  /**
   * Run the standalone ZooKeeper process and the job.
   *
   * @param quorumPeerConfig Quorum peer configuration
   * @param giraphJob Giraph job to run
   * @return True if successful, false otherwise
   */
  private static boolean runZooKeeperAndJob(QuorumPeerConfig quorumPeerConfig,
                                            GiraphJob giraphJob) {
    final InternalZooKeeper zookeeper = new InternalZooKeeper();
    final ServerConfig zkConfig = new ServerConfig();
    zkConfig.readFrom(quorumPeerConfig);

    ExecutorService executorService = Executors.newSingleThreadExecutor();
    executorService.execute(new Runnable() {
      @Override
      public void run() {
        try {
          zookeeper.runFromConfig(zkConfig);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    });
    try {
      return giraphJob.run(true);
    } catch (InterruptedException |
        ClassNotFoundException | IOException e) {
      LOG.error("runZooKeeperAndJob: Got exception on running", e);
    } finally {
      zookeeper.end();
      executorService.shutdown();
      try {
        executorService.awaitTermination(1, TimeUnit.MINUTES);
      } catch (InterruptedException e) {
        LOG.error("runZooKeeperAndJob: Interrupted on waiting", e);
      }
    }

    return false;
  }

  /**
   * Attempts to run the vertex internally in the current JVM, reading from and
   * writing to a temporary folder on local disk. Will start its own zookeeper
   * instance.
   *
   *
   * @param conf GiraphClasses specifying which types to use
   * @param vertexInputData linewise vertex input data
   * @param edgeInputData linewise edge input data
   * @return linewise output data, or null if job fails
   * @throws Exception if anything goes wrong
   */
  public static Iterable<String> run(
      GiraphConfiguration conf,
      String[] vertexInputData,
      String[] edgeInputData) throws Exception {
    // Prepare input file, output folder and temporary folders
    File tmpDir = FileUtils.createTestDir(conf.getComputationName());
    try {
      return run(conf, vertexInputData, edgeInputData, null, tmpDir);
    } finally {
      FileUtils.delete(tmpDir);
    }
  }

  /**
   * Attempts to run the vertex internally in the current JVM, reading from and
   * writing to a temporary folder on local disk. Will start its own zookeeper
   * instance.
   *
   *
   * @param conf GiraphClasses specifying which types to use
   * @param vertexInputData linewise vertex input data
   * @param edgeInputData linewise edge input data
   * @param checkpointsDir if set, will use this folder
   *                          for storing checkpoints.
   * @param tmpDir file path for storing temporary files.
   * @return linewise output data, or null if job fails
   * @throws Exception if anything goes wrong
   */
  public static Iterable<String> run(
      GiraphConfiguration conf,
      String[] vertexInputData,
      String[] edgeInputData,
      String checkpointsDir,
      File tmpDir) throws Exception {
    File vertexInputFile = null;
    File edgeInputFile = null;
    if (conf.hasVertexInputFormat()) {
      vertexInputFile = FileUtils.createTempFile(tmpDir, "vertices.txt");
    }
    if (conf.hasEdgeInputFormat()) {
      edgeInputFile = FileUtils.createTempFile(tmpDir, "edges.txt");
    }

    File outputDir = FileUtils.createTempDir(tmpDir, "output");
    File zkDir = FileUtils.createTempDir(tmpDir, "_bspZooKeeper");
    File zkMgrDir = FileUtils.createTempDir(tmpDir, "_defaultZkManagerDir");
    // Write input data to disk
    if (conf.hasVertexInputFormat()) {
      FileUtils.writeLines(vertexInputFile, vertexInputData);
    }
    if (conf.hasEdgeInputFormat()) {
      FileUtils.writeLines(edgeInputFile, edgeInputData);
    }

    int localZookeeperPort = findAvailablePort();

    conf.setWorkerConfiguration(1, 1, 100.0f);
    GiraphConstants.SPLIT_MASTER_WORKER.set(conf, false);
    GiraphConstants.LOCAL_TEST_MODE.set(conf, true);
    conf.setZookeeperList("localhost:" +
          String.valueOf(localZookeeperPort));

    conf.set(GiraphConstants.ZOOKEEPER_DIR, zkDir.toString());
    GiraphConstants.ZOOKEEPER_MANAGER_DIRECTORY.set(conf,
        zkMgrDir.toString());

    if (checkpointsDir == null) {
      checkpointsDir = FileUtils.createTempDir(
          tmpDir, "_checkpoints").toString();
    }
    GiraphConstants.CHECKPOINT_DIRECTORY.set(conf, checkpointsDir);

    // Create and configure the job to run the vertex
    GiraphJob job = new GiraphJob(conf, conf.getComputationName());

    Job internalJob = job.getInternalJob();
    if (conf.hasVertexInputFormat()) {
      GiraphFileInputFormat.setVertexInputPath(internalJob.getConfiguration(),
          new Path(vertexInputFile.toString()));
    }
    if (conf.hasEdgeInputFormat()) {
      GiraphFileInputFormat.setEdgeInputPath(internalJob.getConfiguration(),
          new Path(edgeInputFile.toString()));
    }
    FileOutputFormat.setOutputPath(job.getInternalJob(),
        new Path(outputDir.toString()));

    // Configure a local zookeeper instance
    Properties zkProperties = configLocalZooKeeper(zkDir, localZookeeperPort);

    QuorumPeerConfig qpConfig = new QuorumPeerConfig();
    qpConfig.parseProperties(zkProperties);

    boolean success = runZooKeeperAndJob(qpConfig, job);
    if (!success) {
      return null;
    }

    File outFile = new File(outputDir, "part-m-00000");
    if (conf.hasVertexOutputFormat() && outFile.canRead()) {
      return Files.readLines(outFile, Charsets.UTF_8);
    } else {
      return ImmutableList.of();
    }

  }

  /**
   * Attempts to run the vertex internally in the current JVM,
   * reading from an in-memory graph. Will start its own zookeeper
   * instance.
   *
   * @param <I> Vertex ID
   * @param <V> Vertex Value
   * @param <E> Edge Value
   * @param conf GiraphClasses specifying which types to use
   * @param graph input graph
   * @throws Exception if anything goes wrong
   */
  public static <I extends WritableComparable,
      V extends Writable,
      E extends Writable> void run(
      GiraphConfiguration conf,
      TestGraph<I, V, E> graph) throws Exception {
    // Prepare temporary folders
    File tmpDir = FileUtils.createTestDir(conf.getComputationName());
    try {
      run(conf, graph, tmpDir, null);
    } finally {
      FileUtils.delete(tmpDir);
    }
  }

  /**
   * Attempts to run the vertex internally in the current JVM,
   * reading from an in-memory graph. Will start its own zookeeper
   * instance.
   *
   * @param <I> Vertex ID
   * @param <V> Vertex Value
   * @param <E> Edge Value
   * @param conf GiraphClasses specifying which types to use
   * @param graph input graph
   * @param tmpDir file path for storing temporary files.
   * @param checkpointsDir if set, will use this folder
   *                          for storing checkpoints.
   * @throws Exception if anything goes wrong
   */
  public static <I extends WritableComparable,
      V extends Writable,
      E extends Writable> void run(
      GiraphConfiguration conf,
      TestGraph<I, V, E> graph,
      File tmpDir,
      String checkpointsDir) throws Exception {
    File zkDir = FileUtils.createTempDir(tmpDir, "_bspZooKeeper");
    File zkMgrDir = FileUtils.createTempDir(tmpDir, "_defaultZkManagerDir");

    if (checkpointsDir == null) {
      checkpointsDir = FileUtils.
          createTempDir(tmpDir, "_checkpoints").toString();
    }

    conf.setVertexInputFormatClass(InMemoryVertexInputFormat.class);

    // Create and configure the job to run the vertex
    GiraphJob job = new GiraphJob(conf, conf.getComputationName());

    InMemoryVertexInputFormat.setGraph(graph);

    int localZookeeperPort = findAvailablePort();

    conf.setWorkerConfiguration(1, 1, 100.0f);
    GiraphConstants.SPLIT_MASTER_WORKER.set(conf, false);
    GiraphConstants.LOCAL_TEST_MODE.set(conf, true);
    GiraphConstants.ZOOKEEPER_LIST.set(conf, "localhost:" +
          String.valueOf(localZookeeperPort));

    conf.set(GiraphConstants.ZOOKEEPER_DIR, zkDir.toString());
    GiraphConstants.ZOOKEEPER_MANAGER_DIRECTORY.set(conf,
        zkMgrDir.toString());
    GiraphConstants.CHECKPOINT_DIRECTORY.set(conf, checkpointsDir);

    // Configure a local zookeeper instance
    Properties zkProperties = configLocalZooKeeper(zkDir, localZookeeperPort);

    QuorumPeerConfig qpConfig = new QuorumPeerConfig();
    qpConfig.parseProperties(zkProperties);

    runZooKeeperAndJob(qpConfig, job);

  }

  /**
   * Attempts to run the vertex internally in the current JVM, reading and
   * writing to an in-memory graph. Will start its own zookeeper
   * instance.
   *
   * @param <I> Vertex ID
   * @param <V> Vertex Value
   * @param <E> Edge Value
   * @param conf GiraphClasses specifying which types to use
   * @param graph input graph
   * @return Output graph
   * @throws Exception if anything goes wrong
   */
  public static <I extends WritableComparable,
      V extends Writable,
      E extends Writable> TestGraph<I, V, E> runWithInMemoryOutput(
      GiraphConfiguration conf,
      TestGraph<I, V, E> graph) throws Exception {
    // Prepare temporary folders
    File tmpDir = FileUtils.createTestDir(conf.getComputationName());
    try {
      return runWithInMemoryOutput(conf, graph, tmpDir, null);
    } finally {
      FileUtils.delete(tmpDir);
    }
  }

  /**
   * Attempts to run the vertex internally in the current JVM, reading and
   * writing to an in-memory graph. Will start its own zookeeper
   * instance.
   *
   * @param <I> Vertex ID
   * @param <V> Vertex Value
   * @param <E> Edge Value
   * @param conf GiraphClasses specifying which types to use
   * @param graph input graph
   * @param tmpDir file path for storing temporary files.
   * @param checkpointsDir if set, will use this folder
   *                       for storing checkpoints.
   * @return Output graph
   * @throws Exception if anything goes wrong
   */
  public static <I extends WritableComparable,
      V extends Writable,
      E extends Writable> TestGraph<I, V, E> runWithInMemoryOutput(
      GiraphConfiguration conf,
      TestGraph<I, V, E> graph,
      File tmpDir,
      String checkpointsDir) throws Exception {
    conf.setVertexOutputFormatClass(InMemoryVertexOutputFormat.class);
    InMemoryVertexOutputFormat.initializeOutputGraph(conf);
    InternalVertexRunner.run(conf, graph, tmpDir, checkpointsDir);
    return InMemoryVertexOutputFormat.getOutputGraph();
  }

  /**
   * Configuration options for running local ZK.
   *
   * @param zkDir directory for ZK to hold files in.
   * @param zookeeperPort port zookeeper will listen on
   * @return Properties configured for local ZK.
   */
  private static Properties configLocalZooKeeper(File zkDir,
                                                 int zookeeperPort) {
    Properties zkProperties = new Properties();
    zkProperties.setProperty("tickTime", "2000");
    zkProperties.setProperty("dataDir", zkDir.getAbsolutePath());
    zkProperties.setProperty("clientPort",
        String.valueOf(zookeeperPort));
    zkProperties.setProperty("maxClientCnxns", "10000");
    zkProperties.setProperty("minSessionTimeout", "10000");
    zkProperties.setProperty("maxSessionTimeout", "100000");
    zkProperties.setProperty("initLimit", "10");
    zkProperties.setProperty("syncLimit", "5");
    zkProperties.setProperty("snapCount", "50000");
    return zkProperties;
  }

  /**
   * Scans for available port. Returns first port where
   * we can open server socket.
   * Note: if another process opened port with SO_REUSEPORT then this
   * function may return port that is in use. It actually happens
   * with NetCat on Mac.
   * @return available port
   */
  private static int findAvailablePort() {
    for (int port = LOCAL_ZOOKEEPER_PORT_FROM;
         port < LOCAL_ZOOKEEPER_PORT_TO; port++) {
      ServerSocket ss = null;
      try {
        ss = new ServerSocket(port);
        ss.setReuseAddress(true);
        return port;
      } catch (IOException e) {
        LOG.info("findAvailablePort: port " + port + " is in use.");
      } finally {
        if (ss != null && !ss.isClosed()) {
          try {
            ss.close();
          } catch (IOException e) {
            LOG.info("findAvailablePort: can't close test socket", e);
          }
        }
      }
    }
    throw new RuntimeException("No port found in the range [ " +
        LOCAL_ZOOKEEPER_PORT_FROM + ", " + LOCAL_ZOOKEEPER_PORT_TO + ")");
  }

  /**
   * Extension of {@link ZooKeeperServerMain} that allows programmatic shutdown
   */
  private static class InternalZooKeeper extends ZooKeeperServerMain {
    /**
     * Shutdown the ZooKeeper instance.
     */
    void end() {
      shutdown();
    }
  }
}
