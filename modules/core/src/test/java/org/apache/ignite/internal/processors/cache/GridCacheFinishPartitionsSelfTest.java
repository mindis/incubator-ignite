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
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.testframework.*;
import org.apache.ignite.transactions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

import static org.apache.ignite.cache.CacheAtomicityMode.*;
import static org.apache.ignite.cache.CacheDistributionMode.*;
import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.transactions.TransactionConcurrency.*;
import static org.apache.ignite.transactions.TransactionIsolation.*;

/**
 * Abstract class for cache tests.
 */
public class GridCacheFinishPartitionsSelfTest extends GridCacheAbstractSelfTest {
    /** */
    private static final int GRID_CNT = 1;

    /** Grid kernal. */
    private IgniteKernal grid;

    /** {@inheritDoc} */
    @Override protected int gridCount() {
        return GRID_CNT;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        grid = (IgniteKernal)grid(0);
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        grid = null;
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration c = super.getConfiguration(gridName);

        CacheConfiguration cc = defaultCacheConfiguration();

        cc.setCacheMode(PARTITIONED);
        cc.setBackups(1);
        cc.setAtomicityMode(TRANSACTIONAL);
        cc.setDistributionMode(NEAR_PARTITIONED);

        c.setCacheConfiguration(cc);

        return c;
    }

    /**
     * @throws Exception If failed.
     */
    public void testTxFinishPartitions() throws Exception {
        String key = "key";
        String val = "value";

        IgniteCache<String, String> cache = grid.jcache(null);

        int keyPart = grid.<String, String>internalCache().context().affinity().partition(key);

        cache.put(key, val);

        // Wait for tx-enlisted partition.
        long waitTime = runTransactions(key, keyPart, F.asList(keyPart));

        info("Wait time, ms: " + waitTime);

        // Wait for not enlisted partition.
        waitTime = runTransactions(key, keyPart, F.asList(keyPart + 1));

        info("Wait time, ms: " + waitTime);

        // Wait for both partitions.
        waitTime = runTransactions(key, keyPart, F.asList(keyPart, keyPart + 1));

        info("Wait time, ms: " + waitTime);
    }

    /**
     * @param key Key.
     * @param keyPart Key partition.
     * @param waitParts Partitions to wait.
     * @return Wait time.
     * @throws Exception If failed.
     */
    private long runTransactions(final String key, final int keyPart, final Collection<Integer> waitParts)
        throws Exception {
        int threadNum = 1;

        final CyclicBarrier barrier = new CyclicBarrier(threadNum);
        final CountDownLatch latch = new CountDownLatch(threadNum);

        final AtomicLong start = new AtomicLong();

        GridTestUtils.runMultiThreaded(new Callable() {
            @Override public Object call() throws Exception {
                if (barrier.await() == 0)
                    start.set(System.currentTimeMillis());

                IgniteCache<String, String> cache = grid(0).jcache(null);

                Transaction tx = grid(0).transactions().txStart(PESSIMISTIC, REPEATABLE_READ);

                cache.get(key);

                IgniteInternalFuture<?> fut = grid.context().cache().context().partitionReleaseFuture(GRID_CNT + 1);

                fut.listenAsync(new CI1<IgniteInternalFuture<?>>() {
                    @Override public void apply(IgniteInternalFuture<?> e) {
                        latch.countDown();
                    }
                });

                assert !fut.isDone() : "Failed waiting for locks " +
                    "[keyPart=" + keyPart + ", waitParts=" + waitParts + ", done=" + fut.isDone() + ']';

                tx.commit();

                return null;
            }
        }, threadNum, "test-finish-partitions-thread");

        latch.await();

        return System.currentTimeMillis() - start.get();
    }

