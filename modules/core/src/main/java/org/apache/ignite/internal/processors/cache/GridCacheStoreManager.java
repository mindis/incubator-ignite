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
import org.apache.ignite.cache.store.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.cache.store.*;
import org.apache.ignite.internal.processors.cache.transactions.*;
import org.apache.ignite.internal.processors.cache.version.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.lang.*;
import org.apache.ignite.internal.util.tostring.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.lifecycle.*;
import org.apache.ignite.transactions.*;
import org.jetbrains.annotations.*;

import javax.cache.*;
import javax.cache.integration.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * Store manager.
 */
@SuppressWarnings("AssignmentToCatchBlockParameter")
public class GridCacheStoreManager<K, V> extends GridCacheManagerAdapter<K, V> {
    /** */
    private static final String SES_ATTR = "STORE_SES";

    /** */
    private final CacheStore<K, Object> store;

    /** */
    private final CacheStore<?, ?> cfgStore;

    /** */
    private final CacheStoreBalancingWrapper<K, Object> singleThreadGate;

    /** */
    private final ThreadLocal<SessionData> sesHolder;

    /** */
    private final boolean locStore;

    /** */
    private final boolean writeThrough;

    /** */
    private final boolean sesEnabled;

    /** */
    private boolean convertPortable;

    /**
     * @param ctx Kernal context.
     * @param sesHolders Session holders map to use the same session holder for different managers if they use
     *        the same store instance.
     * @param cfgStore Store provided in configuration.
     * @param cfg Cache configuration.
     * @throws IgniteCheckedException In case of error.
     */
    @SuppressWarnings("unchecked")
    public GridCacheStoreManager(GridKernalContext ctx,
        Map<CacheStore, ThreadLocal> sesHolders,
        @Nullable CacheStore<K, Object> cfgStore,
        CacheConfiguration cfg) throws IgniteCheckedException {
        this.cfgStore = cfgStore;

        store = cacheStoreWrapper(ctx, cfgStore, cfg);

        singleThreadGate = store == null ? null : new CacheStoreBalancingWrapper<>(store);

        writeThrough = cfg.isWriteThrough();

        ThreadLocal<SessionData> sesHolder0 = null;

        boolean sesEnabled0 = false;

        if (cfgStore != null) {
            if (!sesHolders.containsKey(cfgStore)) {
                sesHolder0 = new ThreadLocal<>();

                sesEnabled0 = ctx.resource().injectStoreSession(cfgStore, new ThreadLocalSession(sesHolder0));

                if (sesEnabled0)
                    sesHolders.put(cfgStore, sesHolder0);
                else
                    sesHolder0 = null;
            }
            else {
                sesHolder0 = sesHolders.get(cfgStore);

                sesEnabled0 = true;
            }
        }

        sesEnabled = sesEnabled0;

        sesHolder = sesHolder0;

        locStore = U.hasAnnotation(cfgStore, CacheLocalStore.class);

        assert sesHolder != null || !sesEnabled;
    }

    /**
     * @return {@code True} is write-through is enabled.
     */
    public boolean writeThrough() {
        return writeThrough;
    }

    /**
     * @return Unwrapped store provided in configuration.
     */
    public CacheStore<?, ?> configuredStore() {
        return cfgStore;
    }

    /**
     * Creates a wrapped cache store if write-behind cache is configured.
     *
     * @param ctx Kernal context.
     * @param cfgStore Store provided in configuration.
     * @param cfg Cache configuration.
     * @return Instance if {@link GridCacheWriteBehindStore} if write-behind store is configured,
     *         or user-defined cache store.
     */
    @SuppressWarnings({"unchecked"})
    private CacheStore cacheStoreWrapper(GridKernalContext ctx,
        @Nullable CacheStore cfgStore,
        CacheConfiguration cfg) {
        if (cfgStore == null || !cfg.isWriteBehindEnabled())
            return cfgStore;

        GridCacheWriteBehindStore store = new GridCacheWriteBehindStore(ctx.gridName(),
            cfg.getName(),
            ctx.log(GridCacheWriteBehindStore.class),
            cfgStore);

        store.setFlushSize(cfg.getWriteBehindFlushSize());
        store.setFlushThreadCount(cfg.getWriteBehindFlushThreadCount());
        store.setFlushFrequency(cfg.getWriteBehindFlushFrequency());
        store.setBatchSize(cfg.getWriteBehindBatchSize());

        return store;
    }

