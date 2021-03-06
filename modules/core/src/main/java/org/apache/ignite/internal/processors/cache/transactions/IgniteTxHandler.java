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

package org.apache.ignite.internal.processors.cache.transactions;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.cluster.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.processors.cache.distributed.*;
import org.apache.ignite.internal.processors.cache.version.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.internal.processors.cache.distributed.dht.*;
import org.apache.ignite.internal.processors.cache.distributed.near.*;
import org.apache.ignite.internal.transactions.*;
import org.apache.ignite.internal.util.future.*;
import org.apache.ignite.internal.util.lang.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.util.*;

import static org.apache.ignite.internal.managers.communication.GridIoPolicy.*;
import static org.apache.ignite.internal.processors.cache.GridCacheUtils.*;
import static org.apache.ignite.internal.processors.cache.transactions.IgniteInternalTx.FinalizationStatus.*;
import static org.apache.ignite.transactions.TransactionConcurrency.*;
import static org.apache.ignite.transactions.TransactionIsolation.*;
import static org.apache.ignite.transactions.TransactionState.*;

/**
 * Isolated logic to process cache messages.
 */
public class IgniteTxHandler<K, V> {
    /** Logger. */
    private IgniteLogger log;

    /** Shared cache context. */
    private GridCacheSharedContext<K, V> ctx;

    public IgniteInternalFuture<IgniteInternalTx<K, V>> processNearTxPrepareRequest(final UUID nearNodeId,
        final GridNearTxPrepareRequest<K, V> req) {
        return prepareTx(nearNodeId, null, req, null);
    }

    /**
     * @param ctx Shared cache context.
     */
    public IgniteTxHandler(GridCacheSharedContext<K, V> ctx) {
        this.ctx = ctx;

        log = ctx.logger(IgniteTxHandler.class);

        ctx.io().addHandler(0, GridNearTxPrepareRequest.class, new CI2<UUID, GridCacheMessage<K, V>>() {
            @Override public void apply(UUID nodeId, GridCacheMessage<K, V> msg) {
                processNearTxPrepareRequest(nodeId, (GridNearTxPrepareRequest<K, V>)msg);
            }
        });

        ctx.io().addHandler(0, GridNearTxPrepareResponse.class, new CI2<UUID, GridCacheMessage<K, V>>() {
            @Override public void apply(UUID nodeId, GridCacheMessage<K, V> msg) {
                processNearTxPrepareResponse(nodeId, (GridNearTxPrepareResponse<K, V>)msg);
            }
        });

        ctx.io().addHandler(0, GridNearTxFinishRequest.class, new CI2<UUID, GridCacheMessage<K, V>>() {
            @Override public void apply(UUID nodeId, GridCacheMessage<K, V> msg) {
                processNearTxFinishRequest(nodeId, (GridNearTxFinishRequest<K, V>)msg);
            }
        });

        ctx.io().addHandler(0, GridNearTxFinishResponse.class, new CI2<UUID, GridCacheMessage<K, V>>() {
            @Override public void apply(UUID nodeId, GridCacheMessage<K, V> msg) {
                processNearTxFinishResponse(nodeId, (GridNearTxFinishResponse<K, V>)msg);
            }
        });

        ctx.io().addHandler(0, GridDhtTxPrepareRequest.class, new CI2<UUID, GridCacheMessage<K, V>>() {
            @Override public void apply(UUID nodeId, GridCacheMessage<K, V> msg) {
                processDhtTxPrepareRequest(nodeId, (GridDhtTxPrepareRequest<K, V>)msg);
            }
        });

        ctx.io().addHandler(0, GridDhtTxPrepareResponse.class, new CI2<UUID, GridCacheMessage<K, V>>() {
            @Override public void apply(UUID nodeId, GridCacheMessage<K, V> msg) {
                processDhtTxPrepareResponse(nodeId, (GridDhtTxPrepareResponse<K, V>)msg);
            }
        });

        ctx.io().addHandler(0, GridDhtTxFinishRequest.class, new CI2<UUID, GridCacheMessage<K, V>>() {
            @Override public void apply(UUID nodeId, GridCacheMessage<K, V> msg) {
                processDhtTxFinishRequest(nodeId, (GridDhtTxFinishRequest<K, V>)msg);
            }
        });

        ctx.io().addHandler(0, GridDhtTxFinishResponse.class, new CI2<UUID, GridCacheMessage<K, V>>() {
            @Override public void apply(UUID nodeId, GridCacheMessage<K, V> msg) {
                processDhtTxFinishResponse(nodeId, (GridDhtTxFinishResponse<K, V>)msg);
            }
        });

        ctx.io().addHandler(0, GridCacheOptimisticCheckPreparedTxRequest.class,
            new CI2<UUID, GridCacheOptimisticCheckPreparedTxRequest<K, V>>() {
                @Override public void apply(UUID nodeId, GridCacheOptimisticCheckPreparedTxRequest<K, V> req) {
                    processCheckPreparedTxRequest(nodeId, req);
                }
            });

        ctx.io().addHandler(0, GridCacheOptimisticCheckPreparedTxResponse.class,
            new CI2<UUID, GridCacheOptimisticCheckPreparedTxResponse<K, V>>() {
                @Override public void apply(UUID nodeId, GridCacheOptimisticCheckPreparedTxResponse<K, V> res) {
                    processCheckPreparedTxResponse(nodeId, res);
                }
            });
    }

