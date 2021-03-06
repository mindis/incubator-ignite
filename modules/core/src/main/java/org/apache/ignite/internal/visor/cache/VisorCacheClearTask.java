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

package org.apache.ignite.internal.visor.cache;

import org.apache.ignite.*;
import org.apache.ignite.compute.*;
import org.apache.ignite.internal.processors.task.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.internal.visor.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.resources.*;

/**
 * Task that clears specified caches on specified node.
 */
@GridInternal
public class VisorCacheClearTask extends VisorOneNodeTask<String, IgniteBiTuple<Integer, Integer>> {
    /** */
    private static final long serialVersionUID = 0L;

    /** {@inheritDoc} */
    @Override protected VisorCacheClearJob job(String arg) {
        return new VisorCacheClearJob(arg, debug);
    }

    /**
     * Job that clear specified caches.
     */
    private static class VisorCacheClearJob extends VisorJob<String, IgniteBiTuple<Integer, Integer>> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        @JobContextResource
        private ComputeJobContext jobCtx;

        /** */
        private final IgniteInClosure<IgniteFuture<Integer>> lsnr;

        /** */
        private final IgniteFuture<Integer>[] futs = new IgniteFuture[3];

        /** */
        private final String cacheName;

        /**
         * Create job.
         *
         * @param cacheName Cache name to clear.
         * @param debug Debug flag.
         */
        private VisorCacheClearJob(String cacheName, boolean debug) {
            super(cacheName, debug);

            this.cacheName = cacheName;

            lsnr = new IgniteInClosure<IgniteFuture<Integer>>() {
                @Override public void apply(IgniteFuture<Integer> f) {
                    assert futs[0].isDone();
                    assert futs[1] == null || futs[1].isDone();
                    assert futs[2] == null || futs[2].isDone();

                    jobCtx.callcc();
                }
            };
        }

        /**
         * @param subJob Sub job to execute asynchronously.
         * @return {@code true} If subJob was not completed and this job should be suspended.
         */
        private boolean callAsync(IgniteCallable<Integer> subJob, int idx) {
            IgniteCompute compute = ignite.compute(ignite.forCacheNodes(cacheName)).withAsync();

            compute.call(subJob);

            IgniteFuture<Integer> fut = compute.future();

            futs[idx] = fut;

            if (fut.isDone())
                return false;

            jobCtx.holdcc();

            fut.listenAsync(lsnr);

            return true;
        }

        /** {@inheritDoc} */
        @Override protected IgniteBiTuple<Integer, Integer> run(final String cacheName) {
            if (futs[0] == null || futs[1] == null || futs[2] == null) {
                IgniteCache cache = ignite.jcache(cacheName);

                if (futs[0] == null && callAsync(new VisorCacheSizeCallable(cache), 0))
                    return null;

                if (futs[1] == null && callAsync(new VisorCacheClearCallable(cache), 1))
                    return null;

                if (futs[2] == null && callAsync(new VisorCacheSizeCallable(cache), 2))
                    return null;
            }

            assert futs[0].isDone() && futs[1].isDone() && futs[2].isDone();

            return new IgniteBiTuple<>(futs[0].get(), futs[2].get());
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(VisorCacheClearJob.class, this);
        }
    }

    /**
     * Callable to get cache size.
     */
    @GridInternal
    private static class VisorCacheSizeCallable implements IgniteCallable<Integer> {
        /** */
        private final IgniteCache cache;

        /**
         * @param cache Cache to take size from.
         */
        private VisorCacheSizeCallable(IgniteCache cache) {
            this.cache = cache;
        }

        /** {@inheritDoc} */
        @Override public Integer call() throws Exception {
            return cache.size();
        }
    }

    /**
     * Callable to clear cache.
     */
    @GridInternal
    private static class VisorCacheClearCallable implements IgniteCallable<Integer> {
        /** */
        private final IgniteCache cache;

        /**
         * @param cache Cache to clear.
         */
        private VisorCacheClearCallable(IgniteCache cache) {
            this.cache = cache;
        }

        /** {@inheritDoc} */
        @Override public Integer call() throws Exception {
            cache.clear();

            return 0;
        }
    }
}
