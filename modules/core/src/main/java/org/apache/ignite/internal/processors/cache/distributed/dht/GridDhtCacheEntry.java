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

package org.apache.ignite.internal.processors.cache.distributed.dht;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.processors.cache.distributed.*;
import org.apache.ignite.internal.processors.cache.transactions.*;
import org.apache.ignite.internal.processors.cache.version.*;
import org.apache.ignite.internal.util.lang.*;
import org.apache.ignite.internal.util.tostring.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.jetbrains.annotations.*;

import java.util.*;

/**
 * Replicated cache entry.
 */
@SuppressWarnings({"TooBroadScope", "NonPrivateFieldAccessedInSynchronizedContext"})
public class GridDhtCacheEntry<K, V> extends GridDistributedCacheEntry<K, V> {
    /** Size overhead. */
    private static final int DHT_SIZE_OVERHEAD = 16;

    /** Gets node value from reader ID. */
    private static final IgniteClosure<ReaderId, UUID> R2N = new C1<ReaderId, UUID>() {
        @Override public UUID apply(ReaderId e) {
            return e.nodeId();
        }
    };

    /** Reader clients. */
    @GridToStringInclude
    private volatile List<ReaderId<K, V>> rdrs = Collections.emptyList();

    /** Local partition. */
    private final GridDhtLocalPartition<K, V> locPart;

    /**
     * @param ctx Cache context.
     * @param topVer Topology version at the time of creation (if negative, then latest topology is assumed).
     * @param key Cache key.
     * @param hash Key hash value.
     * @param val Entry value.
     * @param next Next entry in the linked list.
     * @param ttl Time to live.
     * @param hdrId Header id.
     */
    public GridDhtCacheEntry(GridCacheContext<K, V> ctx, long topVer, K key, int hash, V val,
        GridCacheMapEntry<K, V> next, long ttl, int hdrId) {
        super(ctx, key, hash, val, next, ttl, hdrId);

        // Record this entry with partition.
        locPart = ctx.dht().topology().onAdded(topVer, this);
    }

    /** {@inheritDoc} */
    @Override public int memorySize() throws IgniteCheckedException {
        int rdrsOverhead = 0;

        synchronized (this) {
            if (rdrs != null)
                rdrsOverhead += ReaderId.READER_ID_SIZE * rdrs.size();
        }

        return super.memorySize() + DHT_SIZE_OVERHEAD + rdrsOverhead;
    }

    /** {@inheritDoc} */
    @Override public int partition() {
        return locPart.id();
    }

    /** {@inheritDoc} */
    @Override public boolean isDht() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public boolean partitionValid() {
        return locPart.valid();
    }

    /** {@inheritDoc} */
    @Override public void onMarkedObsolete() {
        assert !Thread.holdsLock(this);

        // Remove this entry from partition mapping.
        cctx.dht().topology().onRemoved(this);
    }

    /**
     * @param nearVer Near version.
     * @param rmv If {@code true}, then add to removed list if not found.
     * @return Local candidate by near version.
     * @throws GridCacheEntryRemovedException If removed.
     */
    @Nullable public synchronized GridCacheMvccCandidate<K> localCandidateByNearVersion(GridCacheVersion nearVer,
        boolean rmv) throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc<K> mvcc = mvccExtras();

        if (mvcc != null) {
            for (GridCacheMvccCandidate<K> c : mvcc.localCandidatesNoCopy(false)) {
                GridCacheVersion ver = c.otherVersion();

                if (ver != null && ver.equals(nearVer))
                    return c;
            }
        }

        if (rmv)
            addRemoved(nearVer);

