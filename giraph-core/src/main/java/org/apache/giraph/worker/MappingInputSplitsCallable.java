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

import org.apache.giraph.conf.ImmutableClassesGiraphConfiguration;
import org.apache.giraph.io.GiraphInputFormat;
import org.apache.giraph.io.MappingInputFormat;
import org.apache.giraph.io.MappingReader;
import org.apache.giraph.mapping.MappingStore;
import org.apache.giraph.mapping.MappingEntry;
import org.apache.giraph.zk.ZooKeeperExt;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Mapper;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Load as many mapping input splits as possible.
 * Every thread will has its own instance of WorkerClientRequestProcessor
 * to send requests.
 *
 * @param <I> vertexId type
 * @param <V> vertexValue type
 * @param <E> edgeValue type
 * @param <B> mappingTarget type
 */
@SuppressWarnings("unchecked")
public class MappingInputSplitsCallable<I extends WritableComparable,
  V extends Writable, E extends Writable, B extends Writable>
  extends FullInputSplitCallable<I, V, E> {
  /** User supplied mappingInputFormat */
  private final MappingInputFormat<I, V, E, B> mappingInputFormat;
  /** Link to bspServiceWorker */
  private final BspServiceWorker<I, V, E> bspServiceWorker;

  /**
   * Constructor
   *
   * @param mappingInputFormat mappingInputFormat
   * @param splitOrganizer Input splits organizer
   * @param context Context
   * @param configuration Configuration
   * @param zooKeeperExt Handle to ZooKeeperExt
   * @param currentIndex Atomic Integer to get splitPath from list
   * @param bspServiceWorker bsp service worker
   */
  public MappingInputSplitsCallable(
      MappingInputFormat<I, V, E, B> mappingInputFormat,
      InputSplitPathOrganizer splitOrganizer,
      Mapper<?, ?, ?, ?>.Context context,
      ImmutableClassesGiraphConfiguration<I, V, E> configuration,
      ZooKeeperExt zooKeeperExt,
      AtomicInteger currentIndex,
      BspServiceWorker<I, V, E> bspServiceWorker) {
    super(splitOrganizer, context,
      configuration, zooKeeperExt, currentIndex);
    this.mappingInputFormat = mappingInputFormat;
    this.bspServiceWorker = bspServiceWorker;
  }

  @Override
  public GiraphInputFormat getInputFormat() {
    return mappingInputFormat;
  }

  @Override
  protected Integer readInputSplit(InputSplit inputSplit)
    throws IOException, InterruptedException {
    MappingReader<I, V, E, B> mappingReader =
        mappingInputFormat.createMappingReader(inputSplit, context);
    mappingReader.setConf(configuration);

    WorkerThreadAggregatorUsage aggregatorUsage = this.bspServiceWorker
        .getAggregatorHandler().newThreadAggregatorUsage();

    mappingReader.initialize(inputSplit, context);
    mappingReader.setWorkerAggregatorUse(aggregatorUsage);

    int entriesLoaded = 0;
    MappingStore<I, B> mappingStore =
      (MappingStore<I, B>) bspServiceWorker.getLocalData().getMappingStore();

    while (mappingReader.nextEntry()) {
      MappingEntry<I, B> entry = mappingReader.getCurrentEntry();
      entriesLoaded += 1;
      mappingStore.addEntry(entry.getVertexId(), entry.getMappingTarget());
    }
    return entriesLoaded;
  }
}