    /** {@inheritDoc} */
    @Override protected void start0() throws IgniteCheckedException {
        if (store instanceof LifecycleAware) {
            try {
                // Avoid second start() call on store in case when near cache is enabled.
                if (cctx.config().isWriteBehindEnabled()) {
                    if (!cctx.isNear())
                        ((LifecycleAware)store).start();
                }
            }
            catch (Exception e) {
                throw new IgniteCheckedException("Failed to start cache store: " + e, e);
            }
        }

        boolean convertPortable = !cctx.keepPortableInStore();

        if (cctx.portableEnabled())
            this.convertPortable = convertPortable;
        else if (convertPortable)
            U.warn(log, "CacheConfiguration.isKeepPortableInStore() configuration property will " +
                "be ignored because portable mode is not enabled for cache: " + cctx.namex());
    }

    /** {@inheritDoc} */
    @Override protected void stop0(boolean cancel) {
        if (store instanceof LifecycleAware) {
            try {
                // Avoid second start() call on store in case when near cache is enabled.
                if (cctx.config().isWriteBehindEnabled()) {
                    if (!cctx.isNear())
                        ((LifecycleAware)store).stop();
                }
            }
            catch (Exception e) {
                U.error(log(), "Failed to stop cache store.", e);
            }
        }
    }

    /**
     * @return Convert-portable flag.
     */
    public boolean convertPortable() {
        return convertPortable;
    }

    /**
     * @param convertPortable Convert-portable flag.
     */
    public void convertPortable(boolean convertPortable) {
        this.convertPortable = convertPortable;
    }

    /**
     * @return {@code true} If local store is configured.
     */
    public boolean isLocalStore() {
        return locStore;
    }

    /**
     * @return {@code true} If store configured.
     */
    public boolean configured() {
        return store != null;
    }

    /**
     * Loads data from persistent store.
     *
     * @param tx Cache transaction.
     * @param key Cache key.
     * @return Loaded value, possibly <tt>null</tt>.
     * @throws IgniteCheckedException If data loading failed.
     */
    @SuppressWarnings("unchecked")
    @Nullable public V loadFromStore(@Nullable IgniteInternalTx tx, K key) throws IgniteCheckedException {
        return (V)loadFromStore(tx, key, true);
    }

    /**
     * Loads data from persistent store.
     *
     * @param tx Cache transaction.
     * @param key Cache key.
     * @param convert Convert flag.
     * @return Loaded value, possibly <tt>null</tt>.
     * @throws IgniteCheckedException If data loading failed.
     */
    @SuppressWarnings("unchecked")
    @Nullable private Object loadFromStore(@Nullable IgniteInternalTx tx,
        K key,
        boolean convert)
        throws IgniteCheckedException {
        if (store != null) {
            if (key instanceof GridCacheInternal)
                // Never load internal keys from store as they are never persisted.
                return null;

            if (convertPortable)
                key = (K)cctx.unwrapPortableIfNeeded(key, false);

            if (log.isDebugEnabled())
                log.debug("Loading value from store for key: " + key);

            boolean ses = initSession(tx);

            Object val = null;

            try {
                val = singleThreadGate.load(key);
            }
            catch (ClassCastException e) {
                handleClassCastException(e);
            }
            catch (CacheLoaderException e) {
                throw new IgniteCheckedException(e);
            }
            catch (Exception e) {
                throw new IgniteCheckedException(new CacheLoaderException(e));
            }
            finally {
                if (ses)
                    sesHolder.set(null);
            }

            if (log.isDebugEnabled())
                log.debug("Loaded value from store [key=" + key + ", val=" + val + ']');

            if (convert) {
                val = convert(val);

                return cctx.portableEnabled() ? cctx.marshalToPortable(val) : val;
            }
            else
                return val;
        }

        return null;
    }

    /**
     * @param val Internal value.
     * @return User value.
     */
    @SuppressWarnings("unchecked")
    private V convert(Object val) {
        if (val == null)
            return null;

        return locStore ? ((IgniteBiTuple<V, GridCacheVersion>)val).get1() : (V)val;
    }

