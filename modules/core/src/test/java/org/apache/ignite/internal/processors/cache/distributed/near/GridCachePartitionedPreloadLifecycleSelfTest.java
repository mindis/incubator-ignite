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

package org.apache.ignite.internal.processors.cache.distributed.near;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.cache.distributed.*;
import org.apache.ignite.internal.processors.cache.query.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.lifecycle.*;
import org.apache.ignite.resources.*;
import org.apache.ignite.transactions.*;

import javax.cache.*;
import java.util.*;

import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.cache.CachePreloadMode.*;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.*;

/**
 * Tests for replicated cache preloader.
 */
@SuppressWarnings({"PublicInnerClass"})
public class GridCachePartitionedPreloadLifecycleSelfTest extends GridCachePreloadLifecycleAbstractTest {
    /** Grid count. */
    private int gridCnt = 5;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration c = super.getConfiguration(gridName);

        c.getTransactionConfiguration().setDefaultTxConcurrency(TransactionConcurrency.OPTIMISTIC);
        c.getTransactionConfiguration().setDefaultTxIsolation(TransactionIsolation.READ_COMMITTED);

        CacheConfiguration cc1 = defaultCacheConfiguration();

        cc1.setName("one");
        cc1.setCacheMode(PARTITIONED);
        cc1.setBackups(1);
        cc1.setWriteSynchronizationMode(FULL_SYNC);
        cc1.setPreloadMode(preloadMode);
        cc1.setEvictionPolicy(null);
        cc1.setSwapEnabled(false);
        cc1.setCacheStoreFactory(null);
        cc1.setEvictionPolicy(null);
        cc1.setNearEvictionPolicy(null);

        // Identical configuration.
        CacheConfiguration cc2 = new CacheConfiguration(cc1);

        cc2.setName("two");

        c.setCacheConfiguration(cc1, cc2);

        return c;
    }

    /**
     * @param keys Keys.
     * @return Lifecycle bean.
     */
    private LifecycleBean lifecycleBean(final Object[] keys) {
        return new LifecycleBean() {
            @IgniteInstanceResource
            private Ignite ignite;

            @Override public void onLifecycleEvent(LifecycleEventType evt) {
                switch (evt) {
                    case AFTER_GRID_START: {
                        IgniteCache<Object, MyValue> c1 = ignite.jcache("one");
                        IgniteCache<Object, MyValue> c2 = ignite.jcache("two");

                        if (!ignite.name().contains("Test0")) {
                            info("Keys already in cache:");

                            for (Object k : entrySet(c1))
                                info("Cache1: " + k.toString());

                            for (Object k : entrySet(c2))
                                info("Cache2: " + k.toString());

                            return;
                        }

                        info("Populating cache data...");

                        int i = 0;

                        for (Object key : keys) {
                            c1.put(key, new MyValue(value(key)));

                            if (i++ % 2 == 0)
                                c2.put(key, new MyValue(value(key)));
                        }

                        assert c1.size() == keys.length : "Invalid cache1 size [size=" + c1.size() + ']';
                        assert c2.size() == keys.length / 2 : "Invalid cache2 size [size=" + c2.size() + ']';

                        break;
                    }

                    case BEFORE_GRID_START:
                    case BEFORE_GRID_STOP:
                    case AFTER_GRID_STOP: {
                        info("Lifecycle event: " + evt);

                        break;
                    }
                }
            }
        };
    }

    /**
     * @param keys Keys.
     * @throws Exception If failed.
     */
    public void checkCache(Object[] keys) throws Exception {
        preloadMode = SYNC;

        lifecycleBean = lifecycleBean(keys);

        for (int i = 0; i < gridCnt; i++) {
            startGrid(i);

            info("Checking '" + (i + 1) + "' nodes...");

            for (int j = 0; j < G.allGrids().size(); j++) {
                IgniteCache<Object, MyValue> c1 = grid(j).jcache("one");
                IgniteCache<Object, MyValue> c2 = grid(j).jcache("two");

                int k = 0;

                for (Object key : keys) {
                    assertNotNull(c1.get(key));

                    if (k++ % 2 == 0)
                        assertNotNull("Value is null for key: " + key, c2.get(key));
                }
            }
        }
    }

    /**
     * @param keys Keys.
     * @throws Exception If failed.
     */
    public void checkScanQuery(Object[] keys) throws Exception {
        preloadMode = SYNC;

        lifecycleBean = lifecycleBean(keys);

        for (int i = 0; i < gridCnt; i++) {
            startGrid(i);

            info("Checking '" + (i + 1) + "' nodes...");

            for (int j = 0; j < G.allGrids().size(); j++) {
                GridCache<Object, MyValue> c2 = ((IgniteKernal)grid(j)).cache("two");

                CacheQuery<Map.Entry<Object, MyValue>> qry = c2.queries().createScanQuery(null);

                int totalCnt = F.sumInt(qry.execute(new IgniteReducer<Map.Entry<Object, MyValue>, Integer>() {
                    @IgniteInstanceResource
                    private Ignite grid;

                    private int cnt;

                    @Override public boolean collect(Map.Entry<Object, MyValue> e) {
                        Object key = e.getKey();

                        assertNotNull(e.getValue());

                        try {
                            Object v1 = e.getValue();
                            Object v2 = grid.jcache("one").get(key);

                            assertNotNull(v2);
                            assertEquals(v1, v2);
                        }
                        catch (CacheException e1) {
                            e1.printStackTrace();

                            assert false;
                        }

                        cnt++;

                        return true;
                    }

                    @Override public Integer reduce() {
                        return cnt;
                    }
                }).get());

                info("Total entry count [grid=" + j + ", totalCnt=" + totalCnt + ']');

                assertEquals(keys.length / 2, totalCnt);
            }
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testLifecycleBean1() throws Exception {
        checkCache(keys(true, DFLT_KEYS.length, DFLT_KEYS));
    }

    /**
     * @throws Exception If failed.
     */
    public void testLifecycleBean2() throws Exception {
        checkCache(keys(false, DFLT_KEYS.length, DFLT_KEYS));
    }

    /**
     * @throws Exception If failed.
     */
    public void testLifecycleBean3() throws Exception {
        checkCache(keys(true, 500));
    }

    /**
     * @throws Exception If failed.
     */
    public void testLifecycleBean4() throws Exception {
        checkCache(keys(false, 500));
    }

    /**
     * @throws Exception If failed.
     */
    public void testScanQuery1() throws Exception {
        checkScanQuery(keys(true, DFLT_KEYS.length, DFLT_KEYS));
    }

    /**
     * @throws Exception If failed.
     */
    public void testScanQuery2() throws Exception {
        checkScanQuery(keys(false, DFLT_KEYS.length, DFLT_KEYS));
    }

    /**
     * @throws Exception If failed.
     */
    public void testScanQuery3() throws Exception {
        checkScanQuery(keys(true, 500));
    }

    /**
     * @throws Exception If failed.
     */
    public void testScanQuery4() throws Exception {
        checkScanQuery(keys(false, 500));
    }
}
