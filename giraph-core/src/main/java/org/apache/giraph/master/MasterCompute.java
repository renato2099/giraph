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

package org.apache.giraph.master;

import org.apache.giraph.aggregators.Aggregator;
import org.apache.giraph.bsp.CentralizedServiceMaster;
import org.apache.giraph.combiner.MessageCombiner;
import org.apache.giraph.conf.DefaultImmutableClassesGiraphConfigurable;
import org.apache.giraph.graph.Computation;
import org.apache.giraph.graph.GraphState;
import org.apache.giraph.utils.WritableFactory;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.Mapper;

/**
 * Interface for defining a master vertex that can perform centralized
 * computation between supersteps. This class will be instantiated on the
 * master node and will run every superstep before the workers do.
 *
 * Communication with the workers should be performed via aggregators. The
 * values of the aggregators are broadcast to the workers before
 * vertex.compute() is called and collected by the master before
 * master.compute() is called. This means aggregator values used by the workers
 * are consistent with aggregator values from the master from the same
 * superstep and aggregator used by the master are consistent with aggregator
 * values from the workers from the previous superstep.
 */
public abstract class MasterCompute
    extends DefaultImmutableClassesGiraphConfigurable
    implements MasterAggregatorUsage, Writable {
  /** If true, do not do anymore computation on this vertex. */
  private boolean halt = false;
  /** Master aggregator usage */
  private CentralizedServiceMaster serviceMaster;
  /** Graph state */
  private GraphState graphState;
  /**
   * Computation and MessageCombiner classes used, which can be
   * switched by master
   */
  private SuperstepClasses superstepClasses;

  /**
   * Must be defined by user to specify what the master has to do.
   */
  public abstract void compute();

  /**
   * Initialize the MasterCompute class, this is the place to register
   * aggregators.
   */
  public abstract void initialize() throws InstantiationException,
    IllegalAccessException;

  /**
   * Retrieves the current superstep.
   *
   * @return Current superstep
   */
  public final long getSuperstep() {
    return graphState.getSuperstep();
  }

  /**
   * Get the total (all workers) number of vertices that
   * existed in the previous superstep.
   *
   * @return Total number of vertices (-1 if first superstep)
   */
  public final long getTotalNumVertices() {
    return graphState.getTotalNumVertices();
  }

  /**
   * Get the total (all workers) number of edges that
   * existed in the previous superstep.
   *
   * @return Total number of edges (-1 if first superstep)
   */
  public final long getTotalNumEdges() {
    return graphState.getTotalNumEdges();
  }

  /**
   * After this is called, the computation will stop, even if there are
   * still messages in the system or vertices that have not voted to halt.
   */
  public final void haltComputation() {
    halt = true;
  }

  /**
   * Has the master halted?
   *
   * @return True if halted, false otherwise.
   */
  public final boolean isHalted() {
    return halt;
  }

  /**
   * Get the mapper context
   *
   * @return Mapper context
   */
  public final Mapper.Context getContext() {
    return graphState.getContext();
  }

  /**
   * Set Computation class to be used
   *
   * @param computationClass Computation class
   */
  public final void setComputation(
      Class<? extends Computation> computationClass) {
    superstepClasses.setComputationClass(computationClass);
  }

  /**
   * Get Computation class to be used
   *
   * @return Computation class
   */
  public final Class<? extends Computation> getComputation() {
    // Might be called prior to classes being set, do not return NPE
    if (superstepClasses == null) {
      return null;
    }

    return superstepClasses.getComputationClass();
  }

  /**
   * Set MessageCombiner class to be used
   *
   * @param combinerClass MessageCombiner class
   */
  public final void setMessageCombiner(
      Class<? extends MessageCombiner> combinerClass) {
    superstepClasses.setMessageCombinerClass(combinerClass);
  }

  /**
   * Get MessageCombiner class to be used
   *
   * @return MessageCombiner class
   */
  public final Class<? extends MessageCombiner> getMessageCombiner() {
    // Might be called prior to classes being set, do not return NPE
    if (superstepClasses == null) {
      return null;
    }

    return superstepClasses.getMessageCombinerClass();
  }

  /**
   * Set incoming message class to be used
   * @param incomingMessageClass incoming message class
   */
  public final void setIncomingMessage(
      Class<? extends Writable> incomingMessageClass) {
    superstepClasses.setIncomingMessageClass(incomingMessageClass);
  }

  /**
   * Set outgoing message class to be used
   * @param outgoingMessageClass outgoing message class
   */
  public final void setOutgoingMessage(
      Class<? extends Writable> outgoingMessageClass) {
    superstepClasses.setOutgoingMessageClass(outgoingMessageClass);
  }

  @Override
  public final <A extends Writable> boolean registerAggregator(
    String name, Class<? extends Aggregator<A>> aggregatorClass)
    throws InstantiationException, IllegalAccessException {
    return serviceMaster.getAggregatorHandler().registerAggregator(
        name, aggregatorClass);
  }

  @Override
  public final <A extends Writable> boolean registerAggregator(
    String name, WritableFactory<? extends Aggregator<A>> aggregator)
    throws InstantiationException, IllegalAccessException {
    return serviceMaster.getAggregatorHandler().registerAggregator(
        name, aggregator);
  }

  @Override
  public final <A extends Writable> boolean registerPersistentAggregator(
      String name,
      Class<? extends Aggregator<A>> aggregatorClass) throws
      InstantiationException, IllegalAccessException {
    return serviceMaster.getAggregatorHandler().registerPersistentAggregator(
        name, aggregatorClass);
  }

  @Override
  public final <A extends Writable> A getAggregatedValue(String name) {
    return serviceMaster.getAggregatorHandler().<A>getAggregatedValue(name);
  }

  @Override
  public final <A extends Writable> void setAggregatedValue(
      String name, A value) {
    serviceMaster.getAggregatorHandler().setAggregatedValue(name, value);
  }

  /**
   * Call this to log a line to command line of the job. Use in moderation -
   * it's a synchronous call to Job client
   *
   * @param line Line to print
   */
  public void logToCommandLine(String line) {
    serviceMaster.getJobProgressTracker().logInfo(line);
  }

  final void setGraphState(GraphState graphState) {
    this.graphState = graphState;
  }

  final void setMasterService(CentralizedServiceMaster serviceMaster) {
    this.serviceMaster = serviceMaster;
  }

  final void setSuperstepClasses(SuperstepClasses superstepClasses) {
    this.superstepClasses = superstepClasses;
  }
}