    /**
     * @return Whether DHT transaction can write to store from DHT.
     */
    public boolean writeToStoreFromDht() {
        return cctx.config().isWriteBehindEnabled() || locStore;
    }

    /**
     * @param tx Cache transaction.
     * @param keys Cache keys.
     * @param vis Closure to apply for loaded elements.
     * @throws IgniteCheckedException If data loading failed.
     */
    public void localStoreLoadAll(@Nullable IgniteInternalTx tx,
        Collection<? extends K> keys,
        final GridInClosure3<K, V, GridCacheVersion> vis)
        throws IgniteCheckedException {
        assert store != null;
        assert locStore;

        loadAllFromStore(tx, keys, null, vis);
    }

    /**
     * Loads data from persistent store.
     *
     * @param tx Cache transaction.
     * @param keys Cache keys.
     * @param vis Closure.
     * @return {@code True} if there is a persistent storage.
     * @throws IgniteCheckedException If data loading failed.
     */
    @SuppressWarnings({"unchecked"})
    public boolean loadAllFromStore(@Nullable IgniteInternalTx tx,
        Collection<? extends K> keys,
        final IgniteBiInClosure<K, V> vis) throws IgniteCheckedException {
        if (store != null) {
            loadAllFromStore(tx, keys, vis, null);

            return true;
        }
        else {
            for (K key : keys)
                vis.apply(key, null);
        }

        return false;
    }

    /**
     * @param tx Cache transaction.
     * @param keys Keys to load.
     * @param vis Key/value closure (only one of vis or verVis can be specified).
     * @param verVis Key/value/version closure (only one of vis or verVis can be specified).
     * @throws IgniteCheckedException If failed.
     */
    @SuppressWarnings("unchecked")
    private void loadAllFromStore(@Nullable IgniteInternalTx tx,
        Collection<? extends K> keys,
        @Nullable final IgniteBiInClosure<K, V> vis,
        @Nullable final GridInClosure3<K, V, GridCacheVersion> verVis)
        throws IgniteCheckedException {
        assert vis != null ^ verVis != null;
        assert verVis == null || locStore;

        final boolean convert = verVis == null;

        if (!keys.isEmpty()) {
            if (keys.size() == 1) {
                K key = F.first(keys);

                if (convert)
                    vis.apply(key, loadFromStore(tx, key));
                else {
                    IgniteBiTuple<V, GridCacheVersion> t =
                        (IgniteBiTuple<V, GridCacheVersion>)loadFromStore(tx, key, false);

                    if (t != null)
                        verVis.apply(key, t.get1(), t.get2());
                }

                return;
            }

            Collection<? extends K> keys0 = convertPortable ?
                F.viewReadOnly(keys, new C1<K, K>() {
                    @Override public K apply(K k) {
                        return (K)cctx.unwrapPortableIfNeeded(k, false);
                    }
                }) :
                keys;

            if (log.isDebugEnabled())
                log.debug("Loading values from store for keys: " + keys0);

            boolean ses = initSession(tx);

            try {
                IgniteBiInClosure<K,Object> c = new CI2<K, Object>() {
                    @SuppressWarnings("ConstantConditions")
                    @Override public void apply(K k, Object val) {
                        if (convert) {
                            V v = convert(val);

                            if (cctx.portableEnabled()) {
                                k = (K)cctx.marshalToPortable(k);
                                v = (V)cctx.marshalToPortable(v);
                            }

                            vis.apply(k, v);
                        }
                        else {
                            IgniteBiTuple<V, GridCacheVersion> v = (IgniteBiTuple<V, GridCacheVersion>)val;

                            if (v != null)
                                verVis.apply(k, v.get1(), v.get2());
                        }
                    }
                };

                if (keys.size() > singleThreadGate.loadAllThreshold()) {
                    Map<K, Object> map = store.loadAll(keys0);

                    if (map != null) {
                        for (Map.Entry<K, Object> e : map.entrySet())
                            c.apply(e.getKey(), e.getValue());
                    }
                }
                else
                    singleThreadGate.loadAll(keys0, c);
            }
            catch (ClassCastException e) {
                handleClassCastException(e);
            }
            catch (CacheLoaderException e) {
                throw new IgniteCheckedException(e);
            }
            catch (Exception e) {
                throw new IgniteCheckedException(new CacheLoaderException(e));
            }
            finally {
                if (ses)
                    sesHolder.set(null);
            }

            if (log.isDebugEnabled())
                log.debug("Loaded values from store for keys: " + keys0);
        }
    }