        return null;
    }

    /**
     * Add local candidate.
     *
     * @param nearNodeId Near node ID.
     * @param nearVer Near version.
     * @param topVer Topology version.
     * @param threadId Owning thread ID.
     * @param ver Lock version.
     * @param timeout Timeout to acquire lock.
     * @param reenter Reentry flag.
     * @param tx Tx flag.
     * @param implicitSingle Implicit flag.
     * @return New candidate.
     * @throws GridCacheEntryRemovedException If entry has been removed.
     * @throws GridDistributedLockCancelledException If lock was cancelled.
     */
    @Nullable public GridCacheMvccCandidate<K> addDhtLocal(
        UUID nearNodeId,
        GridCacheVersion nearVer,
        long topVer,
        long threadId,
        GridCacheVersion ver,
        long timeout,
        boolean reenter,
        boolean tx,
        boolean implicitSingle) throws GridCacheEntryRemovedException, GridDistributedLockCancelledException {
        GridCacheMvccCandidate<K> cand;
        GridCacheMvccCandidate<K> prev;
        GridCacheMvccCandidate<K> owner;

        V val;

        synchronized (this) {
            // Check removed locks prior to obsolete flag.
            checkRemoved(ver);
            checkRemoved(nearVer);

            checkObsolete();

            GridCacheMvcc<K> mvcc = mvccExtras();

            if (mvcc == null) {
                mvcc = new GridCacheMvcc<>(cctx);

                mvccExtras(mvcc);
            }

            prev = mvcc.anyOwner();

            boolean emptyBefore = mvcc.isEmpty();

            cand = mvcc.addLocal(
                this,
                nearNodeId,
                nearVer,
                threadId,
                ver,
                timeout,
                reenter,
                tx,
                implicitSingle,
                /*dht-local*/true
            );

            if (cand == null)
                return null;

            cand.topologyVersion(topVer);

            owner = mvcc.anyOwner();

            if (owner != null)
                cand.ownerVersion(owner.version());

            boolean emptyAfter = mvcc.isEmpty();

            checkCallbacks(emptyBefore, emptyAfter);

            val = this.val;

            if (mvcc != null && mvcc.isEmpty())
                mvccExtras(null);
        }

        // Don't link reentries.
        if (cand != null && !cand.reentry())
            // Link with other candidates in the same thread.
            cctx.mvcc().addNext(cctx, cand);

        checkOwnerChanged(prev, owner, val);

        return cand;
    }

    /** {@inheritDoc} */
    @Override public boolean tmLock(IgniteInternalTx<K, V> tx, long timeout)
        throws GridCacheEntryRemovedException, GridDistributedLockCancelledException {
        if (tx.local()) {
            GridDhtTxLocalAdapter<K, V> dhtTx = (GridDhtTxLocalAdapter<K, V>)tx;

            // Null is returned if timeout is negative and there is other lock owner.
            return addDhtLocal(
                dhtTx.nearNodeId(),
                dhtTx.nearXidVersion(),
                tx.topologyVersion(),
                tx.threadId(),
                tx.xidVersion(),
                timeout,
                /*reenter*/false,
                /*tx*/true,
                tx.implicitSingle()) != null;
        }

        try {
            addRemote(
                tx.nodeId(),
                tx.otherNodeId(),
                tx.threadId(),
                tx.xidVersion(),
                tx.timeout(),
                /*tx*/true,
                tx.implicit(),
                null);

            return true;
        }
        catch (GridDistributedLockCancelledException ignored) {
            if (log.isDebugEnabled())
                log.debug("Attempted to enter tx lock for cancelled ID (will ignore): " + tx);

            return false;
        }
    }

    /** {@inheritDoc} */
    @Override public GridCacheMvccCandidate<K> removeLock() {
        GridCacheMvccCandidate<K> ret = super.removeLock();

        locPart.onUnlock();

        return ret;
    }

    /** {@inheritDoc} */
    @Override public boolean removeLock(GridCacheVersion ver) throws GridCacheEntryRemovedException {
        boolean ret = super.removeLock(ver);

        locPart.onUnlock();

        return ret;
    }

    /**
     * Calls {@link GridDhtLocalPartition#onUnlock()} for this entry's partition.
     */
    public void onUnlock() {
        locPart.onUnlock();
    }

    /**
     * @param topVer Topology version.
     * @return Tuple with version and value of this entry, or {@code null} if entry is new.
     * @throws GridCacheEntryRemovedException If entry has been removed.
     */
    @SuppressWarnings({"NonPrivateFieldAccessedInSynchronizedContext"})
    @Nullable public synchronized GridTuple3<GridCacheVersion, V, byte[]> versionedValue(long topVer)
        throws GridCacheEntryRemovedException {
        if (isNew() || !valid(-1) || deletedUnlocked())
            return null;
        else {
            V val0 = null;
            byte[] valBytes0 = null;

            GridCacheValueBytes valBytesTuple = valueBytesUnlocked();

            if (!valBytesTuple.isNull()) {
                if (valBytesTuple.isPlain())
                    val0 = (V)valBytesTuple.get();
                else
                    valBytes0 = valBytesTuple.get();
            }
            else
                val0 = val;

            return F.t(ver, val0, valBytes0);
        }
    }

    /**
     * @return Readers.
     * @throws GridCacheEntryRemovedException If removed.
     */
    public Collection<UUID> readers() throws GridCacheEntryRemovedException {
        return F.viewReadOnly(checkReaders(), R2N);
    }

    /**
     * @param nodeId Node ID.
     * @return reader ID.
     */
    @Nullable public ReaderId<K, V> readerId(UUID nodeId) {
        for (ReaderId<K, V> reader : rdrs)
            if (reader.nodeId().equals(nodeId))
                return reader;

        return null;
    }

    /**
     * @param nodeId Reader to add.
     * @param msgId Message ID.
     * @param topVer Topology version.
     * @return Future for all relevant transactions that were active at the time of adding reader,
     *      or {@code null} if reader was added
     * @throws GridCacheEntryRemovedException If entry was removed.
     */
    @SuppressWarnings("unchecked")
    @Nullable public IgniteInternalFuture<Boolean> addReader(UUID nodeId, long msgId, long topVer)
        throws GridCacheEntryRemovedException {
        // Don't add local node as reader.
        if (cctx.nodeId().equals(nodeId))
            return null;

        ClusterNode node = cctx.discovery().node(nodeId);

        if (node == null) {
            if (log.isDebugEnabled())
                log.debug("Ignoring near reader because node left the grid: " + nodeId);

            return null;
        }

        // If remote node has no near cache, don't add it.
        if (!U.hasNearCache(node, cacheName())) {
            if (log.isDebugEnabled())
                log.debug("Ignoring near reader because near cache is disabled: " + nodeId);

            return null;
        }

        // If remote node is (primary?) or back up, don't add it as a reader.
        if (cctx.affinity().belongs(node, partition(), topVer)) {
            if (log.isDebugEnabled())
                log.debug("Ignoring near reader because remote node is affinity node [locNodeId=" + cctx.localNodeId()
                    + ", rmtNodeId=" + nodeId + ", key=" + key + ']');

            return null;
        }

        boolean ret = false;

        GridCacheMultiTxFuture<K, V> txFut = null;

        Collection<GridCacheMvccCandidate<K>> cands = null;

        ReaderId<K, V> reader;

        synchronized (this) {
            checkObsolete();

            reader = readerId(nodeId);

            if (reader == null) {
                reader = new ReaderId<>(nodeId, msgId);

                List<ReaderId<K, V>> rdrs = new ArrayList<>(this.rdrs.size() + 1);

                rdrs.addAll(this.rdrs);
                rdrs.add(reader);

                // Seal.
                this.rdrs = Collections.unmodifiableList(rdrs);

                // No transactions in ATOMIC cache.
                if (!cctx.atomic()) {
                    txFut = reader.getOrCreateTxFuture(cctx);

                    cands = localCandidates();

                    ret = true;
                }
            }
            else {
                txFut = reader.txFuture();

                long id = reader.messageId();

                if (id < msgId)
                    reader.messageId(msgId);
            }
        }

        if (ret) {
            assert txFut != null;

            if (!F.isEmpty(cands)) {
                for (GridCacheMvccCandidate<K> c : cands) {
                    IgniteInternalTx<K, V> tx = cctx.tm().tx(c.version());

                    if (tx != null) {
                        assert tx.local();

                        txFut.addTx(tx);
                    }
                }
            }

            txFut.init();

            if (!txFut.isDone()) {
                final ReaderId<K, V> reader0 = reader;

                txFut.listenAsync(new CI1<IgniteInternalFuture<?>>() {
                    @Override public void apply(IgniteInternalFuture<?> f) {
                        synchronized (this) {
                            // Release memory.
                            reader0.resetTxFuture();
                        }
                    }
                });
            }
            else {
                synchronized (this) {
                    // Release memory.
                    reader.resetTxFuture();
                }

                txFut = null;
            }
        }

        return txFut;
    }

    /**
     * @param nodeId Reader to remove.
     * @param msgId Message ID.
     * @return {@code True} if reader was removed as a result of this operation.
     * @throws GridCacheEntryRemovedException If entry was removed.
     */
    public synchronized boolean removeReader(UUID nodeId, long msgId) throws GridCacheEntryRemovedException {
        checkObsolete();

        ReaderId reader = readerId(nodeId);

        if (reader == null || (reader.messageId() > msgId && msgId >= 0))
            return false;

        List<ReaderId<K, V>> rdrs = new ArrayList<>(this.rdrs.size());

        for (ReaderId<K, V> rdr : this.rdrs) {
            if (!rdr.equals(reader))
                rdrs.add(rdr);
        }

        // Seal.
        this.rdrs = rdrs.isEmpty() ? Collections.<ReaderId<K, V>>emptyList() : Collections.unmodifiableList(rdrs);

        return true;
    }

    /**
     * Clears all readers (usually when partition becomes invalid and ready for eviction).
     */
    @Override public synchronized void clearReaders() {
        rdrs = Collections.emptyList();
    }

    /** {@inheritDoc} */
    @Override public synchronized void clearReader(UUID nodeId) throws GridCacheEntryRemovedException {
        removeReader(nodeId, -1);
    }

    /**
     * Marks entry as obsolete and, if possible or required, removes it
     * from swap storage.
     *
     * @param ver Obsolete version.
     * @param swap If {@code true} then remove from swap.
     * @return {@code True} if entry was not being used, passed the filter and could be removed.
     * @throws IgniteCheckedException If failed to remove from swap.
     */
    public boolean clearInternal(GridCacheVersion ver, boolean swap) throws IgniteCheckedException {
        boolean rmv = false;

        try {
            synchronized (this) {
                V prev = saveValueForIndexUnlocked();

                // Call markObsolete0 to avoid recursive calls to clear if
                // we are clearing dht local partition (onMarkedObsolete should not be called).
                if (!markObsolete0(ver, false)) {
                    if (log.isDebugEnabled())
                        log.debug("Entry could not be marked obsolete (it is still used or has readers): " + this);

                    return false;
                }

                rdrs = Collections.emptyList();

                if (log.isDebugEnabled())
                    log.debug("Entry has been marked obsolete: " + this);

                clearIndex(prev);

                // Give to GC.
                update(null, null, 0L, 0L, ver);

                if (swap) {
                    releaseSwap();

                    if (log.isDebugEnabled())
                        log.debug("Entry has been cleared from swap storage: " + this);
                }

                rmv = true;

                return true;
            }
        }
        finally {
            if (rmv)
                cctx.cache().removeIfObsolete(key); // Clear cache.
        }
    }

    /**
     * @return Collection of readers after check.
     * @throws GridCacheEntryRemovedException If removed.
     */
    public synchronized Collection<ReaderId<K, V>> checkReaders() throws GridCacheEntryRemovedException {
        checkObsolete();

        if (!rdrs.isEmpty()) {
            Collection<ReaderId> rmv = null;

            for (ReaderId reader : rdrs) {
                if (!cctx.discovery().alive(reader.nodeId())) {
                    if (rmv == null)
                        rmv = new HashSet<>();

                    rmv.add(reader);
                }
            }

            if (rmv != null) {
                List<ReaderId<K, V>> rdrs = new ArrayList<>(this.rdrs.size() - rmv.size());

                for (ReaderId<K, V> rdr : this.rdrs) {
                    if (!rmv.contains(rdr))
                        rdrs.add(rdr);
                }

                // Seal.
                this.rdrs = rdrs.isEmpty() ? Collections.<ReaderId<K, V>>emptyList() :
                    Collections.unmodifiableList(rdrs);
            }
        }

        return rdrs;
    }

    /** {@inheritDoc} */
    @Override protected synchronized boolean hasReaders() throws GridCacheEntryRemovedException {
        checkReaders();

        return !rdrs.isEmpty();
    }

    /**
     * Sets mappings into entry.
     *
     * @param ver Version.
     * @return Candidate, if one existed for the version, or {@code null} if candidate was not found.
     * @throws GridCacheEntryRemovedException If removed.
     */
    @Nullable public synchronized GridCacheMvccCandidate<K> mappings(GridCacheVersion ver)
        throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc<K> mvcc = mvccExtras();

        return mvcc == null ? null : mvcc.candidate(ver);
    }

    /**
     * @return Cache name.
     */
    protected String cacheName() {
        return cctx.dht().near().name();
    }

    /** {@inheritDoc} */
    @Override public synchronized String toString() {
        return S.toString(GridDhtCacheEntry.class, this, "super", super.toString());
    }

    /**
     * Reader ID.
     */
    private static class ReaderId<K, V> {
        /** Reader ID size. */
        private static final int READER_ID_SIZE = 24;

        /** Node ID. */
        private UUID nodeId;

        /** Message ID. */
        private long msgId;

        /** Transaction future. */
        private GridCacheMultiTxFuture<K, V> txFut;

        /**
         * @param nodeId Node ID.
         * @param msgId Message ID.
         */
        ReaderId(UUID nodeId, long msgId) {
            this.nodeId = nodeId;
            this.msgId = msgId;
        }

        /**
         * @return Node ID.
         */
        UUID nodeId() {
            return nodeId;
        }

        /**
         * @return Message ID.
         */
        long messageId() {
            return msgId;
        }

        /**
         * @param msgId Message ID.
         */
        void messageId(long msgId) {
            this.msgId = msgId;
        }

        /**
         * @param cctx Cache context.
         * @return Transaction future.
         */
        GridCacheMultiTxFuture<K, V> getOrCreateTxFuture(GridCacheContext<K, V> cctx) {
            if (txFut == null)
                txFut = new GridCacheMultiTxFuture<>(cctx);

            return txFut;
        }

        /**
         * @return Transaction future.
         */
        GridCacheMultiTxFuture<K, V> txFuture() {
            return txFut;
        }

        /**
         * Sets multi-transaction future to {@code null}.
         *
         * @return Previous transaction future.
         */
        GridCacheMultiTxFuture<K, V> resetTxFuture() {
            GridCacheMultiTxFuture<K, V> txFut = this.txFut;

            this.txFut = null;

            return txFut;
        }


        /** {@inheritDoc} */
        @Override public boolean equals(Object o) {
            if (this == o)
                return true;

            if (!(o instanceof ReaderId))
                return false;

            ReaderId readerId = (ReaderId)o;

            return msgId == readerId.msgId && nodeId.equals(readerId.nodeId);
        }

        /** {@inheritDoc} */
        @Override public int hashCode() {
            int res = nodeId.hashCode();

            res = 31 * res + (int)(msgId ^ (msgId >>> 32));

            return res;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(ReaderId.class, this);
        }
    }
}
