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

package org.apache.ignite.internal.processors.cache;

import org.apache.ignite.cache.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.cache.distributed.dht.*;
import org.apache.ignite.internal.processors.cache.distributed.near.*;
import org.apache.ignite.testframework.junits.common.*;
import org.apache.ignite.transactions.*;

import static org.apache.ignite.transactions.TransactionConcurrency.*;
import static org.apache.ignite.transactions.TransactionIsolation.*;

/**
 * Tests .
 */
public class IgnitePutAllUpdateNonPreloadedPartitionSelfTest extends GridCommonAbstractTest {
    /** Grid count. */
    private static final int GRID_CNT = 4;

    /** Backups. */
    private int backups = 1;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        cfg.setCacheConfiguration(cacheConfiguration(gridName));

        return cfg;
    }

    /**
     * @param gridName Grid name.
     * @return Test cache configuration.
     */
    public CacheConfiguration cacheConfiguration(String gridName) {
        CacheConfiguration ccfg = defaultCacheConfiguration();

        ccfg.setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL);
        ccfg.setBackups(backups);
        ccfg.setDistributionMode(CacheDistributionMode.PARTITIONED_ONLY);
        ccfg.setCacheMode(CacheMode.PARTITIONED);

        ccfg.setPreloadPartitionedDelay(-1);

        return ccfg;
    }

    /**
     * @throws Exception If failed.
     */
    public void testPessimistic() throws Exception {
        backups = 2;

        startGrids(GRID_CNT - 1);

        try {
            for (int i = 0; i < GRID_CNT - 1; i++)
                ((IgniteKernal)grid(i)).cache(null).forceRepartition().get();

            startGrid(GRID_CNT - 1);

            GridCache<Object, Object> cache = ((IgniteKernal)grid(0)).cache(null);

            final int keyCnt = 100;

            try (Transaction tx = cache.txStart(OPTIMISTIC, REPEATABLE_READ)) {
                for (int k = 0; k < keyCnt; k++)
                    cache.get(k);

                for (int k = 0; k < keyCnt; k++)
                    cache.put(k, k);

                tx.commit();
            }

            //  Check that no stale transactions left and all locks are released.
            for (int g = 0; g < GRID_CNT; g++) {
                IgniteKernal k = (IgniteKernal)grid(g);

                GridCacheAdapter<Object, Object> cacheAdapter = k.context().cache().internalCache();

                assertEquals(0, cacheAdapter.context().tm().idMapSize());

                for (int i = 0; i < keyCnt; i++) {
                    if (cacheAdapter.isNear()) {
                        GridDhtCacheEntry<Object, Object> entry = ((GridNearCacheAdapter<Object, Object>)cacheAdapter)
                            .dht().peekExx(i);

                        if (entry != null) {
                            assertFalse(entry.lockedByAny());
                            assertTrue(entry.localCandidates().isEmpty());
                            assertTrue(entry.remoteMvccSnapshot().isEmpty());
                        }
                    }

                    GridCacheEntryEx<Object, Object> entry = cacheAdapter.peekEx(i);

                    if (entry != null) {
                        assertFalse(entry.lockedByAny());
                        assertTrue(entry.localCandidates().isEmpty());
                        assertTrue(entry.remoteMvccSnapshot().isEmpty());
                    }
                }
            }
        }
        finally {
            stopAllGrids();
        }
    }
}