    /**
     * Loads data from persistent store.
     *
     * @param vis Closer to cache loaded elements.
     * @param args User arguments.
     * @return {@code True} if there is a persistent storage.
     * @throws IgniteCheckedException If data loading failed.
     */
    @SuppressWarnings({"ErrorNotRethrown", "unchecked"})
    public boolean loadCache(final GridInClosure3<K, V, GridCacheVersion> vis, Object[] args)
        throws IgniteCheckedException {
        if (store != null) {
            if (log.isDebugEnabled())
                log.debug("Loading all values from store.");

            boolean ses = initSession(null);

            try {
                store.loadCache(new IgniteBiInClosure<K, Object>() {
                    @Override public void apply(K k, Object o) {
                        V v;
                        GridCacheVersion ver = null;

                        if (locStore) {
                            IgniteBiTuple<V, GridCacheVersion> t = (IgniteBiTuple<V, GridCacheVersion>)o;

                            v = t.get1();
                            ver = t.get2();
                        }
                        else
                            v = (V)o;

                        vis.apply(k, v, ver);
                    }
                }, args);
            }
            catch (CacheLoaderException e) {
                throw new IgniteCheckedException(e);
            }
            catch (Exception e) {
                throw new IgniteCheckedException(new CacheLoaderException(e));
            }
            finally {
                if (ses)
                    sesHolder.set(null);
            }

            if (log.isDebugEnabled())
                log.debug("Loaded all values from store.");

            return true;
        }

        LT.warn(log, null, "Calling Cache.loadCache() method will have no effect, " +
            "CacheConfiguration.getStore() is not defined for cache: " + cctx.namexx());

        return false;
    }

    /**
     * Puts key-value pair into storage.
     *
     * @param tx Cache transaction.
     * @param key Key.
     * @param val Value.
     * @param ver Version.
     * @return {@code true} If there is a persistent storage.
     * @throws IgniteCheckedException If storage failed.
     */
    @SuppressWarnings("unchecked")
    public boolean putToStore(@Nullable IgniteInternalTx tx, K key, V val, GridCacheVersion ver)
        throws IgniteCheckedException {
        if (store != null) {
            // Never persist internal keys.
            if (key instanceof GridCacheInternal)
                return true;

            if (convertPortable) {
                key = (K)cctx.unwrapPortableIfNeeded(key, false);
                val = (V)cctx.unwrapPortableIfNeeded(val, false);
            }

            if (log.isDebugEnabled())
                log.debug("Storing value in cache store [key=" + key + ", val=" + val + ']');

            boolean ses = initSession(tx);

            try {
                store.write(new CacheEntryImpl<>(key, locStore ? F.t(val, ver) : val));
            }
            catch (ClassCastException e) {
                handleClassCastException(e);
            }
            catch (CacheWriterException e) {
                throw new IgniteCheckedException(e);
            }
            catch (Exception e) {
                throw new IgniteCheckedException(new CacheWriterException(e));
            }
            finally {
                if (ses)
                    sesHolder.set(null);
            }

            if (log.isDebugEnabled())
                log.debug("Stored value in cache store [key=" + key + ", val=" + val + ']');

            return true;
        }

        return false;
    }

    /**
     * Puts key-value pair into storage.
     *
     * @param tx Cache transaction.
     * @param map Map.
     * @return {@code True} if there is a persistent storage.
     * @throws IgniteCheckedException If storage failed.
     */
    public boolean putAllToStore(@Nullable IgniteInternalTx tx, Map<K, IgniteBiTuple<V, GridCacheVersion>> map)
        throws IgniteCheckedException {
        if (F.isEmpty(map))
            return true;

        if (map.size() == 1) {
            Map.Entry<K, IgniteBiTuple<V, GridCacheVersion>> e = map.entrySet().iterator().next();

            return putToStore(tx, e.getKey(), e.getValue().get1(), e.getValue().get2());
        }
        else {
            if (store != null) {
                EntriesView entries = new EntriesView(map);

                if (log.isDebugEnabled())
                    log.debug("Storing values in cache store [entries=" + entries + ']');

                boolean ses = initSession(tx);

                try {
                    store.writeAll(entries);
                }
                catch (ClassCastException e) {
                    handleClassCastException(e);
                }
                catch (Exception e) {
                    if (!entries.isEmpty()) {
                        List<Object> keys = new ArrayList<>(entries.size());

                        for (Cache.Entry<?, ?> entry : entries)
                            keys.add(entry.getKey());

                        throw new CacheStorePartialUpdateException(keys, e);
                    }

                    if (!(e instanceof CacheWriterException))
                        e = new CacheWriterException(e);

                    throw new IgniteCheckedException(e);
                }
                finally {
                    if (ses)
                        sesHolder.set(null);
                }

                if (log.isDebugEnabled())
                    log.debug("Stored value in cache store [entries=" + entries + ']');

                return true;
            }

            return false;
        }
    }

