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

package org.apache.ignite.internal.processors.closure;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.compute.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.future.*;
import org.apache.ignite.internal.util.lang.*;
import org.apache.ignite.internal.util.tostring.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.internal.util.worker.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.marshaller.*;
import org.apache.ignite.resources.*;
import org.jdk8.backport.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import static org.apache.ignite.compute.ComputeJobResultPolicy.*;
import static org.apache.ignite.internal.processors.task.GridTaskThreadContextKey.*;

/**
 *
 */
public class GridClosureProcessor extends GridProcessorAdapter {
    /** */
    private final Executor sysPool;

    /** */
    private final Executor pubPool;

    /** */
    private final Executor igfsPool;

    /** Lock to control execution after stop. */
    private final GridSpinReadWriteLock busyLock = new GridSpinReadWriteLock();

    /** Workers count. */
    private final LongAdder workersCnt = new LongAdder();

    /**
     * @param ctx Kernal context.
     */
    public GridClosureProcessor(GridKernalContext ctx) {
        super(ctx);

        sysPool = ctx.getSystemExecutorService();
        pubPool = ctx.getExecutorService();
        igfsPool = ctx.getIgfsExecutorService();
    }

    /** {@inheritDoc} */
    @Override public void start() throws IgniteCheckedException {
        if (log.isDebugEnabled())
            log.debug("Started closure processor.");
    }

    /** {@inheritDoc} */
    @SuppressWarnings("BusyWait")
    @Override public void onKernalStop(boolean cancel) {
        busyLock.writeLock();

        boolean interrupted = Thread.interrupted();

        while (workersCnt.sum() != 0) {
            try {
                Thread.sleep(200);
            }
            catch (InterruptedException ignored) {
                interrupted = true;
            }
        }

        if (interrupted)
            Thread.currentThread().interrupt();

        if (log.isDebugEnabled())
            log.debug("Stopped closure processor.");
    }

    /**
     * @throws IllegalStateException If grid is stopped.
     */
    private void enterBusy() throws IllegalStateException {
        if (!busyLock.tryReadLock())
            throw new IllegalStateException("Closure processor cannot be used on stopped grid: " + ctx.gridName());
    }

    /**
     * Unlocks busy lock.
     */
    private void leaveBusy() {
        busyLock.readUnlock();
    }

    /**
     * @param mode Distribution mode.
     * @param jobs Closures to execute.
     * @param nodes Grid nodes.
     * @return Task execution future.
     */
    public ComputeTaskInternalFuture<?> runAsync(GridClosureCallMode mode, @Nullable Collection<? extends Runnable> jobs,
        @Nullable Collection<ClusterNode> nodes) {
        return runAsync(mode, jobs, nodes, false);
    }

