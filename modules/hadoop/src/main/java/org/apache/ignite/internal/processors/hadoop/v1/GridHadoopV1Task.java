/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.hadoop.v1;

import org.apache.hadoop.mapred.*;
import org.apache.ignite.internal.processors.hadoop.*;
import org.apache.ignite.internal.processors.hadoop.v2.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.text.*;

/**
 * Extended Hadoop v1 task.
 */
public abstract class GridHadoopV1Task extends GridHadoopTask {
    /** Indicates that this task is to be cancelled. */
    private volatile boolean cancelled;

    /**
     * Constructor.
     *
     * @param taskInfo Task info.
     */
    protected GridHadoopV1Task(GridHadoopTaskInfo taskInfo) {
        super(taskInfo);
    }

    /**
     * Gets file name for that task result.
     *
     * @return File name.
     */
    public String fileName() {
        NumberFormat numFormat = NumberFormat.getInstance();

        numFormat.setMinimumIntegerDigits(5);
        numFormat.setGroupingUsed(false);

        return "part-" + numFormat.format(info().taskNumber());
    }

    /**
     *
     * @param jobConf Job configuration.
     * @param taskCtx Task context.
     * @param directWrite Direct write flag.
     * @param fileName File name.
     * @param attempt Attempt of task.
     * @return Collector.
     * @throws IOException In case of IO exception.
     */
    protected GridHadoopV1OutputCollector collector(JobConf jobConf, GridHadoopV2TaskContext taskCtx,
        boolean directWrite, @Nullable String fileName, TaskAttemptID attempt) throws IOException {
        GridHadoopV1OutputCollector collector = new GridHadoopV1OutputCollector(jobConf, taskCtx, directWrite,
            fileName, attempt) {
            /** {@inheritDoc} */
            @Override public void collect(Object key, Object val) throws IOException {
                if (cancelled)
                    throw new GridHadoopTaskCancelledException("Task cancelled.");

                super.collect(key, val);
            }
        };

        collector.setup();

        return collector;
    }

    /** {@inheritDoc} */
    @Override public void cancel() {
        cancelled = true;
    }

    /** Returns true if task is cancelled. */
    public boolean isCancelled() {
        return cancelled;
    }
}