    /**
     * @param nearNodeId Near node ID that initiated transaction.
     * @param locTx Optional local transaction.
     * @param req Near prepare request.
     * @return Future for transaction.
     */
    public IgniteInternalFuture<IgniteInternalTx<K, V>> prepareTx(
        UUID nearNodeId,
        @Nullable GridNearTxLocal<K, V> locTx,
        GridNearTxPrepareRequest<K, V> req,
        @Nullable IgniteInClosure<GridNearTxPrepareResponse<K, V>> completeCb
    ) {
        assert nearNodeId != null;
        assert req != null;

        if (locTx != null) {
            assert completeCb != null;

            if (req.near()) {
                // Make sure not to provide Near entries to DHT cache.
                req.cloneEntries();

                return prepareNearTx(nearNodeId, req, completeCb);
            }
            else
                return prepareColocatedTx(locTx, req, completeCb);
        }
        else
            return prepareNearTx(nearNodeId, req, null);
    }

    /**
     * Prepares local colocated tx.
     *
     * @param locTx Local transaction.
     * @param req Near prepare request.
     * @return Prepare future.
     */
    private IgniteInternalFuture<IgniteInternalTx<K, V>> prepareColocatedTx(
        final GridNearTxLocal<K, V> locTx,
        final GridNearTxPrepareRequest<K, V> req,
        final IgniteInClosure<GridNearTxPrepareResponse<K, V>> completeCb
    ) {

        IgniteInternalFuture<Object> fut = new GridFinishedFutureEx<>(); // TODO force preload keys.

        return new GridEmbeddedFuture<>(
            ctx.kernalContext(),
            fut,
            new C2<Object, Exception, IgniteInternalFuture<IgniteInternalTx<K, V>>>() {
                @Override public IgniteInternalFuture<IgniteInternalTx<K, V>> apply(Object o, Exception ex) {
                    if (ex != null)
                        throw new GridClosureException(ex);

                    IgniteInternalFuture<IgniteInternalTx<K, V>> fut = locTx.prepareAsyncLocal(
                        req.reads(),
                        req.writes(),
                        req.transactionNodes(),
                        req.last(),
                        req.lastBackups(),
                        completeCb);

                    if (locTx.isRollbackOnly())
                        locTx.rollbackAsync();

                    return fut;
                }
            },
            new C2<IgniteInternalTx<K, V>, Exception, IgniteInternalTx<K, V>>() {
                @Nullable @Override public IgniteInternalTx<K, V> apply(IgniteInternalTx<K, V> tx, Exception e) {
                    if (e != null) {
                        // tx can be null of exception occurred.
                        if (tx != null)
                            tx.setRollbackOnly(); // Just in case.

                        if (!(e instanceof IgniteTxOptimisticCheckedException))
                            U.error(log, "Failed to prepare DHT transaction: " + tx, e);
                    }

                    return tx;
                }
            }
        );
    }