    /**
     * @param tx Cache transaction.
     * @param key Key.
     * @return {@code True} if there is a persistent storage.
     * @throws IgniteCheckedException If storage failed.
     */
    @SuppressWarnings("unchecked")
    public boolean removeFromStore(@Nullable IgniteInternalTx tx, K key) throws IgniteCheckedException {
        if (store != null) {
            // Never remove internal key from store as it is never persisted.
            if (key instanceof GridCacheInternal)
                return false;

            if (convertPortable)
                key = (K)cctx.unwrapPortableIfNeeded(key, false);

            if (log.isDebugEnabled())
                log.debug("Removing value from cache store [key=" + key + ']');

            boolean ses = initSession(tx);

            try {
                store.delete(key);
            }
            catch (ClassCastException e) {
                handleClassCastException(e);
            }
            catch (CacheWriterException e) {
                throw new IgniteCheckedException(e);
            }
            catch (Exception e) {
                throw new IgniteCheckedException(new CacheWriterException(e));
            }
            finally {
                if (ses)
                    sesHolder.set(null);
            }

            if (log.isDebugEnabled())
                log.debug("Removed value from cache store [key=" + key + ']');

            return true;
        }

        return false;
    }

    /**
     * @param tx Cache transaction.
     * @param keys Key.
     * @return {@code True} if there is a persistent storage.
     * @throws IgniteCheckedException If storage failed.
     */
    @SuppressWarnings("unchecked")
    public boolean removeAllFromStore(@Nullable IgniteInternalTx tx, Collection<?> keys) throws IgniteCheckedException {
        if (F.isEmpty(keys))
            return true;

        if (keys.size() == 1) {
            Object key = keys.iterator().next();

            return removeFromStore(tx, (K)key);
        }

        if (store != null) {
            Collection<Object> keys0 = convertPortable ?
                cctx.unwrapPortablesIfNeeded((Collection<Object>)keys, false) : (Collection<Object>)keys;

            if (log.isDebugEnabled())
                log.debug("Removing values from cache store [keys=" + keys0 + ']');

            boolean ses = initSession(tx);

            try {
                store.deleteAll(keys0);
            }
            catch (ClassCastException e) {
                handleClassCastException(e);
            }
            catch (Exception e) {
                if (!keys0.isEmpty())
                    throw new CacheStorePartialUpdateException(keys0, e);

                if (!(e instanceof CacheWriterException))
                    e = new CacheWriterException(e);

                throw new IgniteCheckedException(e);
            }
            finally {
                if (ses)
                    sesHolder.set(null);
            }

            if (log.isDebugEnabled())
                log.debug("Removed values from cache store [keys=" + keys0 + ']');

            return true;
        }

        return false;
    }

    /**
     * @return Store.
     */
    public CacheStore<K, Object> store() {
        return store;
    }

    /**
     * @throws IgniteCheckedException If failed.
     */
    public void forceFlush() throws IgniteCheckedException {
        if (store instanceof GridCacheWriteBehindStore)
            ((GridCacheWriteBehindStore)store).forceFlush();
    }

    /**
     * @param tx Transaction.
     * @param commit Commit.
     * @throws IgniteCheckedException If failed.
     */
    public void txEnd(IgniteInternalTx tx, boolean commit) throws IgniteCheckedException {
        assert store != null;

        boolean ses = initSession(tx);

        try {
            store.txEnd(commit);
        }
        finally {
            if (ses) {
                sesHolder.set(null);

                tx.removeMeta(SES_ATTR);
            }
        }
    }

