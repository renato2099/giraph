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

package org.apache.giraph.worker;

import org.apache.giraph.bsp.CentralizedServiceWorker;
import org.apache.giraph.comm.requests.SendWorkerToWorkerMessageRequest;
import org.apache.giraph.conf.DefaultImmutableClassesGiraphConfigurable;
import org.apache.giraph.graph.GraphState;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.Mapper;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;

/**
 * WorkerContext allows for the execution of user code
 * on a per-worker basis. There's one WorkerContext per worker.
 */
@SuppressWarnings("rawtypes")
public abstract class WorkerContext
  extends DefaultImmutableClassesGiraphConfigurable
  implements WorkerAggregatorUsage, Writable {
  /** Global graph state */
  private GraphState graphState;
  /** Worker aggregator usage */
  private WorkerAggregatorUsage workerAggregatorUsage;

  /** Service worker */
  private CentralizedServiceWorker serviceWorker;
  /** Sorted list of other participating workers */
  private List<WorkerInfo> workerList;
  /** Index of this worker within workerList */
  private int myWorkerIndex;

  /**
   * Set the graph state.
   *
   * @param graphState Used to set the graph state.
   */
  public void setGraphState(GraphState graphState) {
    this.graphState = graphState;
  }

  /**
   * Setup superstep.
   *
   * @param serviceWorker Service worker containing all the information
   */
  public void setupSuperstep(CentralizedServiceWorker<?, ?, ?> serviceWorker) {
    this.serviceWorker = serviceWorker;
    workerList = serviceWorker.getWorkerInfoList();
    myWorkerIndex = workerList.indexOf(serviceWorker.getWorkerInfo());
  }

  /**
   * Set worker aggregator usage
   *
   * @param workerAggregatorUsage Worker aggregator usage
   */
  public void setWorkerAggregatorUsage(
      WorkerAggregatorUsage workerAggregatorUsage) {
    this.workerAggregatorUsage = workerAggregatorUsage;
  }

  /**
   * Initialize the WorkerContext.
   * This method is executed once on each Worker before the first
   * superstep starts.
   *
   * @throws IllegalAccessException Thrown for getting the class
   * @throws InstantiationException Expected instantiation in this method.
   */
  public abstract void preApplication() throws InstantiationException,
    IllegalAccessException;

  /**
   * Finalize the WorkerContext.
   * This method is executed once on each Worker after the last
   * superstep ends.
   */
  public abstract void postApplication();

  /**
   * Execute user code.
   * This method is executed once on each Worker before each
   * superstep starts.
   */
  public abstract void preSuperstep();

  /**
   * Get number of workers
   *
   * @return Number of workers
   */
  public int getWorkerCount() {
    return workerList.size();
  }

  /**
   * Get index for this worker
   *
   * @return Index of this worker
   */
  public int getMyWorkerIndex() {
    return myWorkerIndex;
  }

  /**
   * Get messages which other workers sent to this worker and clear them (can
   * be called once per superstep)
   *
   * @return Messages received
   */
  public List<Writable> getAndClearMessagesFromOtherWorkers() {
    return serviceWorker.getServerData().
        getAndClearCurrentWorkerToWorkerMessages();
  }

  /**
   * Send message to another worker
   *
   * @param message Message to send
   * @param workerIndex Index of the worker to send the message to
   */
  public void sendMessageToWorker(Writable message, int workerIndex) {
    SendWorkerToWorkerMessageRequest request =
        new SendWorkerToWorkerMessageRequest(message);
    if (workerIndex == myWorkerIndex) {
      request.doRequest(serviceWorker.getServerData());
    } else {
      serviceWorker.getWorkerClient().sendWritableRequest(
          workerList.get(workerIndex).getTaskId(), request);
    }
  }

  /**
   * Execute user code.
   * This method is executed once on each Worker after each
   * superstep ends.
   */
  public abstract void postSuperstep();

  /**
   * Retrieves the current superstep.
   *
   * @return Current superstep
   */
  public long getSuperstep() {
    return graphState.getSuperstep();
  }

  /**
   * Get the total (all workers) number of vertices that
   * existed in the previous superstep.
   *
   * @return Total number of vertices (-1 if first superstep)
   */
  public long getTotalNumVertices() {
    return graphState.getTotalNumVertices();
  }

  /**
   * Get the total (all workers) number of edges that
   * existed in the previous superstep.
   *
   * @return Total number of edges (-1 if first superstep)
   */
  public long getTotalNumEdges() {
    return graphState.getTotalNumEdges();
  }

  /**
   * Get the mapper context
   *
   * @return Mapper context
   */
  public Mapper.Context getContext() {
    return graphState.getContext();
  }

  @Override
  public <A extends Writable> void aggregate(String name, A value) {
    workerAggregatorUsage.aggregate(name, value);
  }

  @Override
  public <A extends Writable> A getAggregatedValue(String name) {
    return workerAggregatorUsage.<A>getAggregatedValue(name);
  }

  /**
   * Call this to log a line to command line of the job. Use in moderation -
   * it's a synchronous call to Job client
   *
   * @param line Line to print
   */
  public void logToCommandLine(String line) {
    serviceWorker.getJobProgressTracker().logInfo(line);
  }

  @Override
  public void write(DataOutput dataOutput) throws IOException {
  }

  @Override
  public void readFields(DataInput dataInput) throws IOException {
  }
}