    /**
     * Prepares near transaction.
     *
     * @param nearNodeId Near node ID that initiated transaction.
     * @param req Near prepare request.
     * @return Prepare future.
     */
    private IgniteInternalFuture<IgniteInternalTx<K, V>> prepareNearTx(
        final UUID nearNodeId,
        final GridNearTxPrepareRequest<K, V> req,
        IgniteInClosure<GridNearTxPrepareResponse<K, V>> completeCb
    ) {
        ClusterNode nearNode = ctx.node(nearNodeId);

        if (nearNode == null) {
            if (log.isDebugEnabled())
                log.debug("Received transaction request from node that left grid (will ignore): " + nearNodeId);

            return null;
        }

        try {
            for (IgniteTxEntry<K, V> e : F.concat(false, req.reads(), req.writes()))
                e.unmarshal(ctx, false, ctx.deploy().globalLoader());
        }
        catch (IgniteCheckedException e) {
            return new GridFinishedFuture<>(ctx.kernalContext(), e);
        }

        GridDhtTxLocal<K, V> tx;

        GridCacheVersion mappedVer = ctx.tm().mappedVersion(req.version());

        if (mappedVer != null) {
            tx = ctx.tm().tx(mappedVer);

            if (tx == null)
                U.warn(log, "Missing local transaction for mapped near version [nearVer=" + req.version()
                    + ", mappedVer=" + mappedVer + ']');
            else {
                if (req.concurrency() == PESSIMISTIC)
                    tx.nearFutureId(req.futureId());
            }
        }
        else {
            tx = new GridDhtTxLocal<>(
                ctx,
                nearNode.id(),
                req.version(),
                req.futureId(),
                req.miniId(),
                req.threadId(),
                req.implicitSingle(),
                req.implicitSingle(),
                req.system(),
                req.concurrency(),
                req.isolation(),
                req.timeout(),
                req.isInvalidate(),
                false,
                req.txSize(),
                req.groupLockKey(),
                req.partitionLock(),
                req.transactionNodes(),
                req.subjectId(),
                req.taskNameHash()
            );

            tx = ctx.tm().onCreated(tx);

            if (tx != null)
                tx.topologyVersion(req.topologyVersion());
            else
                U.warn(log, "Failed to create local transaction (was transaction rolled back?) [xid=" +
                    req.version() + ", req=" + req + ']');
        }

        if (tx != null) {
            tx.transactionNodes(req.transactionNodes());

            if (req.onePhaseCommit()) {
                assert req.last();
                assert F.isEmpty(req.lastBackups()) || req.lastBackups().size() <= 1;

                tx.onePhaseCommit(true);
            }

            if (req.returnValue())
                tx.needReturnValue(true);

            IgniteInternalFuture<IgniteInternalTx<K, V>> fut = tx.prepareAsync(
                req.reads(),
                req.writes(),
                req.dhtVersions(),
                req.messageId(),
                req.miniId(),
                req.transactionNodes(),
                req.last(),
                req.lastBackups(),
                completeCb);

            if (tx.isRollbackOnly()) {
                try {
                    tx.rollback();
                }
                catch (IgniteCheckedException e) {
                    U.error(log, "Failed to rollback transaction: " + tx, e);
                }
            }

            final GridDhtTxLocal<K, V> tx0 = tx;

            fut.listenAsync(new CI1<IgniteInternalFuture<IgniteInternalTx<K, V>>>() {
                @Override public void apply(IgniteInternalFuture<IgniteInternalTx<K, V>> txFut) {
                    try {
                        txFut.get();
                    }
                    catch (IgniteCheckedException e) {
                        tx0.setRollbackOnly(); // Just in case.

                        if (!(e instanceof IgniteTxOptimisticCheckedException))
                            U.error(log, "Failed to prepare DHT transaction: " + tx0, e);
                    }
                }
            });

            return fut;
        }
        else
            return new GridFinishedFuture<>(ctx.kernalContext(), (IgniteInternalTx<K, V>)null);
    }

    /**
     * @param nodeId Node ID.
     * @param res Response.
     */
    private void processNearTxPrepareResponse(UUID nodeId, GridNearTxPrepareResponse<K, V> res) {
        GridNearTxPrepareFuture<K, V> fut = (GridNearTxPrepareFuture<K, V>)ctx.mvcc()
            .<IgniteInternalTx<K, V>>future(res.version(), res.futureId());

        if (fut == null) {
            U.warn(log, "Failed to find future for prepare response [sender=" + nodeId + ", res=" + res + ']');

            return;
        }

        fut.onResult(nodeId, res);
    }

    /**
     * @param nodeId Node ID.
     * @param res Response.
     */
    private void processNearTxFinishResponse(UUID nodeId, GridNearTxFinishResponse<K, V> res) {
        ctx.tm().onFinishedRemote(nodeId, res.threadId());

        GridNearTxFinishFuture<K, V> fut = (GridNearTxFinishFuture<K, V>)ctx.mvcc().<IgniteInternalTx>future(
            res.xid(), res.futureId());

        if (fut == null) {
            if (log.isDebugEnabled())
                log.debug("Failed to find future for finish response [sender=" + nodeId + ", res=" + res + ']');

            return;
        }

        fut.onResult(nodeId, res);
    }

    /**
     * @param nodeId Node ID.
     * @param res Response.
     */
    private void processDhtTxPrepareResponse(UUID nodeId, GridDhtTxPrepareResponse<K, V> res) {
        GridDhtTxPrepareFuture<K, V> fut = (GridDhtTxPrepareFuture<K, V>)ctx.mvcc().
            <IgniteInternalTx<K, V>>future(res.version(), res.futureId());

        if (fut == null) {
            if (log.isDebugEnabled())
                log.debug("Received response for unknown future (will ignore): " + res);

            return;
        }

        fut.onResult(nodeId, res);
    }