    /**
     * @param e Class cast exception.
     * @throws IgniteCheckedException Thrown exception.
     */
    private void handleClassCastException(ClassCastException e) throws IgniteCheckedException {
        assert e != null;

        if (cctx.portableEnabled() && e.getMessage() != null) {
            throw new IgniteCheckedException("Cache store must work with portable objects if portables are " +
                "enabled for cache [cacheName=" + cctx.namex() + ']', e);
        }
        else
            throw e;
    }

    /**
     * @param tx Current transaction.
     * @return {@code True} if session was initialized.
     */
    private boolean initSession(@Nullable IgniteInternalTx<?, ?> tx) {
        if (!sesEnabled)
            return false;

        SessionData ses;

        if (tx != null) {
            ses = tx.meta(SES_ATTR);

            if (ses == null) {
                ses = new SessionData(tx, cctx.name());

                tx.addMeta(SES_ATTR, ses);
            }
            else
                // Session cache name may change in cross-cache transaction.
                ses.cacheName(cctx.name());
        }
        else
            ses = new SessionData(null, cctx.name());

        sesHolder.set(ses);

        return true;
    }

    /**
     *
     */
    private static class SessionData {
        /** */
        @GridToStringExclude
        private final IgniteInternalTx tx;

        /** */
        private String cacheName;

        /** */
        @GridToStringInclude
        private Map<Object, Object> props;

        /**
         * @param tx Current transaction.
         * @param cacheName Cache name.
         */
        private SessionData(@Nullable IgniteInternalTx tx, @Nullable String cacheName) {
            this.tx = tx;
            this.cacheName = cacheName;
        }

        /**
         * @return Transaction.
         */
        @Nullable private Transaction transaction() {
            return tx != null ? tx.proxy() : null;
        }

        /**
         * @return Properties.
         */
        private Map<Object, Object> properties() {
            if (props == null)
                props = new GridLeanMap<>();

            return props;
        }

        /**
         * @return Cache name.
         */
        private String cacheName() {
            return cacheName;
        }

        /**
         * @param cacheName Cache name.
         */
        private void cacheName(String cacheName) {
            this.cacheName = cacheName;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(SessionData.class, this, "tx", CU.txString(tx));
        }
    }

    /**
     *
     */
    private static class ThreadLocalSession implements CacheStoreSession {
        /** */
        private final ThreadLocal<SessionData> sesHolder;

        /**
         * @param sesHolder Session holder.
         */
        private ThreadLocalSession(ThreadLocal<SessionData> sesHolder) {
            this.sesHolder = sesHolder;
        }

        /** {@inheritDoc} */
        @Nullable @Override public Transaction transaction() {
            SessionData ses0 = sesHolder.get();

            return ses0 != null ? ses0.transaction() : null;
        }

        /** {@inheritDoc} */
        @SuppressWarnings("unchecked")
        @Override public <K1, V1> Map<K1, V1> properties() {
            SessionData ses0 = sesHolder.get();

            return ses0 != null ? (Map<K1, V1>)ses0.properties() : null;
        }

        /** {@inheritDoc} */
        @Nullable @Override public String cacheName() {
            SessionData ses0 = sesHolder.get();

            return ses0 != null ? ses0.cacheName() : null;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(ThreadLocalSession.class, this);
        }
    }

    /**
     *
     */
    @SuppressWarnings("unchecked")
    private class EntriesView extends AbstractCollection<Cache.Entry<? extends K, ?>> {
        /** */
        private final Map<K, IgniteBiTuple<V, GridCacheVersion>> map;

        /** */
        private Set<K> rmvd;

        /** */
        private boolean cleared;

        /**
         * @param map Map.
         */
        private EntriesView(Map<K, IgniteBiTuple<V, GridCacheVersion>> map) {
            assert map != null;

            this.map = map;
        }

        /** {@inheritDoc} */
        @Override public int size() {
            return cleared ? 0 : (map.size() - (rmvd != null ? rmvd.size() : 0));
        }

        /** {@inheritDoc} */
        @Override public boolean isEmpty() {
            return cleared || !iterator().hasNext();
        }