    /**
     * Tests method {@link GridCacheMvccManager#finishLocks(org.apache.ignite.lang.IgnitePredicate, long)}.
     *
     * @throws Exception If failed.
     */
    public void testMvccFinishPartitions() throws Exception {
        String key = "key";

        int keyPart = grid.internalCache().context().affinity().partition(key);

        // Wait for tx-enlisted partition.
        long waitTime = runLock(key, keyPart, F.asList(keyPart));

        info("Wait time, ms: " + waitTime);

        // Wait for not enlisted partition.
        waitTime = runLock(key, keyPart, F.asList(keyPart + 1));

        info("Wait time, ms: " + waitTime);

        // Wait for both partitions.
        waitTime = runLock(key, keyPart, F.asList(keyPart, keyPart + 1));

        info("Wait time, ms: " + waitTime);
    }

    /**
     * Tests finish future for particular set of keys.
     *
     * @throws Exception If failed.
     */
    public void testMvccFinishKeys() throws Exception {
        IgniteCache<String, Integer> cache = grid(0).jcache(null);

        try (Transaction tx = grid(0).transactions().txStart(PESSIMISTIC, REPEATABLE_READ)) {
            final String key = "key";

            cache.get(key);

            GridCacheAdapter<String, Integer> internal = grid.internalCache();

            IgniteInternalFuture<?> nearFut = internal.context().mvcc().finishKeys(Collections.singletonList(key), 2);

            IgniteInternalFuture<?> dhtFut = internal.context().near().dht().context().mvcc().finishKeys(
                Collections.singletonList(key), 2);

            assert !nearFut.isDone();
            assert !dhtFut.isDone();

            tx.commit();
        }
    }

    /**
     * Tests chained locks and partitions release future.
     *
     * @throws Exception If failed.
     */
    public void testMvccFinishPartitionsContinuousLockAcquireRelease() throws Exception {
        int key = 1;

        GridCacheSharedContext<Object, Object> ctx = grid.context().cache().context();

        final AtomicLong end = new AtomicLong(0);

        final CountDownLatch latch = new CountDownLatch(1);

        IgniteCache<Integer, String> cache = grid.jcache(null);

        Lock lock = cache.lock(key);

        lock.lock();

        long start = System.currentTimeMillis();

        info("Start time: " + start);

        IgniteInternalFuture<?> fut = ctx.partitionReleaseFuture(GRID_CNT + 1);

        assert fut != null;

        fut.listenAsync(new CI1<IgniteInternalFuture<?>>() {
            @Override public void apply(IgniteInternalFuture<?> e) {
                end.set(System.currentTimeMillis());

                latch.countDown();

                info("End time: " + end.get());
            }
        });

        Lock lock1 = cache.lock(key + 1);

        lock1.lock();

        lock.unlock();

        Lock lock2 = cache.lock(key + 2);

        lock2.lock();

        lock1.unlock();

        assert !fut.isDone() : "Failed waiting for locks";

        lock2.unlock();

        latch.await();
    }

    /**
     * @param key Key.
     * @param keyPart Key partition.
     * @param waitParts Partitions to wait.
     * @return Wait time.
     * @throws Exception If failed.
     */
    private long runLock(String key, int keyPart, Collection<Integer> waitParts) throws Exception {

        GridCacheSharedContext<Object, Object> ctx = grid.context().cache().context();

        final AtomicLong end = new AtomicLong(0);

        final CountDownLatch latch = new CountDownLatch(1);

        IgniteCache<String, String> cache = grid.jcache(null);

        Lock lock = cache.lock(key);

        lock.lock();

        long start;
        try {
            start = System.currentTimeMillis();

            info("Start time: " + start);

            IgniteInternalFuture<?> fut = ctx.partitionReleaseFuture(GRID_CNT + 1);

            assert fut != null;

            fut.listenAsync(new CI1<IgniteInternalFuture<?>>() {
                @Override public void apply(IgniteInternalFuture<?> e) {
                    end.set(System.currentTimeMillis());

                    latch.countDown();

                    info("End time: " + end.get());
                }
            });

            assert !fut.isDone() : "Failed waiting for locks [keyPart=" + keyPart + ", waitParts=" + waitParts + ", done="
                + fut.isDone() + ']';
        }
        finally {
            lock.unlock();
        }

        latch.await();

        return end.get() - start;
    }
}
