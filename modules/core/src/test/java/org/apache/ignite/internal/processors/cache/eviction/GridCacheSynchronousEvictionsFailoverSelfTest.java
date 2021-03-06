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

package org.apache.ignite.internal.processors.cache.eviction;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.affinity.*;
import org.apache.ignite.cache.affinity.consistenthash.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.testframework.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.apache.ignite.cache.CacheDistributionMode.*;
import static org.apache.ignite.cache.CacheMode.*;

/**
 *
 */
public class GridCacheSynchronousEvictionsFailoverSelfTest extends GridCacheAbstractSelfTest {
    /** {@inheritDoc} */
    @Override protected int gridCount() {
        return 3;
    }

    /** {@inheritDoc} */
    @Override protected CacheMode cacheMode() {
        return PARTITIONED;
    }

    /** {@inheritDoc} */
    @Override protected CacheDistributionMode distributionMode() {
        return PARTITIONED_ONLY;
    }

    /** {@inheritDoc} */
    @Override protected CacheConfiguration cacheConfiguration(String gridName) throws Exception {
        CacheConfiguration ccfg = super.cacheConfiguration(gridName);

        ccfg.setSwapEnabled(false);
        ccfg.setEvictSynchronized(true);
        ccfg.setEvictSynchronizedKeyBufferSize(10);

        ccfg.setBackups(2);

        ccfg.setAffinity(new CacheConsistentHashAffinityFunction(false, 500));

        return ccfg;
    }

    /** {@inheritDoc} */
    @Override protected long getTestTimeout() {
        return 60_000;
    }

    /**
     * @throws Exception If failed.
     */
    public void testSynchronousEvictions() throws Exception {
        IgniteCache<String, Integer> cache = jcache(0);

        final AtomicBoolean stop = new AtomicBoolean();

        IgniteInternalFuture<?> fut = null;

        try {
            Map<String, Integer> data = new HashMap<>();

            addKeysForNode(affinity(cache), grid(0).localNode(), data);
            addKeysForNode(affinity(cache), grid(1).localNode(), data);
            addKeysForNode(affinity(cache), grid(2).localNode(), data);

            fut = GridTestUtils.runAsync(new Callable<Void>() {
                @Override public Void call() throws Exception {
                    Random rnd = new Random();

                    while (!stop.get()) {
                        int idx = rnd.nextBoolean() ? 1 : 2;

                        log.info("Stopping grid: " + idx);

                        stopGrid(idx);

                        U.sleep(100);

                        log.info("Starting grid: " + idx);

                        startGrid(idx);
                    }

                    return null;
                }
            });

            for (int i = 0 ; i < 100; i++) {
                log.info("Iteration: " + i);

                try {
                    cache.putAll(data);
                }
                catch (IgniteException ignore) {
                    continue;
                }

                cache.localEvict(data.keySet());
            }
        }
        finally {
            stop.set(true);

            if (fut != null)
                fut.get();
        }
    }

    /**
     * @param aff Cache affinity.
     * @param node Primary node for keys.
     * @param data Map where keys/values should be put to.
     */
    private void addKeysForNode(CacheAffinity<String> aff, ClusterNode node, Map<String, Integer> data) {
        int cntr = 0;

        for (int i = 0; i < 100_000; i++) {
            String key = String.valueOf(i);

            if (aff.isPrimary(node, key)) {
                data.put(key, i);

                cntr++;

                if (cntr == 500)
                    break;
            }
        }

        assertEquals(500, cntr);
    }
}