        /** {@inheritDoc} */
        @Override public boolean contains(Object o) {
            if (cleared || !(o instanceof Cache.Entry))
                return false;

            Cache.Entry<? extends K, ?> e = (Cache.Entry<? extends K, ?>)o;

            return map.containsKey(e.getKey());
        }

        /** {@inheritDoc} */
        @NotNull @Override public Iterator<Cache.Entry<? extends K, ?>> iterator() {
            if (cleared)
                return F.emptyIterator();

            final Iterator<Map.Entry<K, IgniteBiTuple<V, GridCacheVersion>>> it0 = map.entrySet().iterator();

            return new Iterator<Cache.Entry<? extends K, ?>>() {
                /** */
                private Cache.Entry<? extends K, ?> cur;

                /** */
                private Cache.Entry<? extends K, ?> next;

                /**
                 *
                 */
                {
                    checkNext();
                }

                /**
                 *
                 */
                private void checkNext() {
                    while (it0.hasNext()) {
                        Map.Entry<K, IgniteBiTuple<V, GridCacheVersion>> e = it0.next();

                        K k = e.getKey();

                        if (rmvd != null && rmvd.contains(k))
                            continue;

                        Object v = locStore ? e.getValue() : e.getValue().get1();

                        if (convertPortable) {
                            k = (K)cctx.unwrapPortableIfNeeded(k, false);
                            v = cctx.unwrapPortableIfNeeded(v, false);
                        }

                        next = new CacheEntryImpl<>(k, v);

                        break;
                    }
                }

                @Override public boolean hasNext() {
                    return next != null;
                }

                @Override public Cache.Entry<? extends K, ?> next() {
                    if (next == null)
                        throw new NoSuchElementException();

                    cur = next;

                    next = null;

                    checkNext();

                    return cur;
                }

                @Override public void remove() {
                    if (cur == null)
                        throw new IllegalStateException();

                    addRemoved(cur);

                    cur = null;
                }
            };
        }

        /** {@inheritDoc} */
        @Override public boolean add(Cache.Entry<? extends K, ?> entry) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public boolean addAll(Collection<? extends Cache.Entry<? extends K, ?>> col) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public boolean remove(Object o) {
            if (cleared || !(o instanceof Cache.Entry))
                return false;

            Cache.Entry<? extends K, ?> e = (Cache.Entry<? extends K, ?>)o;

            if (rmvd != null && rmvd.contains(e.getKey()))
                return false;

            if (mapContains(e)) {
                addRemoved(e);

                return true;
            }

            return false;
        }

        /** {@inheritDoc} */
        @Override public boolean containsAll(Collection<?> col) {
            if (cleared)
                return false;

            for (Object o : col) {
                if (contains(o))
                    return false;
            }

            return true;
        }

        /** {@inheritDoc} */
        @Override public boolean removeAll(Collection<?> col) {
            if (cleared)
                return false;

            boolean modified = false;

            for (Object o : col) {
                 if (remove(o))
                     modified = true;
            }

            return modified;
        }

        /** {@inheritDoc} */
        @Override public boolean retainAll(Collection<?> col) {
            if (cleared)
                return false;

            boolean modified = false;

            for (Cache.Entry<? extends K, ?> e : this) {
                if (!col.contains(e)) {
                    addRemoved(e);

                    modified = true;
                }
            }

            return modified;
        }

        /** {@inheritDoc} */
        @Override public void clear() {
            cleared = true;
        }

        /**
         * @param e Entry.
         */
        private void addRemoved(Cache.Entry<? extends K, ?> e) {
            if (rmvd == null)
                rmvd = new HashSet<>();

            rmvd.add(e.getKey());
        }

        /**
         * @param e Entry.
         * @return {@code True} if original map contains entry.
         */
        private boolean mapContains(Cache.Entry<? extends K, ?> e) {
            K key = (K)(convertPortable ? cctx.marshalToPortable(e.getKey()) : e.getKey());

            return map.containsKey(key);

        }

        /** {@inheritDoc} */
        public String toString() {
            Iterator<Cache.Entry<? extends K, ?>> it = iterator();

            if (!it.hasNext())
                return "[]";

            SB sb = new SB("[");

            while (true) {
                Cache.Entry<? extends K, ?> e = it.next();

                sb.a(e.toString());

                if (!it.hasNext())
                    return sb.a(']').toString();

                sb.a(", ");
            }
        }
    }
}