    /**
     * @param mode Distribution mode.
     * @param jobs Closures to execute.
     * @param nodes Grid nodes.
     * @param sys If {@code true}, then system pool will be used.
     * @return Task execution future.
     */
    public ComputeTaskInternalFuture<?> runAsync(GridClosureCallMode mode,
        Collection<? extends Runnable> jobs,
        @Nullable Collection<ClusterNode> nodes,
        boolean sys)
    {
        assert mode != null;
        assert !F.isEmpty(jobs) : jobs;

        enterBusy();

        try {
            if (F.isEmpty(nodes))
                return ComputeTaskInternalFuture.finishedFuture(ctx, T1.class, U.emptyTopologyException());

            ctx.task().setThreadContext(TC_SUBGRID, nodes);

            return ctx.task().execute(new T1(mode, jobs), null, sys);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param mode Distribution mode.
     * @param job Closure to execute.
     * @param nodes Grid nodes.
     * @return Task execution future.
     */
    public ComputeTaskInternalFuture<?> runAsync(GridClosureCallMode mode, Runnable job,
        @Nullable Collection<ClusterNode> nodes) {
        return runAsync(mode, job, nodes, false);
    }

    /**
     * @param mode Distribution mode.
     * @param job Closure to execute.
     * @param nodes Grid nodes.
     * @param sys If {@code true}, then system pool will be used.
     * @return Task execution future.
     */
    public ComputeTaskInternalFuture<?> runAsync(GridClosureCallMode mode,
        Runnable job,
        @Nullable Collection<ClusterNode> nodes,
        boolean sys)
    {
        assert mode != null;
        assert job != null;

        enterBusy();

        try {
            if (F.isEmpty(nodes))
                return ComputeTaskInternalFuture.finishedFuture(ctx, T2.class, U.emptyTopologyException());

            ctx.task().setThreadContext(TC_SUBGRID, nodes);

            return ctx.task().execute(new T2(mode, job), null, sys);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * Maps {@link Runnable} jobs to specified nodes based on distribution mode.
     *
     * @param mode Distribution mode.
     * @param jobs Closures to map.
     * @param nodes Grid nodes.
     * @param lb Load balancer.
     * @throws IgniteException Thrown in case of any errors.
     * @return Mapping.
     */
    private Map<ComputeJob, ClusterNode> absMap(GridClosureCallMode mode, Collection<? extends Runnable> jobs,
        Collection<ClusterNode> nodes, ComputeLoadBalancer lb) throws IgniteException {
        assert mode != null;
        assert jobs != null;
        assert nodes != null;
        assert lb != null;

        try {
            if (!F.isEmpty(jobs) && !F.isEmpty(nodes)) {
                JobMapper mapper = new JobMapper(jobs.size());

                switch (mode) {
                    case BROADCAST: {
                        for (ClusterNode n : nodes)
                            for (Runnable r : jobs)
                                mapper.map(job(r), n);

                        break;
                    }

                    case BALANCE: {
                        for (Runnable r : jobs) {
                            ComputeJob job = job(r);

                            mapper.map(job, lb.getBalancedNode(job, null));
                        }

                        break;
                    }
                }

                return mapper.map();
            }
            else
                return Collections.emptyMap();
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }

    /**
     * Maps {@link Callable} jobs to specified nodes based on distribution mode.
     *
     * @param mode Distribution mode.
     * @param jobs Closures to map.
     * @param nodes Grid nodes.
     * @param lb Load balancer.
     * @throws IgniteException Thrown in case of any errors.
     * @return Mapping.
     */
    private <R> Map<ComputeJob, ClusterNode> outMap(GridClosureCallMode mode,
        Collection<? extends Callable<R>> jobs, Collection<ClusterNode> nodes, ComputeLoadBalancer lb)
        throws IgniteException {
        assert mode != null;
        assert jobs != null;
        assert nodes != null;
        assert lb != null;

        try {
            if (!F.isEmpty(jobs) && !F.isEmpty(nodes)) {
                JobMapper mapper = new JobMapper(jobs.size());

                switch (mode) {
                    case BROADCAST: {
                        for (ClusterNode n : nodes)
                            for (Callable<R> c : jobs)
                                mapper.map(job(c), n);

                        break;
                    }

                    case BALANCE: {
                        for (Callable<R> c : jobs) {
                            ComputeJob job = job(c);

                            mapper.map(job, lb.getBalancedNode(job, null));
                        }

                        break;
                    }
                }

                return mapper.map();
            }
            else
                return Collections.emptyMap();
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }

    /**
     * @param mode Distribution mode.
     * @param jobs Closures to execute.
     * @param rdc Reducer.
     * @param nodes Grid nodes.
     * @param <R1> Type.
     * @param <R2> Type.
     * @return Reduced result.
     */
    public <R1, R2> ComputeTaskInternalFuture<R2> forkjoinAsync(GridClosureCallMode mode,
        Collection<? extends Callable<R1>> jobs,
        IgniteReducer<R1, R2> rdc,
        @Nullable Collection<ClusterNode> nodes)
    {
        assert mode != null;
        assert rdc != null;
        assert !F.isEmpty(jobs);

        enterBusy();

        try {
            if (F.isEmpty(nodes))
                return ComputeTaskInternalFuture.finishedFuture(ctx, T3.class, U.emptyTopologyException());

            ctx.task().setThreadContext(TC_SUBGRID, nodes);

            return ctx.task().execute(new T3<>(mode, jobs, rdc), null);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param mode Distribution mode.
     * @param jobs Closures to execute.
     * @param nodes Grid nodes.
     * @param <R> Type.
     * @return Grid future for collection of closure results.
     */
    public <R> ComputeTaskInternalFuture<Collection<R>> callAsync(
        GridClosureCallMode mode,
        @Nullable Collection<? extends Callable<R>> jobs,
        @Nullable Collection<ClusterNode> nodes) {
        return callAsync(mode, jobs, nodes, false);
    }

    /**
     * @param mode Distribution mode.
     * @param jobs Closures to execute.
     * @param nodes Grid nodes.
     * @param sys If {@code true}, then system pool will be used.
     * @param <R> Type.
     * @return Grid future for collection of closure results.
     */
    public <R> ComputeTaskInternalFuture<Collection<R>> callAsync(GridClosureCallMode mode,
        Collection<? extends Callable<R>> jobs,
        @Nullable Collection<ClusterNode> nodes,
        boolean sys)
    {
        assert mode != null;
        assert !F.isEmpty(jobs);

        enterBusy();

        try {
            if (F.isEmpty(nodes))
                return ComputeTaskInternalFuture.finishedFuture(ctx, T6.class, U.emptyTopologyException());

            ctx.task().setThreadContext(TC_SUBGRID, nodes);

            return ctx.task().execute(new T6<>(mode, jobs), null, sys);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     *
     * @param mode Distribution mode.
     * @param job Closure to execute.
     * @param nodes Grid nodes.
     * @param <R> Type.
     * @return Grid future for collection of closure results.
     */
    public <R> ComputeTaskInternalFuture<R> callAsync(GridClosureCallMode mode,
        @Nullable Callable<R> job, @Nullable Collection<ClusterNode> nodes) {
        return callAsync(mode, job, nodes, false);
    }

    /**
     * @param cacheName Cache name.
     * @param affKey Affinity key.
     * @param job Job.
     * @param nodes Grid nodes.
     * @return Job future.
     */
    public <R> ComputeTaskInternalFuture<R> affinityCall(@Nullable String cacheName, Object affKey, Callable<R> job,
        @Nullable Collection<ClusterNode> nodes) {
        enterBusy();

        try {
            if (F.isEmpty(nodes))
                return ComputeTaskInternalFuture.finishedFuture(ctx, T5.class, U.emptyTopologyException());

            // In case cache key is passed instead of affinity key.
            final Object affKey0 = ctx.affinity().affinityKey(cacheName, affKey);

            final ClusterNode node = ctx.affinity().mapKeyToNode(cacheName, affKey0);

            ctx.task().setThreadContext(TC_SUBGRID, nodes);

            return ctx.task().execute(new T5(node, job), null, false);
        }
        catch (IgniteCheckedException e) {
            return ComputeTaskInternalFuture.finishedFuture(ctx, T5.class, e);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param cacheName Cache name.
     * @param affKey Affinity key.
     * @param job Job.
     * @param nodes Grid nodes.
     * @return Job future.
     */
    public ComputeTaskInternalFuture<?> affinityRun(@Nullable String cacheName, Object affKey, Runnable job,
        @Nullable Collection<ClusterNode> nodes) {
        enterBusy();

        try {
            if (F.isEmpty(nodes))
                return ComputeTaskInternalFuture.finishedFuture(ctx, T4.class, U.emptyTopologyException());

            // In case cache key is passed instead of affinity key.
            final Object affKey0 = ctx.affinity().affinityKey(cacheName, affKey);

            final ClusterNode node = ctx.affinity().mapKeyToNode(cacheName, affKey0);

            ctx.task().setThreadContext(TC_SUBGRID, nodes);

            return ctx.task().execute(new T4(node, job), null, false);
        }
        catch (IgniteCheckedException e) {
            return ComputeTaskInternalFuture.finishedFuture(ctx, T4.class, e);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param mode Distribution mode.
     * @param job Closure to execute.
     * @param nodes Grid nodes.
     * @param sys If {@code true}, then system pool will be used.
     * @param <R> Type.
     * @return Grid future for collection of closure results.
     */
    public <R> IgniteInternalFuture<R> callAsyncNoFailover(GridClosureCallMode mode, @Nullable Callable<R> job,
        @Nullable Collection<ClusterNode> nodes, boolean sys) {
        assert mode != null;

        enterBusy();

        try {
            if (job == null)
                return new GridFinishedFuture<>(ctx);

            if (F.isEmpty(nodes))
                return new GridFinishedFuture<>(ctx, U.emptyTopologyException());

            ctx.task().setThreadContext(TC_NO_FAILOVER, true);
            ctx.task().setThreadContext(TC_SUBGRID, nodes);

            return ctx.task().execute(new T7<>(mode, job), null, sys);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param mode Distribution mode.
     * @param jobs Closures to execute.
     * @param nodes Grid nodes.
     * @param sys If {@code true}, then system pool will be used.
     * @param <R> Type.
     * @return Grid future for collection of closure results.
     */
    public <R> IgniteInternalFuture<Collection<R>> callAsyncNoFailover(GridClosureCallMode mode,
        @Nullable Collection<? extends Callable<R>> jobs, @Nullable Collection<ClusterNode> nodes,
        boolean sys) {
        assert mode != null;

        enterBusy();

        try {
            if (F.isEmpty(jobs))
                return new GridFinishedFuture<>(ctx);

            if (F.isEmpty(nodes))
                return new GridFinishedFuture<>(ctx, U.emptyTopologyException());

            ctx.task().setThreadContext(TC_NO_FAILOVER, true);
            ctx.task().setThreadContext(TC_SUBGRID, nodes);

            return ctx.task().execute(new T6<>(mode, jobs), null, sys);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param mode Distribution mode.
     * @param job Closure to execute.
     * @param nodes Grid nodes.
     * @param sys If {@code true}, then system pool will be used.
     * @param <R> Type.
     * @return Grid future for collection of closure results.
     */
    public <R> ComputeTaskInternalFuture<R> callAsync(GridClosureCallMode mode,
        Callable<R> job,
        @Nullable Collection<ClusterNode> nodes,
        boolean sys)
    {
        assert mode != null;
        assert job != null;

        enterBusy();

        try {
            if (F.isEmpty(nodes))
                return ComputeTaskInternalFuture.finishedFuture(ctx, T7.class, U.emptyTopologyException());

            ctx.task().setThreadContext(TC_SUBGRID, nodes);

            return ctx.task().execute(new T7<>(mode, job), null, sys);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param job Job closure.
     * @param arg Optional job argument.
     * @param nodes Grid nodes.
     * @return Grid future for execution result.
     */
    public <T, R> ComputeTaskInternalFuture<R> callAsync(IgniteClosure<T, R> job, @Nullable T arg,
        @Nullable Collection<ClusterNode> nodes) {
        enterBusy();

        try {
            if (F.isEmpty(nodes))
                return ComputeTaskInternalFuture.finishedFuture(ctx, T8.class, U.emptyTopologyException());

            ctx.task().setThreadContext(TC_SUBGRID, nodes);

            return ctx.task().execute(new T8(job, arg), null, false);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param job Job closure.
     * @param arg Optional job argument.
     * @param nodes Grid nodes.
     * @return Grid future for execution result.
     */
    public <T, R> IgniteInternalFuture<Collection<R>> broadcast(IgniteClosure<T, R> job, @Nullable T arg,
        @Nullable Collection<ClusterNode> nodes) {
        enterBusy();

        try {
            if (F.isEmpty(nodes))
                return new GridFinishedFuture<>(ctx, U.emptyTopologyException());

            ctx.task().setThreadContext(TC_SUBGRID, nodes);

            return ctx.task().execute(new T11<>(job, arg), null, false);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param job Job closure.
     * @param arg Optional job argument.
     * @param nodes Grid nodes.
     * @return Grid future for execution result.
     */
    public <T, R> IgniteInternalFuture<Collection<R>> broadcastNoFailover(IgniteClosure<T, R> job, @Nullable T arg,
        @Nullable Collection<ClusterNode> nodes) {
        enterBusy();

        try {
            if (F.isEmpty(nodes))
                return new GridFinishedFuture<>(ctx, U.emptyTopologyException());

            ctx.task().setThreadContext(TC_SUBGRID, nodes);
            ctx.task().setThreadContext(TC_NO_FAILOVER, true);

            return ctx.task().execute(new T11<>(job, arg), null, false);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param job Job closure.
     * @param args Job arguments.
     * @param nodes Grid nodes.
     * @return Grid future for execution result.
     */
    public <T, R> ComputeTaskInternalFuture<Collection<R>> callAsync(IgniteClosure<T, R> job,
        @Nullable Collection<? extends T> args,
        @Nullable Collection<ClusterNode> nodes)
    {
        enterBusy();

        try {
            if (F.isEmpty(nodes))
                return ComputeTaskInternalFuture.finishedFuture(ctx, T9.class, U.emptyTopologyException());

            ctx.task().setThreadContext(TC_SUBGRID, nodes);

            return ctx.task().execute(new T9<>(job, args), null, false);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param job Job closure.
     * @param args Job arguments.
     * @param rdc Reducer.
     * @param nodes Grid nodes.
     * @return Grid future for execution result.
     */
    public <T, R1, R2> ComputeTaskInternalFuture<R2> callAsync(IgniteClosure<T, R1> job,
        Collection<? extends T> args, IgniteReducer<R1, R2> rdc, @Nullable Collection<ClusterNode> nodes) {
        enterBusy();

        try {
            if (F.isEmpty(nodes))
                return ComputeTaskInternalFuture.finishedFuture(ctx, T10.class, U.emptyTopologyException());

            ctx.task().setThreadContext(TC_SUBGRID, nodes);

            return ctx.task().execute(new T10<>(job, args, rdc), null, false);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * Gets pool by execution policy.
     *
     * @param plc Whether to get system or public pool.
     * @return Requested worker pool.
     */
    private Executor pool(GridClosurePolicy plc) {
        switch (plc) {
            case PUBLIC_POOL:
                return pubPool;

            case SYSTEM_POOL:
                return sysPool;

            case IGFS_POOL:
                return igfsPool;

            default:
                throw new IllegalArgumentException("Invalid closure execution policy: " + plc);
        }
    }

    /**
     * Gets pool name by execution policy.
     *
     * @param plc Policy to choose executor pool.
     * @return Pool name.
     */
    private String poolName(GridClosurePolicy plc) {
        switch (plc) {
            case PUBLIC_POOL:
                return "public";

            case SYSTEM_POOL:
                return "system";

            case IGFS_POOL:
                return "igfs";

            default:
                throw new IllegalArgumentException("Invalid closure execution policy: " + plc);
        }
    }

    /**
     * @param c Closure to execute.
     * @param sys If {@code true}, then system pool will be used, otherwise public pool will be used.
     * @return Future.
     * @throws IgniteCheckedException Thrown in case of any errors.
     */
    private IgniteInternalFuture<?> runLocal(@Nullable final Runnable c, boolean sys) throws IgniteCheckedException {
        return runLocal(c, sys ? GridClosurePolicy.SYSTEM_POOL : GridClosurePolicy.PUBLIC_POOL);
    }

    /**
     * @param c Closure to execute.
     * @param plc Whether to run on system or public pool.
     * @return Future.
     * @throws IgniteCheckedException Thrown in case of any errors.
     */
    private IgniteInternalFuture<?> runLocal(@Nullable final Runnable c, GridClosurePolicy plc) throws IgniteCheckedException {
        if (c == null)
            return new GridFinishedFuture(ctx);

        enterBusy();

        try {
            // Inject only if needed.
            if (!(c instanceof GridPlainRunnable))
                ctx.resource().inject(ctx.deploy().getDeployment(c.getClass().getName()), c.getClass(), c);

            final ClassLoader ldr = Thread.currentThread().getContextClassLoader();

            final GridWorkerFuture fut = new GridWorkerFuture(ctx);

            workersCnt.increment();

            GridWorker w = new GridWorker(ctx.gridName(), "closure-proc-worker", log) {
                @Override protected void body() {
                    try {
                        if (ldr != null)
                            U.wrapThreadLoader(ldr, c);
                        else
                            c.run();

                        fut.onDone();
                    }
                    catch (Throwable e) {
                        if (e instanceof Error)
                            U.error(log, "Closure execution failed with error.", e);

                        fut.onDone(U.cast(e));
                    }
                    finally {
                        workersCnt.decrement();
                    }
                }
            };

            fut.setWorker(w);

            try {
                pool(plc).execute(w);
            }
            catch (RejectedExecutionException e) {
                U.error(log, "Failed to execute worker due to execution rejection " +
                    "(increase upper bound on " + poolName(plc) + " executor service).", e);

                w.run();
            }

            return fut;
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * Executes closure on system pool. Companion to {@link #runLocal(Runnable, boolean)} but
     * in case of rejected execution re-runs the closure in the current thread (blocking).
     *
     * @param c Closure to execute.
     * @return Future.
     */
    public IgniteInternalFuture<?> runLocalSafe(Runnable c) {
        return runLocalSafe(c, true);
    }

    /**
     * Companion to {@link #runLocal(Runnable, boolean)} but in case of rejected execution re-runs
     * the closure in the current thread (blocking).
     *
     * @param c Closure to execute.
     * @param sys If {@code true}, then system pool will be used, otherwise public pool will be used.
     * @return Future.
     */
    public IgniteInternalFuture<?> runLocalSafe(Runnable c, boolean sys) {
        return runLocalSafe(c, sys ? GridClosurePolicy.SYSTEM_POOL : GridClosurePolicy.PUBLIC_POOL);
    }

    /**
     * Companion to {@link #runLocal(Runnable, boolean)} but in case of rejected execution re-runs
     * the closure in the current thread (blocking).
     *
     * @param c Closure to execute.
     * @param plc Policy to choose executor pool.
     * @return Future.
     */
    public IgniteInternalFuture<?> runLocalSafe(Runnable c, GridClosurePolicy plc) {
        try {
            return runLocal(c, plc);
        }
        catch (Throwable e) {
            if (e instanceof Error)
                U.error(log, "Closure execution failed with error.", e);

            // If execution was rejected - rerun locally.
            if (e.getCause() instanceof RejectedExecutionException) {
                U.warn(log, "Closure execution has been rejected (will execute in the same thread) [plc=" + plc +
                    ", closure=" + c + ']');

                try {
                    c.run();

                    return new GridFinishedFuture(ctx);
                }
                catch (Throwable t) {
                    if (t instanceof Error)
                        U.error(log, "Closure execution failed with error.", t);

                    return new GridFinishedFuture(ctx, U.cast(t));
                }
            }
            // If failed for other reasons - return error future.
            else
                return new GridFinishedFuture(ctx, U.cast(e));
        }
    }

    /**
     * @param c Closure to execute.
     * @param sys If {@code true}, then system pool will be used, otherwise public pool will be used.
     * @return Future.
     * @throws IgniteCheckedException Thrown in case of any errors.
     */
    private <R> IgniteInternalFuture<R> callLocal(@Nullable final Callable<R> c, boolean sys) throws IgniteCheckedException {
        return callLocal(c, sys ? GridClosurePolicy.SYSTEM_POOL : GridClosurePolicy.PUBLIC_POOL);
    }

    /**
     * @param c Closure to execute.
     * @param plc Whether to run on system or public pool.
     * @param <R> Type of closure return value.
     * @return Future.
     * @throws IgniteCheckedException Thrown in case of any errors.
     */
    private <R> IgniteInternalFuture<R> callLocal(@Nullable final Callable<R> c, GridClosurePolicy plc) throws IgniteCheckedException {
        if (c == null)
            return new GridFinishedFuture<>(ctx);

        enterBusy();

        try {
            // Inject only if needed.
            if (!(c instanceof GridPlainCallable))
                ctx.resource().inject(ctx.deploy().getDeployment(c.getClass().getName()), c.getClass(), c);

            final ClassLoader ldr = Thread.currentThread().getContextClassLoader();

            final GridWorkerFuture<R> fut = new GridWorkerFuture<>(ctx);

            workersCnt.increment();

            GridWorker w = new GridWorker(ctx.gridName(), "closure-proc-worker", log) {
                @Override protected void body() {
                    try {
                        if (ldr != null)
                            fut.onDone(U.wrapThreadLoader(ldr, c));
                        else
                            fut.onDone(c.call());
                    }
                    catch (Throwable e) {
                        if (e instanceof Error)
                            U.error(log, "Closure execution failed with error.", e);

                        fut.onDone(U.cast(e));
                    }
                    finally {
                        workersCnt.decrement();
                    }
                }
            };

            fut.setWorker(w);

            try {
                pool(plc).execute(w);
            }
            catch (RejectedExecutionException e) {
                U.error(log, "Failed to execute worker due to execution rejection " +
                    "(increase upper bound on " + poolName(plc) + " executor service).", e);

                w.run();
            }

            return fut;
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * Executes closure on system pool. Companion to {@link #callLocal(Callable, boolean)}
     * but in case of rejected execution re-runs the closure in the current thread (blocking).
     *
     * @param c Closure to execute.
     * @return Future.
     */
    public <R> IgniteInternalFuture<R> callLocalSafe(Callable<R> c) {
        return callLocalSafe(c, true);
    }

    /**
     * Executes closure on system pool. Companion to {@link #callLocal(Callable, boolean)}
     * but in case of rejected execution re-runs the closure in the current thread (blocking).
     *
     * @param c Closure to execute.
     * @param sys If {@code true}, then system pool will be used, otherwise public pool will be used.
     * @return Future.
     */
    public <R> IgniteInternalFuture<R> callLocalSafe(Callable<R> c, boolean sys) {
        return callLocalSafe(c, sys ? GridClosurePolicy.SYSTEM_POOL : GridClosurePolicy.PUBLIC_POOL);
    }

    /**
     * Companion to {@link #callLocal(Callable, boolean)} but in case of rejected execution re-runs
     * the closure in the current thread (blocking).
     *
     * @param c Closure to execute.
     * @param plc Policy to choose executor pool.
     * @return Future.
     */
    public <R> IgniteInternalFuture<R> callLocalSafe(Callable<R> c, GridClosurePolicy plc) {
        try {
            return callLocal(c, plc);
        }
        catch (IgniteCheckedException e) {
            // If execution was rejected - rerun locally.
            if (e.getCause() instanceof RejectedExecutionException) {
                U.warn(log, "Closure execution has been rejected (will execute in the same thread) [plc=" + plc +
                    ", closure=" + c + ']');

                try {
                    return new GridFinishedFuture<>(ctx, c.call());
                }
                // If failed again locally - return error future.
                catch (Exception e2) {
                    return new GridFinishedFuture<>(ctx, U.cast(e2));
                }
            }
            // If failed for other reasons - return error future.
            else
                return new GridFinishedFuture<>(ctx, U.cast(e));
        }
    }

    /**
     * Converts given closure with arguments to grid job.
     * @param job Job.
     * @param arg Optional argument.
     * @return Job.
     */
    private static <T, R> ComputeJob job(final IgniteClosure<T, R> job, @Nullable final T arg) {
        A.notNull(job, "job");

        return job instanceof ComputeJobMasterLeaveAware ? new C1MLA<>(job, arg) : new C1<>(job, arg);
    }

    /**
     * Converts given closure to a grid job.
     *
     * @param c Closure to convert to grid job.
     * @return Grid job made out of closure.
     */
    private static <R> ComputeJob job(final Callable<R> c) {
        A.notNull(c, "job");

        return c instanceof ComputeJobMasterLeaveAware ? new C2MLA<>(c) : new C2<>(c);
    }

    /**
     * Converts given closure to a grid job.
     *
     * @param r Closure to convert to grid job.
     * @return Grid job made out of closure.
     */
    private static ComputeJob job(final Runnable r) {
        A.notNull(r, "job");

       return r instanceof ComputeJobMasterLeaveAware ? new C4MLA(r) : new C4(r);
    }

    /**
     *
     */
    private class JobMapper {
        /** */
        private final Map<ComputeJob, ClusterNode> map;

        /** */
        private boolean hadLocNode;

        /** */
        private byte[] closureBytes;

        /** */
        private IgniteClosure<?, ?> closure;

        /**
         * @param expJobCnt Expected Jobs count.
         */
        private JobMapper(int expJobCnt) {
            map = IgniteUtils.newHashMap(expJobCnt);
        }

        /**
         * @param job Job.
         * @param node Node.
         * @throws IgniteCheckedException In case of error.
         */
        public void map(@NotNull ComputeJob job, @NotNull ClusterNode node) throws IgniteCheckedException {
            if (ctx.localNodeId().equals(node.id())) {
                if (hadLocNode) {
                    Marshaller marsh = ctx.config().getMarshaller();

                    if (job instanceof C1) {
                        C1 c = (C1)job;

                        if (closureBytes == null) {
                            closure = c.job;

                            closureBytes = marsh.marshal(c.job);
                        }

                        if (c.job == closure)
                            c.job = marsh.unmarshal(closureBytes, null);
                        else
                            c.job = marsh.unmarshal(marsh.marshal(c.job), null);

                        c.arg = marsh.unmarshal(marsh.marshal(c.arg), null);
                    }
                    else
                        job = marsh.unmarshal(marsh.marshal(job), null);
                }
                else
                    hadLocNode = true;
            }

            map.put(job, node);
        }

        /**
         *
         */
        public Map<ComputeJob, ClusterNode> map() {
            return map;
        }
    }

    /**
     * No-reduce task adapter.
     */
    private abstract static class TaskNoReduceAdapter<T> extends GridPeerDeployAwareTaskAdapter<T, Void> {
        /** */
        private static final long serialVersionUID = 0L;

        /**
         * @param pda Peer deploy aware instance.
         */
        protected TaskNoReduceAdapter(@Nullable GridPeerDeployAware pda) {
            super(pda);
        }

        /** {@inheritDoc} */
        @Nullable @Override public Void reduce(List<ComputeJobResult> results) {
            return null;
        }
    }

    /**
     * Task that is free of dragged in enclosing context for the method
     * {@link GridClosureProcessor#runAsync(GridClosureCallMode, Collection, Collection)}.
     */
    private class T1 extends TaskNoReduceAdapter<Void> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        @LoadBalancerResource
        private ComputeLoadBalancer lb;

        /** */
        private IgniteBiTuple<GridClosureCallMode, Collection<? extends Runnable>> t;

        /**
         * @param mode Call mode.
         * @param jobs Collection of jobs.
         */
        private T1(GridClosureCallMode mode, Collection<? extends Runnable> jobs) {
            super(U.peerDeployAware0(jobs));

            t = F.<
                GridClosureCallMode,
                Collection<? extends Runnable>
                >t(mode, jobs);
        }

        /** {@inheritDoc} */
        @Override public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, @Nullable Void arg) {
            return absMap(t.get1(), t.get2(), subgrid, lb);
        }
    }

    /**
     * Task that is free of dragged in enclosing context for the method
     * {@link GridClosureProcessor#runAsync(GridClosureCallMode, Runnable, Collection)}.
     */
    private class T2 extends TaskNoReduceAdapter<Void> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        @LoadBalancerResource
        private ComputeLoadBalancer lb;

        /** */
        private IgniteBiTuple<GridClosureCallMode, Runnable> t;

        /**
         * @param mode Call mode.
         * @param job Job.
         */
        private T2(GridClosureCallMode mode, Runnable job) {
            super(U.peerDeployAware(job));

            t = F.t(mode, job);
        }

        /** {@inheritDoc} */
        @Override public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, @Nullable Void arg) {
            return absMap(t.get1(), F.asList(t.get2()), subgrid, lb);
        }
    }

    /**
     * Task that is free of dragged in enclosing context for the method
     * {@link GridClosureProcessor#forkjoinAsync(GridClosureCallMode, Collection, org.apache.ignite.lang.IgniteReducer, Collection)}
     */
    private class T3<R1, R2> extends GridPeerDeployAwareTaskAdapter<Void, R2> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        @LoadBalancerResource
        private ComputeLoadBalancer lb;

        /** */
        private GridTuple3<GridClosureCallMode,
            Collection<? extends Callable<R1>>,
            IgniteReducer<R1, R2>
            > t;

        /**
         *
         * @param mode Call mode.
         * @param jobs Collection of jobs.
         * @param rdc Reducer.
         */
        private T3(GridClosureCallMode mode, Collection<? extends Callable<R1>> jobs, IgniteReducer<R1, R2> rdc) {
            super(U.peerDeployAware0(jobs));

            t = F.<
                GridClosureCallMode,
                Collection<? extends Callable<R1>>,
                IgniteReducer<R1, R2>
                >t(mode, jobs, rdc);
        }

        /** {@inheritDoc} */
        @Override public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, @Nullable Void arg) {
            return outMap(t.get1(), t.get2(), subgrid, lb);
        }

        /** {@inheritDoc} */
        @Override public ComputeJobResultPolicy result(ComputeJobResult res, List<ComputeJobResult> rcvd) {
            ComputeJobResultPolicy resPlc = super.result(res, rcvd);

            if (res.getException() == null && resPlc != FAILOVER && !t.get3().collect((R1)res.getData()))
                resPlc = REDUCE; // If reducer returned false - reduce right away.

            return resPlc;
        }

        /** {@inheritDoc} */
        @Override public R2 reduce(List<ComputeJobResult> res) {
            return t.get3().reduce();
        }
    }

    /**
     */
    private static class T4 extends TaskNoReduceAdapter<Void> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private ClusterNode node;

        /** */
        private Runnable job;

        /**
         * @param node Cluster node.
         * @param job Job.
         */
        private T4(ClusterNode node, Runnable job) {
            super(U.peerDeployAware0(job));

            this.node = node;
            this.job = job;
        }

        /** {@inheritDoc} */
        @Override public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, @Nullable Void arg) {
            ComputeJob job = job(this.job);

            return Collections.singletonMap(job, node);
        }
    }

    /**
     */
    private static class T5<R> extends GridPeerDeployAwareTaskAdapter<Void, R> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private ClusterNode node;

        /** */
        private Callable<R> job;

        /**
         * @param node Cluster node.
         * @param job Job.
         */
        private T5(ClusterNode node, Callable<R> job) {
            super(U.peerDeployAware0(job));

            this.node = node;
            this.job = job;
        }

        /** {@inheritDoc} */
        @Override public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, @Nullable Void arg) {
            ComputeJob job = job(this.job);

            return Collections.singletonMap(job, node);
        }

        /** {@inheritDoc} */
        @Override public R reduce(List<ComputeJobResult> res) {
            for (ComputeJobResult r : res) {
                if (r.getException() == null)
                    return r.getData();
            }

            throw new IgniteException("Failed to find successful job result: " + res);
        }
    }

    /**
     * Task that is free of dragged in enclosing context for the method
     * {@link GridClosureProcessor#callAsync(GridClosureCallMode, Collection, Collection)}
     */
    private class T6<R> extends GridPeerDeployAwareTaskAdapter<Void, Collection<R>> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private final GridClosureCallMode mode;

        /** */
        private final Collection<? extends Callable<R>> jobs;

        /** */
        @LoadBalancerResource
        private ComputeLoadBalancer lb;

        /**
         *
         * @param mode Call mode.
         * @param jobs Collection of jobs.
         */
        private T6(
            GridClosureCallMode mode,
            Collection<? extends Callable<R>> jobs) {
            super(U.peerDeployAware0(jobs));

            this.mode = mode;
            this.jobs = jobs;
        }

        /** {@inheritDoc} */
        @Override public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, @Nullable Void arg) {
            return outMap(mode, jobs, subgrid, lb);
        }

        /** {@inheritDoc} */
        @Override public Collection<R> reduce(List<ComputeJobResult> res) {
            return F.jobResults(res);
        }
    }

    /**
     * Task that is free of dragged in enclosing context for the method
     * {@link GridClosureProcessor#callAsync(GridClosureCallMode, Callable, Collection)}
     */
    private class T7<R> extends GridPeerDeployAwareTaskAdapter<Void, R> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private IgniteBiTuple<GridClosureCallMode, Callable<R>> t;

        /** */
        @LoadBalancerResource
        private ComputeLoadBalancer lb;

        /**
         * @param mode Call mode.
         * @param job Job.
         */
        private T7(GridClosureCallMode mode, Callable<R> job) {
            super(U.peerDeployAware(job));

            t = F.t(mode, job);
        }

        /** {@inheritDoc} */
        @Override public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, @Nullable Void arg) {
            return outMap(t.get1(), F.asList(t.get2()), subgrid, lb);
        }

        /** {@inheritDoc} */
        @Override public R reduce(List<ComputeJobResult> res) {
            for (ComputeJobResult r : res)
                if (r.getException() == null)
                    return r.getData();

            throw new IgniteException("Failed to find successful job result: " + res);
        }
    }

    /**
     */
    private static class T8<T, R> extends GridPeerDeployAwareTaskAdapter<Void, R> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private IgniteClosure<T, R> job;

        /** */
        private T arg;

        /** */
        @LoadBalancerResource
        private ComputeLoadBalancer lb;

        /**
         * @param job Job.
         * @param arg Optional job argument.
         */
        private T8(IgniteClosure<T, R> job, @Nullable T arg) {
            super(U.peerDeployAware(job));

            this.job = job;
            this.arg = arg;
        }

        /** {@inheritDoc} */
        @Override public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, @Nullable Void arg) {
            ComputeJob job = job(this.job, this.arg);

            return Collections.singletonMap(job, lb.getBalancedNode(job, null));
        }

        /** {@inheritDoc} */
        @Override public R reduce(List<ComputeJobResult> res) throws IgniteException {
            for (ComputeJobResult r : res)
                if (r.getException() == null)
                    return r.getData();

            throw new IgniteException("Failed to find successful job result: " + res);
        }
    }

    /**
     */
    private class T9<T, R> extends GridPeerDeployAwareTaskAdapter<Void, Collection<R>> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private IgniteClosure<T, R> job;

        /** */
        private Collection<? extends T> args;

        /** */
        @LoadBalancerResource
        private ComputeLoadBalancer lb;

        /**
         * @param job Job.
         * @param args Job arguments.
         */
        private T9(IgniteClosure<T, R> job, Collection<? extends T> args) {
            super(U.peerDeployAware(job));

            this.job = job;
            this.args = args;
        }

        /** {@inheritDoc} */
        @Override public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, @Nullable Void arg) {
            try {
                JobMapper mapper = new JobMapper(args.size());

                for (T jobArg : args) {
                    ComputeJob job = job(this.job, jobArg);

                    mapper.map(job, lb.getBalancedNode(job, null));
                }

                return mapper.map();
            }
            catch (IgniteCheckedException e) {
                throw new IgniteException(e);
            }
        }

        /** {@inheritDoc} */
        @Override public Collection<R> reduce(List<ComputeJobResult> res) throws IgniteException {
            return F.jobResults(res);
        }
    }

    /**
     */
    private class T10<T, R1, R2> extends GridPeerDeployAwareTaskAdapter<Void, R2> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private IgniteClosure<T, R1> job;

        /** */
        private Collection<? extends T> args;

        /** */
        private IgniteReducer<R1, R2> rdc;

        /** */
        @LoadBalancerResource
        private ComputeLoadBalancer lb;

        /**
         * @param job Job.
         * @param args Job arguments.
         * @param rdc Reducer.
         */
        private T10(IgniteClosure<T, R1> job, Collection<? extends T> args, IgniteReducer<R1, R2> rdc) {
            super(U.peerDeployAware(job));

            this.job = job;
            this.args = args;
            this.rdc = rdc;
        }

        /** {@inheritDoc} */
        @Override public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, @Nullable Void arg) {
            try {
                JobMapper mapper = new JobMapper(args.size());

                for (T jobArg : args) {
                    ComputeJob job = job(this.job, jobArg);

                    mapper.map(job, lb.getBalancedNode(job, null));
                }

                return mapper.map();
            }
            catch (IgniteCheckedException e) {
                throw new IgniteException(e);
            }
        }

        /** {@inheritDoc} */
        @Override public ComputeJobResultPolicy result(ComputeJobResult res, List<ComputeJobResult> rcvd) {
            ComputeJobResultPolicy resPlc = super.result(res, rcvd);

            if (res.getException() == null && resPlc != FAILOVER && !rdc.collect((R1) res.getData()))
                resPlc = REDUCE; // If reducer returned false - reduce right away.

            return resPlc;
        }

        /** {@inheritDoc} */
        @Override public R2 reduce(List<ComputeJobResult> res) {
            return rdc.reduce();
        }
    }

    /**
     */
    private class T11<T, R> extends GridPeerDeployAwareTaskAdapter<Void, Collection<R>> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private final IgniteClosure<T, R> job;

        /** */
        private final T arg;

        /**
         * @param job Job.
         * @param arg Job argument.
         */
        private T11(IgniteClosure<T, R> job, @Nullable T arg) {
            super(U.peerDeployAware(job));

            this.job = job;
            this.arg = arg;
        }

        /** {@inheritDoc} */
        @Override public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, @Nullable Void arg) {
            if (F.isEmpty(subgrid))
                return Collections.emptyMap();

            try {
                JobMapper mapper = new JobMapper(subgrid.size());

                for (ClusterNode n : subgrid)
                    mapper.map(job(job, this.arg), n);

                return mapper.map();
            }
            catch (IgniteCheckedException e) {
                throw new IgniteException(e);
            }
        }

        /** {@inheritDoc} */
        @Override public Collection<R> reduce(List<ComputeJobResult> res) {
            return F.jobResults(res);
        }
    }

    /**
     *
     */
    private static class C1<T, R> implements ComputeJob, Externalizable {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        protected IgniteClosure<T, R> job;

        /** */
        @GridToStringInclude
        private T arg;

        /**
         *
         */
        public C1(){
            // No-op.
        }

        /**
         * @param job Job.
         * @param arg Argument.
         */
        C1(IgniteClosure<T, R> job, T arg) {
            this.job = job;
            this.arg = arg;
        }

        /** {@inheritDoc} */
        @Nullable @Override public Object execute() {
            return job.apply(arg);
        }

        /** {@inheritDoc} */
        @Override public void cancel() {
            // No-op.
        }

        /** {@inheritDoc} */
        @Override public void writeExternal(ObjectOutput out) throws IOException {
            out.writeObject(job);
            out.writeObject(arg);
        }

        /** {@inheritDoc} */
        @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            job = (IgniteClosure<T, R>)in.readObject();
            arg = (T)in.readObject();
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(C1.class, this);
        }
    }

    /**
     *
     */
    private static class C1MLA<T, R> extends C1<T, R> implements ComputeJobMasterLeaveAware {
        /** */
        private static final long serialVersionUID = 0L;

        /**
         *
         */
        public C1MLA() {
            super();
        }

        /**
         * @param job Job.
         * @param arg Argument.
         */
        private C1MLA(IgniteClosure<T, R> job, T arg) {
            super(job, arg);
        }

        /** {@inheritDoc} */
        @Override public void onMasterNodeLeft(ComputeTaskSession ses) {
            ((ComputeJobMasterLeaveAware)job).onMasterNodeLeft(ses);
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(C1MLA.class, this, super.toString());
        }
    }

    /**
     *
     */
    private static class C2<R> implements ComputeJob, Externalizable {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        protected Callable<R> c;

        /**
         *
         */
        public C2(){
            // No-op.
        }

        /**
         * @param c Callable.
         */
        private C2(Callable<R> c) {
            this.c = c;
        }

        /** {@inheritDoc} */
        @Override public Object execute() {
            try {
                return c.call();
            }
            catch (Exception e) {
                throw new IgniteException(e);
            }
        }

        /** {@inheritDoc} */
        @Override public void cancel() {
            // No-op.
        }

        /** {@inheritDoc} */
        @Override public void writeExternal(ObjectOutput out) throws IOException {
            out.writeObject(c);
        }

        /** {@inheritDoc} */
        @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            c = (Callable<R>)in.readObject();
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(C2.class, this);
        }
    }

    /**
     *
     */
    private static class C2MLA<R> extends C2<R> implements ComputeJobMasterLeaveAware{
        /** */
        private static final long serialVersionUID = 0L;

        /**
         *
         */
        public C2MLA() {
            super();
        }

        /**
         * @param c Callable.
         */
        private C2MLA(Callable<R> c) {
            super(c);
        }

        /** {@inheritDoc} */
        @Override public void onMasterNodeLeft(ComputeTaskSession ses) {
            ((ComputeJobMasterLeaveAware)c).onMasterNodeLeft(ses);
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(C2MLA.class, this, super.toString());
        }
    }

    /**
     */
    private static class C4 implements ComputeJob, Externalizable {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        protected Runnable r;

        /**
         *
         */
        public C4(){
            // No-op.
        }

        /**
         * @param r Runnable.
         */
        private C4(Runnable r) {
            this.r = r;
        }

        /** {@inheritDoc} */
        @Nullable @Override public Object execute() {
            r.run();

            return null;
        }

        /** {@inheritDoc} */
        @Override public void cancel() {
            // No-op.
        }

        /** {@inheritDoc} */
        @Override public void writeExternal(ObjectOutput out) throws IOException {
            out.writeObject(r);
        }

        /** {@inheritDoc} */
        @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            r = (Runnable)in.readObject();
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(C4.class, this);
        }
    }

    /**
     *
     */
    private static class C4MLA extends C4 implements ComputeJobMasterLeaveAware{
        /** */
        private static final long serialVersionUID = 0L;

        /**
         *
         */
        public C4MLA() {
            super();
        }

        /**
         * @param r Runnable.
         */
        private C4MLA(Runnable r) {
            super(r);
        }

        /** {@inheritDoc} */
        @Override public void onMasterNodeLeft(ComputeTaskSession ses) {
            ((ComputeJobMasterLeaveAware)r).onMasterNodeLeft(ses);
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(C4MLA.class, this, super.toString());
        }
    }
}
