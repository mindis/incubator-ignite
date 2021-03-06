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

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.cache.version.*;
import org.apache.ignite.transactions.*;
import org.jetbrains.annotations.*;

import static org.apache.ignite.cache.CacheAtomicWriteOrderMode.*;
import static org.apache.ignite.cache.CacheAtomicityMode.*;
import static org.apache.ignite.cache.CacheDistributionMode.*;
import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.transactions.TransactionConcurrency.*;
import static org.apache.ignite.transactions.TransactionIsolation.*;

/**
 *
 */
public class GridCacheVersionMultinodeTest extends GridCacheAbstractSelfTest {
    /** */
    private CacheAtomicityMode atomicityMode;

    /** */
    private CacheAtomicWriteOrderMode atomicWriteOrder;

    /** {@inheritDoc} */
    @Override protected int gridCount() {
        return 3;
    }

    /** {@inheritDoc} */
    @Override protected CacheConfiguration cacheConfiguration(String gridName) throws Exception {
        CacheConfiguration ccfg = super.cacheConfiguration(gridName);

        assert atomicityMode != null;

        ccfg.setAtomicityMode(atomicityMode);

        if (atomicityMode == null) {
            assert atomicityMode != null;

            ccfg.setAtomicWriteOrderMode(atomicWriteOrder);
        }

        return ccfg;
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
    @Override protected void beforeTestsStarted() throws Exception {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();
    }

    /**
     * @throws Exception If failed.
     */
    public void testVersionTx() throws Exception {
        atomicityMode = TRANSACTIONAL;

        checkVersion();
    }

    /**
     * @throws Exception If failed.
     */
    public void testVersionAtomicClock() throws Exception {
        atomicityMode = ATOMIC;

        atomicWriteOrder = CLOCK;

        checkVersion();
    }

    /**
     * @throws Exception If failed.
     */
    public void testVersionAtomicPrimary() throws Exception {
        atomicityMode = ATOMIC;

        atomicWriteOrder = PRIMARY;

        checkVersion();
    }

    /**
     * @throws Exception If failed.
     */
    private void checkVersion() throws Exception {
        super.beforeTestsStarted();

        for (int i = 0; i < 100; i++) {
            checkVersion(String.valueOf(i), null); // Create.

            checkVersion(String.valueOf(i), null); // Update.
        }

        if (atomicityMode == TRANSACTIONAL) {
            for (int i = 100; i < 200; i++) {
                checkVersion(String.valueOf(i), PESSIMISTIC); // Create.

                checkVersion(String.valueOf(i), PESSIMISTIC); // Update.
            }

            for (int i = 200; i < 300; i++) {
                checkVersion(String.valueOf(i), OPTIMISTIC); // Create.

                checkVersion(String.valueOf(i), OPTIMISTIC); // Update.
            }
        }
    }

    /**
     * @param key Key.
     * @param txMode Non null tx mode if explicit transaction should be started.
     * @throws Exception If failed.
     */
    private void checkVersion(String key, @Nullable TransactionConcurrency txMode) throws Exception {
        IgniteCache<String, Integer> cache = jcache(0);

        Transaction tx = null;

        if (txMode != null)
            tx = cache.unwrap(Ignite.class).transactions().txStart(txMode, REPEATABLE_READ);

        try {
            cache.put(key, 1);

            if (tx != null)
                tx.commit();
        }
        finally {
            if (tx != null)
                tx.close();
        }

        checkEntryVersion(key);
    }

    /**
     * @param key Key.
     * @throws Exception If failed.
     */
    private void checkEntryVersion(String key) throws Exception {
        GridCacheVersion ver = null;

        boolean verified = false;

        for (int i = 0; i < gridCount(); i++) {
            IgniteKernal grid = (IgniteKernal)grid(i);

            GridCacheAdapter<Object, Object> cache = grid.context().cache().internalCache();

            if (cache.affinity().isPrimaryOrBackup(grid.localNode(), key)) {
                GridCacheEntryEx<Object, Object> e = cache.peekEx(key);

                assertNotNull(e);

                if (ver != null) {
                    assertEquals("Non-equal versions for key " + key, ver, e.version());

                    verified = true;
                }
                else
                    ver = e.version();
            }
        }

        assertTrue(verified);
    }
}
