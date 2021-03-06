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

package org.apache.giraph.job;

import org.apache.hadoop.mapreduce.Job;

/**
 * Class to decide whether a GiraphJob should be restarted after failure.
 */
public interface GiraphJobRetryChecker {
  /**
   * Check if the job should be retried
   *
   * @param submittedJob Job that ran and failed
   * @param tryCount How many times have we tried to run the job until now
   *
   * @return True iff job should be retried
   */
  boolean shouldRetry(Job submittedJob, int tryCount);

  /**
   * The job has been checkpointed and halted. Should we now restart it?
   * @return true if checkpointed job should be automatically restarted.
   */
  boolean shouldRestartCheckpoint();
}