    /**
     * @param nodeId Node ID.
     * @param res Response.
     */
    private void processDhtTxFinishResponse(UUID nodeId, GridDhtTxFinishResponse<K, V> res) {
        assert nodeId != null;
        assert res != null;

        GridDhtTxFinishFuture<K, V> fut = (GridDhtTxFinishFuture<K, V>)ctx.mvcc().<IgniteInternalTx>future(res.xid(),
            res.futureId());

        if (fut == null) {
            if (log.isDebugEnabled())
                log.debug("Received response for unknown future (will ignore): " + res);

            return;
        }

        fut.onResult(nodeId, res);
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     * @return Future.
     */
    @Nullable public IgniteInternalFuture<IgniteInternalTx> processNearTxFinishRequest(UUID nodeId, GridNearTxFinishRequest<K, V> req) {
        return finish(nodeId, null, req);
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     * @return Future.
     */
    @Nullable public IgniteInternalFuture<IgniteInternalTx> finish(UUID nodeId, @Nullable GridNearTxLocal<K, V> locTx,
        GridNearTxFinishRequest<K, V> req) {
        assert nodeId != null;
        assert req != null;

        // Transaction on local cache only.
        if (locTx != null && !locTx.nearLocallyMapped() && !locTx.colocatedLocallyMapped())
            return new GridFinishedFutureEx<IgniteInternalTx>(locTx);

        if (log.isDebugEnabled())
            log.debug("Processing near tx finish request [nodeId=" + nodeId + ", req=" + req + "]");

        IgniteInternalFuture<IgniteInternalTx> colocatedFinishFut = null;

        if (locTx != null && locTx.colocatedLocallyMapped())
            colocatedFinishFut = finishColocatedLocal(req.commit(), locTx);

        IgniteInternalFuture<IgniteInternalTx> nearFinishFut = null;

        if (locTx == null || locTx.nearLocallyMapped())
            nearFinishFut = finishDhtLocal(nodeId, locTx, req);

        if (colocatedFinishFut != null && nearFinishFut != null) {
            GridCompoundFuture<IgniteInternalTx, IgniteInternalTx> res = new GridCompoundFuture<>(ctx.kernalContext());

            res.add(colocatedFinishFut);
            res.add(nearFinishFut);

            res.markInitialized();

            return res;
        }

        if (colocatedFinishFut != null)
            return colocatedFinishFut;

        return nearFinishFut;
    }

    /**
     * @param nodeId Node ID initiated commit.
     * @param locTx Optional local transaction.
     * @param req Finish request.
     * @return Finish future.
     */
    private IgniteInternalFuture<IgniteInternalTx> finishDhtLocal(UUID nodeId, @Nullable GridNearTxLocal<K, V> locTx,
        GridNearTxFinishRequest<K, V> req) {
        GridCacheVersion dhtVer = ctx.tm().mappedVersion(req.version());

        GridDhtTxLocal<K, V> tx = null;

        if (dhtVer == null) {
            if (log.isDebugEnabled())
                log.debug("Received transaction finish request for unknown near version (was lock explicit?): " + req);
        }
        else
            tx = ctx.tm().tx(dhtVer);

        if (tx == null && !req.explicitLock()) {
            assert locTx == null : "DHT local tx should never be lost for near local tx: " + locTx;

            U.warn(log, "Received finish request for completed transaction (the message may be too late " +
                "and transaction could have been DGCed by now) [commit=" + req.commit() +
                ", xid=" + req.version() + ']');

            // Always send finish response.
            GridCacheMessage<K, V> res = new GridNearTxFinishResponse<>(req.version(), req.threadId(), req.futureId(),
                req.miniId(), new IgniteCheckedException("Transaction has been already completed."));

            try {
                ctx.io().send(nodeId, res, req.system() ? UTILITY_CACHE_POOL : SYSTEM_POOL);
            }
            catch (Throwable e) {
                // Double-check.
                if (ctx.discovery().node(nodeId) == null) {
                    if (log.isDebugEnabled())
                        log.debug("Node left while sending finish response [nodeId=" + nodeId + ", res=" + res +
                            ']');
                }
                else
                    U.error(log, "Failed to send finish response to node [nodeId=" + nodeId + ", " +
                        "res=" + res + ']', e);
            }

            return null;
        }

        try {
            if (req.commit()) {
                if (tx == null) {
                    // Create transaction and add entries.
                    tx = ctx.tm().onCreated(
                        new GridDhtTxLocal<>(
                            ctx,
                            nodeId,
                            req.version(),
                            req.futureId(),
                            req.miniId(),
                            req.threadId(),
                            true,
                            false, /* we don't know, so assume false. */
                            req.system(),
                            PESSIMISTIC,
                            READ_COMMITTED,
                            /*timeout */0,
                            req.isInvalidate(),
                            req.storeEnabled(),
                            req.txSize(),
                            req.groupLockKey(),
                            false,
                            null,
                            req.subjectId(),
                            req.taskNameHash()));

                    if (tx == null || !ctx.tm().onStarted(tx))
                        throw new IgniteTxRollbackCheckedException("Attempt to start a completed transaction: " + req);

                    tx.topologyVersion(req.topologyVersion());
                }

                tx.storeEnabled(req.storeEnabled());

                if (!tx.markFinalizing(USER_FINISH)) {
                    if (log.isDebugEnabled())
                        log.debug("Will not finish transaction (it is handled by another thread): " + tx);

                    return null;
                }

                if (!tx.syncCommit())
                    tx.syncCommit(req.syncCommit());

                tx.nearFinishFutureId(req.futureId());
                tx.nearFinishMiniId(req.miniId());

                IgniteInternalFuture<IgniteInternalTx> commitFut = tx.commitAsync();

                // Only for error logging.
                commitFut.listenAsync(CU.errorLogger(log));

                return commitFut;
            }
            else {
                assert tx != null : "Transaction is null for near rollback request [nodeId=" +
                    nodeId + ", req=" + req + "]";

                tx.syncRollback(req.syncRollback());

                tx.nearFinishFutureId(req.futureId());
                tx.nearFinishMiniId(req.miniId());

                IgniteInternalFuture<IgniteInternalTx> rollbackFut = tx.rollbackAsync();

                // Only for error logging.
                rollbackFut.listenAsync(CU.errorLogger(log));

                return rollbackFut;
            }
        }
        catch (Throwable e) {
            U.error(log, "Failed completing transaction [commit=" + req.commit() + ", tx=" + tx + ']', e);

            if (tx != null) {
                IgniteInternalFuture<IgniteInternalTx> rollbackFut = tx.rollbackAsync();

                // Only for error logging.
                rollbackFut.listenAsync(CU.errorLogger(log));

                return rollbackFut;
            }

            return new GridFinishedFuture<>(ctx.kernalContext(), e);
        }
    }

    /**
     * @param commit Commit flag (rollback if {@code false}).
     * @param tx Transaction to commit.
     * @return Future.
     */
    public IgniteInternalFuture<IgniteInternalTx> finishColocatedLocal(boolean commit, GridNearTxLocal<K, V> tx) {
        try {
            if (commit) {
                if (!tx.markFinalizing(USER_FINISH)) {
                    if (log.isDebugEnabled())
                        log.debug("Will not finish transaction (it is handled by another thread): " + tx);

                    return null;
                }

                return tx.commitAsyncLocal();
            }
            else
                return tx.rollbackAsyncLocal();
        }
        catch (Throwable e) {
            U.error(log, "Failed completing transaction [commit=" + commit + ", tx=" + tx + ']', e);

            if (tx != null)
                return tx.rollbackAsync();

            return new GridFinishedFuture<>(ctx.kernalContext(), e);
        }
    }

    /**
     * @param nodeId Sender node ID.
     * @param req Request.
     */
    protected final void processDhtTxPrepareRequest(UUID nodeId, GridDhtTxPrepareRequest<K, V> req) {
        assert nodeId != null;
        assert req != null;

        assert req.transactionNodes() != null;

        if (log.isDebugEnabled())
            log.debug("Processing dht tx prepare request [locNodeId=" + ctx.localNodeId() +
                ", nodeId=" + nodeId + ", req=" + req + ']');

        GridDhtTxRemote<K, V> dhtTx = null;
        GridNearTxRemote<K, V> nearTx = null;

        GridDhtTxPrepareResponse<K, V> res;

        try {
            res = new GridDhtTxPrepareResponse<>(req.version(), req.futureId(), req.miniId());

            // Start near transaction first.
            nearTx = !F.isEmpty(req.nearWrites()) ? startNearRemoteTx(ctx.deploy().globalLoader(), nodeId, req) : null;
            dhtTx = startRemoteTx(nodeId, req, res);

            // Set evicted keys from near transaction.
            if (nearTx != null)
                res.nearEvicted(nearTx.evicted());

            if (dhtTx != null && !F.isEmpty(dhtTx.invalidPartitions()))
                res.invalidPartitions(dhtTx.invalidPartitions());

            if (req.onePhaseCommit()) {
                assert req.last();

                if (dhtTx != null) {
                    dhtTx.onePhaseCommit(true);

                    finish(nodeId, dhtTx, req);
                }

                if (nearTx != null) {
                    nearTx.onePhaseCommit(true);

                    finish(nodeId, nearTx, req);
                }
            }
        }
        catch (IgniteCheckedException e) {
            if (e instanceof IgniteTxRollbackCheckedException)
                U.error(log, "Transaction was rolled back before prepare completed: " + req, e);
            else if (e instanceof IgniteTxOptimisticCheckedException) {
                if (log.isDebugEnabled())
                    log.debug("Optimistic failure for remote transaction (will rollback): " + req);
            }
            else if (e instanceof IgniteTxHeuristicCheckedException) {
                U.warn(log, "Failed to commit transaction (all transaction entries were invalidated): " +
                    CU.txString(dhtTx));
            }
            else
                U.error(log, "Failed to process prepare request: " + req, e);

            if (nearTx != null)
                nearTx.rollback();

            res = new GridDhtTxPrepareResponse<>(req.version(), req.futureId(), req.miniId(), e);
        }

        try {
            // Reply back to sender.
            ctx.io().send(nodeId, res, req.system() ? UTILITY_CACHE_POOL : SYSTEM_POOL);
        }
        catch (IgniteCheckedException e) {
            if (e instanceof ClusterTopologyCheckedException) {
                if (log.isDebugEnabled())
                    log.debug("Failed to send tx response to remote node (node left grid) [node=" + nodeId +
                        ", xid=" + req.version());
            }
            else
                U.warn(log, "Failed to send tx response to remote node (will rollback transaction) [node=" + nodeId +
                    ", xid=" + req.version() + ", err=" +  e.getMessage() + ']');

            if (nearTx != null)
                nearTx.rollback();

            if (dhtTx != null)
                dhtTx.rollback();
        }
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     */
    @SuppressWarnings({"unchecked"})
    protected final void processDhtTxFinishRequest(final UUID nodeId, final GridDhtTxFinishRequest<K, V> req) {
        assert nodeId != null;
        assert req != null;

        if (log.isDebugEnabled())
            log.debug("Processing dht tx finish request [nodeId=" + nodeId + ", req=" + req + ']');

        GridDhtTxRemote<K, V> dhtTx = ctx.tm().tx(req.version());
        GridNearTxRemote<K, V> nearTx = ctx.tm().nearTx(req.version());

        // Safety - local transaction will finish explicitly.
        if (nearTx != null && nearTx.local())
            nearTx = null;

        finish(nodeId, dhtTx, req);

        if (nearTx != null)
            finish(nodeId, nearTx, req);

        if (dhtTx != null && !dhtTx.done()) {
            dhtTx.finishFuture().listenAsync(new CI1<IgniteInternalFuture<IgniteInternalTx>>() {
                @Override public void apply(IgniteInternalFuture<IgniteInternalTx> igniteTxIgniteFuture) {
                    sendReply(nodeId, req);
                }
            });
        }
        else
            sendReply(nodeId, req);
    }

    /**
     * @param nodeId Node ID.
     * @param tx Transaction.
     * @param req Request.
     */
    protected void finish(
        UUID nodeId,
        IgniteTxRemoteEx<K, V> tx,
        GridDhtTxFinishRequest<K, V> req) {
        // We don't allow explicit locks for transactions and
        // therefore immediately return if transaction is null.
        // However, we may decide to relax this restriction in
        // future.
        if (tx == null) {
            if (req.commit())
                // Must be some long time duplicate, but we add it anyway.
                ctx.tm().addCommittedTx(req.version(), null);
            else
                ctx.tm().addRolledbackTx(req.version());

            if (log.isDebugEnabled())
                log.debug("Received finish request for non-existing transaction (added to completed set) " +
                    "[senderNodeId=" + nodeId + ", res=" + req + ']');

            return;
        }
        else if (log.isDebugEnabled())
            log.debug("Received finish request for transaction [senderNodeId=" + nodeId + ", req=" + req +
                ", tx=" + tx + ']');

        try {
            if (req.commit() || req.isSystemInvalidate()) {
                if (tx.commitVersion(req.commitVersion())) {
                    tx.invalidate(req.isInvalidate());
                    tx.systemInvalidate(req.isSystemInvalidate());

                    // Complete remote candidates.
                    tx.doneRemote(req.baseVersion(), null, null, null);

                    tx.commit();
                }
            }
            else {
                tx.doneRemote(req.baseVersion(), null, null, null);

                tx.rollback();
            }
        }
        catch (Throwable e) {
            U.error(log, "Failed completing transaction [commit=" + req.commit() + ", tx=" + tx + ']', e);

            // Mark transaction for invalidate.
            tx.invalidate(true);
            tx.systemInvalidate(true);

            try {
                tx.commit();
            }
            catch (IgniteCheckedException ex) {
                U.error(log, "Failed to invalidate transaction: " + tx, ex);
            }
        }
    }

    /**
     * @param nodeId Node ID.
     * @param tx Transaction.
     * @param req Request.
     */
    protected void finish(
        UUID nodeId,
        GridDistributedTxRemoteAdapter<K, V> tx,
        GridDhtTxPrepareRequest<K, V> req) throws IgniteTxHeuristicCheckedException {
        assert tx != null : "No transaction for one-phase commit prepare request: " + req;

        try {
            tx.commitVersion(req.writeVersion());
            tx.invalidate(req.isInvalidate());

            // Complete remote candidates.
            tx.doneRemote(req.version(), null, null, null);

            tx.commit();
        }
        catch (IgniteTxHeuristicCheckedException e) {
            // Just rethrow this exception. Transaction was already uncommitted.
            throw e;
        }
        catch (Throwable e) {
            U.error(log, "Failed committing transaction [tx=" + tx + ']', e);

            // Mark transaction for invalidate.
            tx.invalidate(true);
            tx.systemInvalidate(true);

            tx.rollback();
        }
    }

    /**
     * Sends tx finish response to remote node, if response is requested.
     *
     * @param nodeId Node id that originated finish request.
     * @param req Request.
     */
    protected void sendReply(UUID nodeId, GridDhtTxFinishRequest<K, V> req) {
        if (req.replyRequired()) {
            GridCacheMessage<K, V> res = new GridDhtTxFinishResponse<>(req.version(), req.futureId(), req.miniId());

            try {
                ctx.io().send(nodeId, res, req.system() ? UTILITY_CACHE_POOL : SYSTEM_POOL);
            }
            catch (Throwable e) {
                // Double-check.
                if (ctx.discovery().node(nodeId) == null) {
                    if (log.isDebugEnabled())
                        log.debug("Node left while sending finish response [nodeId=" + nodeId + ", res=" + res + ']');
                }
                else
                    U.error(log, "Failed to send finish response to node [nodeId=" + nodeId + ", res=" + res + ']', e);
            }
        }
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     * @param res Response.
     * @return Remote transaction.
     * @throws IgniteCheckedException If failed.
     */
    @Nullable GridDhtTxRemote<K, V> startRemoteTx(
        UUID nodeId,
        GridDhtTxPrepareRequest<K, V> req,
        GridDhtTxPrepareResponse<K, V> res
    ) throws IgniteCheckedException {
        if (!F.isEmpty(req.writes())) {
            GridDhtTxRemote<K, V> tx = ctx.tm().tx(req.version());

            assert F.isEmpty(req.candidatesByKey());

            if (tx == null) {
                tx = new GridDhtTxRemote<>(
                    ctx,
                    req.nearNodeId(),
                    req.futureId(),
                    nodeId,
                    req.threadId(),
                    req.topologyVersion(),
                    req.version(),
                    null,
                    req.system(),
                    req.concurrency(),
                    req.isolation(),
                    req.isInvalidate(),
                    req.timeout(),
                    req.writes() != null ? Math.max(req.writes().size(), req.txSize()) : req.txSize(),
                    req.groupLockKey(),
                    req.nearXidVersion(),
                    req.transactionNodes(),
                    req.subjectId(),
                    req.taskNameHash());

                tx.writeVersion(req.writeVersion());

                tx = ctx.tm().onCreated(tx);

                if (tx == null || !ctx.tm().onStarted(tx)) {
                    if (log.isDebugEnabled())
                        log.debug("Attempt to start a completed transaction (will ignore): " + tx);

                    return null;
                }
            }

            if (!tx.isSystemInvalidate() && !F.isEmpty(req.writes())) {
                int idx = 0;

                for (IgniteTxEntry<K, V> entry : req.writes()) {
                    GridCacheContext<K, V> cacheCtx = entry.context();

                    tx.addWrite(entry, ctx.deploy().globalLoader());

                    if (isNearEnabled(cacheCtx) && req.invalidateNearEntry(idx))
                        invalidateNearEntry(cacheCtx, entry.key(), req.version());

                    try {
                        if (req.needPreloadKey(idx)) {
                            GridCacheEntryEx<K, V> cached = entry.cached();

                            if (cached == null)
                                cached = cacheCtx.cache().entryEx(entry.key(), req.topologyVersion());

                            GridCacheEntryInfo<K, V> info = cached.info();

                            if (info != null && !info.isNew() && !info.isDeleted())
                                res.addPreloadEntry(info);
                        }
                    }
                    catch (GridDhtInvalidPartitionException e) {
                        tx.addInvalidPartition(cacheCtx, e.partition());

                        tx.clearEntry(entry.txKey());
                    }

                    idx++;
                }
            }

            // Prepare prior to reordering, so the pending locks added
            // in prepare phase will get properly ordered as well.
            tx.prepare();

            if (req.last())
                tx.state(PREPARED);

            res.invalidPartitions(tx.invalidPartitions());

            if (tx.empty() && req.last()) {
                tx.rollback();

                return null;
            }

            return tx;
        }

        return null;
    }

    /**
     * @param key Key
     * @param ver Version.
     * @throws IgniteCheckedException If invalidate failed.
     */
    private void invalidateNearEntry(GridCacheContext<K, V> cacheCtx, K key, GridCacheVersion ver)
        throws IgniteCheckedException {
        GridNearCacheAdapter<K, V> near = cacheCtx.isNear() ? cacheCtx.near() : cacheCtx.dht().near();

        GridCacheEntryEx<K, V> nearEntry = near.peekEx(key);

        if (nearEntry != null)
            nearEntry.invalidate(null, ver);
    }

    /**
     * Called while processing dht tx prepare request.
     *
     * @param ldr Loader.
     * @param nodeId Sender node ID.
     * @param req Request.
     * @return Remote transaction.
     * @throws IgniteCheckedException If failed.
     */
    @Nullable public GridNearTxRemote<K, V> startNearRemoteTx(ClassLoader ldr, UUID nodeId,
        GridDhtTxPrepareRequest<K, V> req) throws IgniteCheckedException {
        assert F.isEmpty(req.candidatesByKey());

        if (!F.isEmpty(req.nearWrites())) {
            GridNearTxRemote<K, V> tx = ctx.tm().nearTx(req.version());

            if (tx == null) {
                tx = new GridNearTxRemote<>(
                    ctx,
                    ldr,
                    nodeId,
                    req.nearNodeId(),
                    req.threadId(),
                    req.version(),
                    null,
                    req.system(),
                    req.concurrency(),
                    req.isolation(),
                    req.isInvalidate(),
                    req.timeout(),
                    req.nearWrites(),
                    req.txSize(),
                    req.groupLockKey(),
                    req.subjectId(),
                    req.taskNameHash()
                );

                tx.writeVersion(req.writeVersion());

                if (!tx.empty()) {
                    tx = ctx.tm().onCreated(tx);

                    if (tx == null || !ctx.tm().onStarted(tx))
                        throw new IgniteTxRollbackCheckedException("Attempt to start a completed transaction: " + tx);
                }
            }
            else
                tx.addEntries(ldr, req.nearWrites());

            tx.ownedVersions(req.owned());

            // Prepare prior to reordering, so the pending locks added
            // in prepare phase will get properly ordered as well.
            tx.prepare();

            if (req.last())
                tx.state(PREPARED);

            return tx;
        }

        return null;
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     */
    protected void processCheckPreparedTxRequest(UUID nodeId, GridCacheOptimisticCheckPreparedTxRequest<K, V> req) {
        if (log.isDebugEnabled())
            log.debug("Processing check prepared transaction requests [nodeId=" + nodeId + ", req=" + req + ']');

        boolean prepared = ctx.tm().txsPreparedOrCommitted(req.nearXidVersion(), req.transactions());

        GridCacheOptimisticCheckPreparedTxResponse<K, V> res =
            new GridCacheOptimisticCheckPreparedTxResponse<>(req.version(), req.futureId(), req.miniId(), prepared);

        try {
            if (log.isDebugEnabled())
                log.debug("Sending check prepared transaction response [nodeId=" + nodeId + ", res=" + res + ']');

            ctx.io().send(nodeId, res, req.system() ? UTILITY_CACHE_POOL : SYSTEM_POOL);
        }
        catch (ClusterTopologyCheckedException ignored) {
            if (log.isDebugEnabled())
                log.debug("Failed to send check prepared transaction response (did node leave grid?) [nodeId=" +
                    nodeId + ", res=" + res + ']');
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to send response to node [nodeId=" + nodeId + ", res=" + res + ']', e);
        }
    }

    /**
     * @param nodeId Node ID.
     * @param res Response.
     */
    protected void processCheckPreparedTxResponse(UUID nodeId, GridCacheOptimisticCheckPreparedTxResponse<K, V> res) {
        if (log.isDebugEnabled())
            log.debug("Processing check prepared transaction response [nodeId=" + nodeId + ", res=" + res + ']');

        GridCacheOptimisticCheckPreparedTxFuture<K, V> fut = (GridCacheOptimisticCheckPreparedTxFuture<K, V>)ctx.mvcc().
            <Boolean>future(res.version(), res.futureId());

        if (fut == null) {
            if (log.isDebugEnabled())
                log.debug("Received response for unknown future (will ignore): " + res);

            return;
        }

        fut.onResult(nodeId, res);
    }
}
