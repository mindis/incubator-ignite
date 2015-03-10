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

package org.apache.ignite.internal.processors.query;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.query.*;
import org.apache.ignite.cache.query.annotations.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.events.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.processors.cache.query.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.future.*;
import org.apache.ignite.internal.util.lang.*;
import org.apache.ignite.internal.util.tostring.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.internal.util.worker.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.spi.*;
import org.apache.ignite.spi.indexing.*;
import org.jdk8.backport.*;
import org.jetbrains.annotations.*;

import javax.cache.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

import static org.apache.ignite.events.EventType.EVT_CACHE_QUERY_EXECUTED;
import static org.apache.ignite.internal.IgniteComponentType.*;
import static org.apache.ignite.internal.processors.query.GridQueryIndexType.*;

/**
 * Indexing processor.
 */
public class GridQueryProcessor extends GridProcessorAdapter {
    /** For tests. */
    public static Class<? extends GridQueryIndexing> idxCls;

    /** */
    private final GridSpinBusyLock busyLock = new GridSpinBusyLock();

    /** Type descriptors. */
    private final ConcurrentMap<TypeId, TypeDescriptor> types = new ConcurrentHashMap8<>();

    /** Type descriptors. */
    private final ConcurrentMap<TypeName, TypeDescriptor> typesByName = new ConcurrentHashMap8<>();

    /** */
    private ExecutorService execSvc;

    /** */
    private final GridQueryIndexing idx;

    /** Configuration-declared types. */
    private final Map<TypeName, CacheTypeMetadata> declaredTypesByName = new HashMap<>();

    /** Configuration-declared types. */
    private Map<TypeId, CacheTypeMetadata> declaredTypesById;

    /** Portable IDs. */
    private Map<Integer, String> portableIds;

    /** Type resolvers per space name. */
    private Map<String,QueryTypeResolver> typeResolvers = new HashMap<>();

    /**
     * @param ctx Kernal context.
     */
    public GridQueryProcessor(GridKernalContext ctx) throws IgniteCheckedException {
        super(ctx);

        if (idxCls != null) {
            idx = U.newInstance(idxCls);

            idxCls = null;
        }
        else
            idx = INDEXING.inClassPath() ? U.<GridQueryIndexing>newInstance(INDEXING.className()) : null;
    }

    /** {@inheritDoc} */
    @Override public void start() throws IgniteCheckedException {
        super.start();

        if (idx != null) {
            ctx.resource().injectGeneric(idx);

            idx.start(ctx);

            for (CacheConfiguration<?, ?> ccfg : ctx.config().getCacheConfiguration()){
                CacheQueryConfiguration qryCfg = ccfg.getQueryConfiguration();

                if (qryCfg != null) {
                    if (!F.isEmpty(ccfg.getTypeMetadata())) {
                        for (CacheTypeMetadata meta : ccfg.getTypeMetadata())
                            declaredTypesByName.put(new TypeName(ccfg.getName(), meta.getValueType()), meta);
                    }

                    if (qryCfg.getTypeResolver() != null)
                        typeResolvers.put(ccfg.getName(), qryCfg.getTypeResolver());
                }
            }

            execSvc = ctx.getExecutorService();
        }
    }

    /** {@inheritDoc} */
    @Override public void onKernalStop(boolean cancel) {
        super.onKernalStop(cancel);

        busyLock.block();
    }

    /** {@inheritDoc} */
    @Override public void stop(boolean cancel) throws IgniteCheckedException {
        super.stop(cancel);

        if (idx != null)
            idx.stop();
    }

    /**
     * Returns number of objects of given type for given space of spi.
     *
     * @param space Space.
     * @param valType Value type.
     * @return Objects number or -1 if this type is unknown for given SPI and space.
     * @throws IgniteCheckedException If failed.
     */
    public long size(@Nullable String space, Class<?> valType) throws IgniteCheckedException {
        checkEnabled();

        if (!busyLock.enterBusy())
            throw new IllegalStateException("Failed to get space size (grid is stopping).");

        try {
            TypeDescriptor desc = types.get(new TypeId(space, valType));

            if (desc == null || !desc.registered())
                return -1;

            return idx.size(space, desc, null);
        }
        finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Rebuilds all search indexes of given value type for given space of spi.
     *
     * @param space Space.
     * @param valTypeName Value type name.
     * @return Future that will be completed when rebuilding of all indexes is finished.
     */
    public IgniteInternalFuture<?> rebuildIndexes(@Nullable final String space, String valTypeName) {
        if (!busyLock.enterBusy())
            throw new IllegalStateException("Failed to rebuild indexes (grid is stopping).");

        try {
            return rebuildIndexes(space, typesByName.get(new TypeName(space, valTypeName)));
        }
        finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * @param space Space.
     * @param desc Type descriptor.
     * @return Future that will be completed when rebuilding of all indexes is finished.
     */
    private IgniteInternalFuture<?> rebuildIndexes(@Nullable final String space, @Nullable final TypeDescriptor desc) {
        if (idx == null)
            return new GridFinishedFuture<>(new IgniteCheckedException("Indexing is disabled."));

        if (desc == null || !desc.registered())
            return new GridFinishedFuture<Void>();

        final GridWorkerFuture<?> fut = new GridWorkerFuture<Void>();

        GridWorker w = new GridWorker(ctx.gridName(), "index-rebuild-worker", log) {
            @Override protected void body() {
                try {
                    idx.rebuildIndexes(space, desc);

                    fut.onDone();
                }
                catch (Exception e) {
                    fut.onDone(e);
                }
                catch (Throwable e) {
                    log.error("Failed to rebuild indexes for type: " + desc.name(), e);

                    fut.onDone(e);
                }
            }
        };

        fut.setWorker(w);

        execSvc.execute(w);

        return fut;
    }

    /**
     * Rebuilds all search indexes for given spi.
     *
     * @return Future that will be completed when rebuilding of all indexes is finished.
     */
    @SuppressWarnings("unchecked")
    public IgniteInternalFuture<?> rebuildAllIndexes() {
        if (!busyLock.enterBusy())
            throw new IllegalStateException("Failed to get space size (grid is stopping).");

        try {
            GridCompoundFuture<?, ?> fut = new GridCompoundFuture<Object, Object>();

            for (Map.Entry<TypeId, TypeDescriptor> e : types.entrySet())
                fut.add((IgniteInternalFuture)rebuildIndexes(e.getKey().space, e.getValue()));

            fut.markInitialized();

            return fut;
        }
        finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Writes key-value pair to index.
     *
     * @param space Space.
     * @param key Key.
     * @param keyBytes Byte array with key data.
     * @param val Value.
     * @param valBytes Byte array with value data.
     * @param ver Cache entry version.
     * @param expirationTime Expiration time or 0 if never expires.
     * @throws IgniteCheckedException In case of error.
     */
    @SuppressWarnings("unchecked")
    public <K, V> void store(final String space, final K key, @Nullable byte[] keyBytes, final V val,
        @Nullable byte[] valBytes, byte[] ver, long expirationTime) throws IgniteCheckedException {
        assert key != null;
        assert val != null;

        ctx.indexing().store(space, key, val, expirationTime);

        if (idx == null)
            return;

        if (!busyLock.enterBusy())
            throw new IllegalStateException("Failed to write to index (grid is stopping).");

        try {
            if (log.isDebugEnabled())
                log.debug("Storing key to cache query index [key=" + key + ", value=" + val + "]");

            final Class<?> valCls = val.getClass();
            final Class<?> keyCls = key.getClass();

            TypeId id = null;

            QueryTypeResolver rslvr = typeResolvers.get(space);

            if (rslvr != null) {
                String typeName = rslvr.resolveTypeName(key, val);

                if (typeName != null)
                    id = new TypeId(space, ctx.portable().typeId(typeName));
            }

            if (id == null) {
                if (ctx.portable().isPortableObject(val)) {
                    int typeId = ctx.portable().typeId(val);

                    String typeName = portableName(typeId);

                    if (typeName == null)
                        return;

                    id = new TypeId(space, typeId);
                }
                else
                    id = new TypeId(space, valCls);
            }

            TypeDescriptor desc = types.get(id);

            if (desc == null) {
                desc = new TypeDescriptor();

                TypeDescriptor existing = types.putIfAbsent(id, desc);

                if (existing != null)
                    desc = existing;
            }

            if (!desc.succeeded()) {
                final TypeDescriptor d = desc;

                d.init(new Callable<Void>() {
                    @Override public Void call() throws Exception {
                        d.keyClass(keyCls);
                        d.valueClass(valCls);

                        if (ctx.portable().isPortableObject(key)) {
                            int typeId = ctx.portable().typeId(key);

                            String typeName = portableName(typeId);

                            if (typeName != null) {
                                CacheTypeMetadata keyMeta = declaredType(space, typeId);

                                if (keyMeta != null)
                                    processPortableMeta(true, keyMeta, d);
                            }
                        }
                        else {
                            CacheTypeMetadata keyMeta = declaredType(space, keyCls.getName());

                            if (keyMeta == null)
                                processAnnotationsInClass(true, d.keyCls, d, null);
                            else
                                processClassMeta(true, d.keyCls, keyMeta, d);
                        }

                        if (ctx.portable().isPortableObject(val)) {
                            int typeId = ctx.portable().typeId(val);

                            String typeName = portableName(typeId);

                            if (typeName != null) {
                                CacheTypeMetadata valMeta = declaredType(space, typeId);

                                d.name(typeName);

                                if (valMeta != null)
                                    processPortableMeta(false, valMeta, d);
                            }
                        }
                        else {
                            String valTypeName = typeName(valCls);

                            d.name(valTypeName);

                            CacheTypeMetadata typeMeta = declaredType(space, valCls.getName());

                            if (typeMeta == null)
                                processAnnotationsInClass(false, d.valCls, d, null);
                            else
                                processClassMeta(false, d.valCls, typeMeta, d);
                        }

                        d.registered(idx.registerType(space, d));

                        typesByName.put(new TypeName(space, d.name()), d);

                        return null;
                    }
                });
            }

            if (!desc.registered())
                return;

            if (!desc.valueClass().equals(valCls))
                throw new IgniteCheckedException("Failed to update index due to class name conflict" +
                    "(multiple classes with same simple name are stored in the same cache) " +
                    "[expCls=" + desc.valueClass().getName() + ", actualCls=" + valCls.getName() + ']');

            idx.store(space, desc, key, val, ver, expirationTime);
        }
        finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * @throws IgniteCheckedException If failed.
     */
    private void checkEnabled() throws IgniteCheckedException {
        if (idx == null)
            throw new IgniteCheckedException("Indexing is disabled.");
    }

    /**
     * @param space Space.
     * @param clause Clause.
     * @param params Parameters collection.
     * @param resType Result type.
     * @param filters Filters.
     * @return Key/value rows.
     * @throws IgniteCheckedException If failed.
     */
    @SuppressWarnings("unchecked")
    public <K, V> GridCloseableIterator<IgniteBiTuple<K, V>> query(String space, String clause,
        Collection<Object> params, String resType, IndexingQueryFilter filters)
        throws IgniteCheckedException {
        checkEnabled();

        if (!busyLock.enterBusy())
            throw new IllegalStateException("Failed to execute query (grid is stopping).");

        try {
            TypeDescriptor type = typesByName.get(new TypeName(space, resType));

            if (type == null || !type.registered())
                return new GridEmptyCloseableIterator<>();

            return idx.query(space, clause, params, type, filters);
        }
        finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * @param space Space name.
     * @param qry Query.
     * @return Future.
     */
    public IgniteInternalFuture<GridCacheSqlResult> queryTwoStep(String space, GridCacheTwoStepQuery qry) {
        if (!busyLock.enterBusy())
            throw new IllegalStateException("Failed to execute query (grid is stopping).");

        try {
            return idx.queryTwoStep(space, qry);
        }
        finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * @param space Space.
     * @param sqlQry Query.
     * @param params Parameters.
     * @return Result.
     */
    public IgniteInternalFuture<GridCacheSqlResult> queryTwoStep(String space, String sqlQry, Object[] params) {
        if (!busyLock.enterBusy())
            throw new IllegalStateException("Failed to execute query (grid is stopping).");

        try {
            return idx.queryTwoStep(space, sqlQry, params);
        }
        finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * @param space Space.
     * @param type Type.
     * @param sqlQry Query.
     * @param params Parameters.
     * @return Cursor.
     */
    public <K,V> Iterator<Cache.Entry<K,V>> queryLocal(String space, String type, String sqlQry, Object[] params) {
        if (!busyLock.enterBusy())
            throw new IllegalStateException("Failed to execute query (grid is stopping).");

        try {
            TypeDescriptor typeDesc = typesByName.get(new TypeName(space, type));

            if (typeDesc == null || !typeDesc.registered())
                return new GridEmptyCloseableIterator<>();

            final GridCloseableIterator<IgniteBiTuple<K,V>> i = idx.query(space, sqlQry, F.asList(params), typeDesc,
                idx.backupFilter());

            if (ctx.event().isRecordable(EVT_CACHE_QUERY_EXECUTED)) {
                ctx.event().record(new CacheQueryExecutedEvent<>(
                    ctx.discovery().localNode(),
                    "SQL query executed.",
                    EVT_CACHE_QUERY_EXECUTED,
                    CacheQueryType.SQL,
                    null,
                    null,
                    sqlQry,
                    null,
                    null,
                    params,
                    null,
                    null));
            }

            return new ClIter<Cache.Entry<K,V>>() {
                @Override public void close() throws Exception {
                    i.close();
                }

                @Override public boolean hasNext() {
                    return i.hasNext();
                }

                @Override public Cache.Entry<K,V> next() {
                    IgniteBiTuple<K,V> t = i.next();

                    return new CacheEntryImpl<>(t.getKey(), t.getValue());
                }

                @Override public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException(e);
        }
        finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Closeable iterator.
     */
    private static interface ClIter<X> extends AutoCloseable, Iterator<X> {
        // No-op.
    }

    /**
     * @param space Space.
     * @param sql SQL Query.
     * @param args Arguments.
     * @return Iterator.
     */
    public Iterator<List<?>> queryLocalFields(String space, String sql, Object[] args) {
        if (!busyLock.enterBusy())
            throw new IllegalStateException("Failed to execute query (grid is stopping).");

        try {
            IgniteSpiCloseableIterator<List<?>> iterator =
                idx.queryFields(space, sql, F.asList(args), idx.backupFilter()).iterator();

            if (ctx.event().isRecordable(EVT_CACHE_QUERY_EXECUTED)) {
                ctx.event().record(new CacheQueryExecutedEvent<>(
                    ctx.discovery().localNode(),
                    "SQL query executed.",
                    EVT_CACHE_QUERY_EXECUTED,
                    CacheQueryType.SQL,
                    null,
                    null,
                    sql,
                    null,
                    null,
                    args,
                    null,
                    null));
            }

            return iterator;
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException(e);
        }
        finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * @param space Space.
     * @param key Key.
     * @throws IgniteCheckedException Thrown in case of any errors.
     */
    @SuppressWarnings("unchecked")
    public void remove(String space, Object key) throws IgniteCheckedException {
        assert key != null;

        ctx.indexing().remove(space, key);

        if (idx == null)
            return;

        if (!busyLock.enterBusy())
            throw new IllegalStateException("Failed to remove from index (grid is stopping).");

        try {
            idx.remove(space, key);
        }
        finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Gets type name by class.
     *
     * @param cls Class.
     * @return Type name.
     */
    public static String typeName(Class<?> cls) {
        String typeName = cls.getSimpleName();

        // To protect from failure on anonymous classes.
        if (F.isEmpty(typeName)) {
            String pkg = cls.getPackage().getName();

            typeName = cls.getName().substring(pkg.length() + (pkg.isEmpty() ? 0 : 1));
        }

        if (cls.isArray()) {
            assert typeName.endsWith("[]");

            typeName = typeName.substring(0, typeName.length() - 2) + "_array";
        }

        return typeName;
    }

    /**
     * Gets portable type name by portable ID.
     *
     * @param typeId Type ID.
     * @return Name.
     */
    private String portableName(int typeId) {
        Map<Integer, String> portableIds = this.portableIds;

        if (portableIds == null) {
            portableIds = new HashMap<>();

            for (CacheConfiguration<?, ?> ccfg : ctx.config().getCacheConfiguration()){
                CacheQueryConfiguration qryCfg = ccfg.getQueryConfiguration();

                if (qryCfg != null && ccfg.getTypeMetadata() != null) {
                    for (CacheTypeMetadata meta : ccfg.getTypeMetadata())
                        portableIds.put(ctx.portable().typeId(meta.getValueType()), meta.getValueType());
                }
            }

            this.portableIds = portableIds;
        }

        return portableIds.get(typeId);
    }

    /**
     * @param space Space name.
     * @param typeId Type ID.
     * @return Type meta data if it was declared in configuration.
     */
    @Nullable private CacheTypeMetadata declaredType(String space, int typeId) {
        Map<TypeId, CacheTypeMetadata> declaredTypesById = this.declaredTypesById;

        if (declaredTypesById == null) {
            declaredTypesById = new HashMap<>();

            for (CacheConfiguration<?, ?> ccfg : ctx.config().getCacheConfiguration()){
                CacheQueryConfiguration qryCfg = ccfg.getQueryConfiguration();

                if (qryCfg != null && ccfg.getTypeMetadata() != null) {
                    for (CacheTypeMetadata meta : ccfg.getTypeMetadata())
                        declaredTypesById.put(new TypeId(ccfg.getName(), ctx.portable().typeId(meta.getValueType())), meta);
                }
            }

            this.declaredTypesById = declaredTypesById;
        }

        return declaredTypesById.get(new TypeId(space, typeId));
    }

    /**
     * @param space Space name.
     * @param typeName Type name.
     * @return Type meta data if it was declared in configuration.
     */
    @Nullable private CacheTypeMetadata declaredType(String space, String typeName) {
        return declaredTypesByName.get(new TypeName(space, typeName));
    }

    /**
     * @param space Space.
     * @param clause Clause.
     * @param resType Result type.
     * @param filters Key and value filters.
     * @param <K> Key type.
     * @param <V> Value type.
     * @return Key/value rows.
     * @throws IgniteCheckedException If failed.
     */
    @SuppressWarnings("unchecked")
    public <K, V> GridCloseableIterator<IgniteBiTuple<K, V>> queryText(String space, String clause, String resType,
        IndexingQueryFilter filters) throws IgniteCheckedException {
        checkEnabled();

        if (!busyLock.enterBusy())
            throw new IllegalStateException("Failed to execute query (grid is stopping).");

        try {
            TypeDescriptor type = typesByName.get(new TypeName(space, resType));

            if (type == null || !type.registered())
                return new GridEmptyCloseableIterator<>();

            return idx.queryText(space, clause, type, filters);
        }
        finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * @param space Space name.
     * @param clause Clause.
     * @param params Parameters collection.
     * @param filters Key and value filters.
     * @return Field rows.
     * @throws IgniteCheckedException If failed.
     */
    public <K, V> GridQueryFieldsResult queryFields(@Nullable String space, String clause, Collection<Object> params,
        IndexingQueryFilter filters) throws IgniteCheckedException {
        checkEnabled();

        if (!busyLock.enterBusy())
            throw new IllegalStateException("Failed to execute query (grid is stopping).");

        try {
            return idx.queryFields(space, clause, params, filters);
        }
        finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Will be called when entry for key will be swapped.
     *
     * @param spaceName Space name.
     * @param key key.
     * @throws IgniteCheckedException If failed.
     */
    public void onSwap(String spaceName, Object key) throws IgniteCheckedException {
        ctx.indexing().onSwap(spaceName, key);

        if (idx == null)
            return;

        if (!busyLock.enterBusy())
            throw new IllegalStateException("Failed to process swap event (grid is stopping).");

        try {
            idx.onSwap(spaceName, key);
        }
        finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Will be called when entry for key will be unswapped.
     *
     * @param spaceName Space name.
     * @param key Key.
     * @param val Value.
     * @param valBytes Value bytes.
     * @throws IgniteCheckedException If failed.
     */
    public void onUnswap(String spaceName, Object key, Object val, byte[] valBytes)
        throws IgniteCheckedException {
        ctx.indexing().onUnswap(spaceName, key, val);

        if (idx == null)
            return;

        if (!busyLock.enterBusy())
            throw new IllegalStateException("Failed to process swap event (grid is stopping).");

        try {
            idx.onUnswap(spaceName, key, val, valBytes);
        }
        finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Removes index tables for all classes belonging to given class loader.
     *
     * @param space Space name.
     * @param ldr Class loader to undeploy.
     * @throws IgniteCheckedException If undeploy failed.
     */
    public void onUndeploy(@Nullable String space, ClassLoader ldr) throws IgniteCheckedException {
        if (idx == null)
            return;

        if (!busyLock.enterBusy())
            throw new IllegalStateException("Failed to process undeploy event (grid is stopping).");

        try {
            Iterator<Map.Entry<TypeId, TypeDescriptor>> it = types.entrySet().iterator();

            while (it.hasNext()) {
                Map.Entry<TypeId, TypeDescriptor> e = it.next();

                if (!F.eq(e.getKey().space, space))
                    continue;

                TypeDescriptor desc = e.getValue();

                if (ldr.equals(U.detectClassLoader(desc.valCls)) || ldr.equals(U.detectClassLoader(desc.keyCls))) {
                    idx.unregisterType(e.getKey().space, desc);

                    it.remove();
                }
            }
        }
        finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Process annotations for class.
     *
     * @param key If given class relates to key.
     * @param cls Class.
     * @param type Type descriptor.
     * @param parent Parent in case of embeddable.
     * @throws IgniteCheckedException In case of error.
     */
    static void processAnnotationsInClass(boolean key, Class<?> cls, TypeDescriptor type,
        @Nullable ClassProperty parent) throws IgniteCheckedException {
        if (U.isJdk(cls))
            return;

        if (parent != null && parent.knowsClass(cls))
            throw new IgniteCheckedException("Recursive reference found in type: " + cls.getName());

        if (parent == null) { // Check class annotation at top level only.
            QueryTextField txtAnnCls = cls.getAnnotation(QueryTextField.class);

            if (txtAnnCls != null)
                type.valueTextIndex(true);

            QueryGroupIndex grpIdx = cls.getAnnotation(QueryGroupIndex.class);

            if (grpIdx != null)
                type.addIndex(grpIdx.name(), SORTED);

            QueryGroupIndex.List grpIdxList = cls.getAnnotation(QueryGroupIndex.List.class);

            if (grpIdxList != null && !F.isEmpty(grpIdxList.value())) {
                for (QueryGroupIndex idx : grpIdxList.value())
                    type.addIndex(idx.name(), SORTED);
            }
        }

        for (Class<?> c = cls; c != null && !c.equals(Object.class); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                QuerySqlField sqlAnn = field.getAnnotation(QuerySqlField.class);
                QueryTextField txtAnn = field.getAnnotation(QueryTextField.class);

                if (sqlAnn != null || txtAnn != null) {
                    ClassProperty prop = new ClassProperty(field);

                    prop.parent(parent);

                    processAnnotation(key, sqlAnn, txtAnn, field.getType(), prop, type);

                    type.addProperty(key, prop, true);
                }
            }

            for (Method mtd : c.getDeclaredMethods()) {
                QuerySqlField sqlAnn = mtd.getAnnotation(QuerySqlField.class);
                QueryTextField txtAnn = mtd.getAnnotation(QueryTextField.class);

                if (sqlAnn != null || txtAnn != null) {
                    if (mtd.getParameterTypes().length != 0)
                        throw new IgniteCheckedException("Getter with QuerySqlField " +
                            "annotation cannot have parameters: " + mtd);

                    ClassProperty prop = new ClassProperty(mtd);

                    prop.parent(parent);

                    processAnnotation(key, sqlAnn, txtAnn, mtd.getReturnType(), prop, type);

                    type.addProperty(key, prop, true);
                }
            }
        }
    }

    /**
     * Processes annotation at field or method.
     *
     * @param key If given class relates to key.
     * @param sqlAnn SQL annotation, can be {@code null}.
     * @param txtAnn H2 text annotation, can be {@code null}.
     * @param cls Class of field or return type for method.
     * @param prop Current property.
     * @param desc Class description.
     * @throws IgniteCheckedException In case of error.
     */
    static void processAnnotation(boolean key, QuerySqlField sqlAnn, QueryTextField txtAnn,
        Class<?> cls, ClassProperty prop, TypeDescriptor desc) throws IgniteCheckedException {
        if (sqlAnn != null) {
            processAnnotationsInClass(key, cls, desc, prop);

            if (!sqlAnn.name().isEmpty())
                prop.name(sqlAnn.name());

            if (sqlAnn.index()) {
                String idxName = prop.name() + "_idx";

                desc.addIndex(idxName, isGeometryClass(prop.type()) ? GEO_SPATIAL : SORTED);

                desc.addFieldToIndex(idxName, prop.name(), 0, sqlAnn.descending());
            }

            if (!F.isEmpty(sqlAnn.groups())) {
                for (String group : sqlAnn.groups())
                    desc.addFieldToIndex(group, prop.name(), 0, false);
            }

            if (!F.isEmpty(sqlAnn.orderedGroups())) {
                for (QuerySqlField.Group idx : sqlAnn.orderedGroups())
                    desc.addFieldToIndex(idx.name(), prop.name(), idx.order(), idx.descending());
            }
        }

        if (txtAnn != null)
            desc.addFieldToTextIndex(prop.name());
    }

    /**
     * Processes declarative metadata for class.
     *
     * @param key Key or value flag.
     * @param cls Class to process.
     * @param meta Type metadata.
     * @param d Type descriptor.
     * @throws IgniteCheckedException If failed.
     */
    static void processClassMeta(boolean key, Class<?> cls, CacheTypeMetadata meta, TypeDescriptor d)
        throws IgniteCheckedException {
        for (Map.Entry<String, Class<?>> entry : meta.getAscendingFields().entrySet()) {
            ClassProperty prop = buildClassProperty(cls, entry.getKey(), entry.getValue());

            d.addProperty(key, prop, false);

            String idxName = prop.name() + "_idx";

            d.addIndex(idxName, isGeometryClass(prop.type()) ? GEO_SPATIAL : SORTED);

            d.addFieldToIndex(idxName, prop.name(), 0, false);
        }

        for (Map.Entry<String, Class<?>> entry : meta.getDescendingFields().entrySet()) {
            ClassProperty prop = buildClassProperty(cls, entry.getKey(), entry.getValue());

            d.addProperty(key, prop, false);

            String idxName = prop.name() + "_idx";

            d.addIndex(idxName, isGeometryClass(prop.type()) ? GEO_SPATIAL : SORTED);

            d.addFieldToIndex(idxName, prop.name(), 0, true);
        }

        for (String txtIdx : meta.getTextFields()) {
            ClassProperty prop = buildClassProperty(cls, txtIdx, String.class);

            d.addProperty(key, prop, false);

            d.addFieldToTextIndex(prop.name());
        }

        Map<String, LinkedHashMap<String, IgniteBiTuple<Class<?>, Boolean>>> grps = meta.getGroups();

        if (grps != null) {
            for (Map.Entry<String, LinkedHashMap<String, IgniteBiTuple<Class<?>, Boolean>>> entry : grps.entrySet()) {
                String idxName = entry.getKey();

                LinkedHashMap<String, IgniteBiTuple<Class<?>, Boolean>> idxFields = entry.getValue();

                int order = 0;

                for (Map.Entry<String, IgniteBiTuple<Class<?>, Boolean>> idxField : idxFields.entrySet()) {
                    ClassProperty prop = buildClassProperty(cls, idxField.getKey(), idxField.getValue().get1());

                    d.addProperty(key, prop, false);

                    Boolean descending = idxField.getValue().get2();

                    d.addFieldToIndex(idxName, prop.name(), order, descending != null && descending);

                    order++;
                }
            }
        }

        for (Map.Entry<String, Class<?>> entry : meta.getQueryFields().entrySet()) {
            ClassProperty prop = buildClassProperty(cls, entry.getKey(), entry.getValue());

            d.addProperty(key, prop, false);
        }
    }

    /**
     * Processes declarative metadata for portable object.
     *
     * @param key Key or value flag.
     * @param meta Declared metadata.
     * @param d Type descriptor.
     * @throws IgniteCheckedException If failed.
     */
    private void processPortableMeta(boolean key, CacheTypeMetadata meta, TypeDescriptor d)
        throws IgniteCheckedException {
        for (Map.Entry<String, Class<?>> entry : meta.getAscendingFields().entrySet()) {
            PortableProperty prop = buildPortableProperty(entry.getKey(), entry.getValue());

            d.addProperty(key, prop, false);

            String idxName = prop.name() + "_idx";

            d.addIndex(idxName, isGeometryClass(prop.type()) ? GEO_SPATIAL : SORTED);

            d.addFieldToIndex(idxName, prop.name(), 0, false);
        }

        for (Map.Entry<String, Class<?>> entry : meta.getDescendingFields().entrySet()) {
            PortableProperty prop = buildPortableProperty(entry.getKey(), entry.getValue());

            d.addProperty(key, prop, false);

            String idxName = prop.name() + "_idx";

            d.addIndex(idxName, isGeometryClass(prop.type()) ? GEO_SPATIAL : SORTED);

            d.addFieldToIndex(idxName, prop.name(), 0, true);
        }

        for (String txtIdx : meta.getTextFields()) {
            PortableProperty prop = buildPortableProperty(txtIdx, String.class);

            d.addProperty(key, prop, false);

            d.addFieldToTextIndex(prop.name());
        }

        Map<String, LinkedHashMap<String, IgniteBiTuple<Class<?>, Boolean>>> grps = meta.getGroups();

        if (grps != null) {
            for (Map.Entry<String, LinkedHashMap<String, IgniteBiTuple<Class<?>, Boolean>>> entry : grps.entrySet()) {
                String idxName = entry.getKey();

                LinkedHashMap<String, IgniteBiTuple<Class<?>, Boolean>> idxFields = entry.getValue();

                int order = 0;

                for (Map.Entry<String, IgniteBiTuple<Class<?>, Boolean>> idxField : idxFields.entrySet()) {
                    PortableProperty prop = buildPortableProperty(idxField.getKey(), idxField.getValue().get1());

                    d.addProperty(key, prop, false);

                    Boolean descending = idxField.getValue().get2();

                    d.addFieldToIndex(idxName, prop.name(), order, descending != null && descending);

                    order++;
                }
            }
        }

        for (Map.Entry<String, Class<?>> entry : meta.getQueryFields().entrySet()) {
            PortableProperty prop = buildPortableProperty(entry.getKey(), entry.getValue());

            if (!d.props.containsKey(prop.name()))
                d.addProperty(key, prop, false);
        }
    }

    /**
     * Builds portable object property.
     *
     * @param pathStr String representing path to the property. May contains dots '.' to identify
     *      nested fields.
     * @param resType Result type.
     * @return Portable property.
     */
    private PortableProperty buildPortableProperty(String pathStr, Class<?> resType) {
        String[] path = pathStr.split("\\.");

        PortableProperty res = null;

        for (String prop : path)
            res = new PortableProperty(prop, res, resType);

        return res;
    }

    /**
     * @param cls Source type class.
     * @param pathStr String representing path to the property. May contains dots '.' to identify nested fields.
     * @param resType Expected result type.
     * @return Property instance corresponding to the given path.
     * @throws IgniteCheckedException If property cannot be created.
     */
    static ClassProperty buildClassProperty(Class<?> cls, String pathStr, Class<?> resType) throws IgniteCheckedException {
        String[] path = pathStr.split("\\.");

        ClassProperty res = null;

        for (String prop : path) {
            ClassProperty tmp;

            try {
                StringBuilder bld = new StringBuilder("get");

                bld.append(prop);

                bld.setCharAt(3, Character.toUpperCase(bld.charAt(3)));

                tmp = new ClassProperty(cls.getMethod(bld.toString()));
            }
            catch (NoSuchMethodException ignore) {
                try {
                    tmp = new ClassProperty(cls.getDeclaredField(prop));
                }
                catch (NoSuchFieldException ignored) {
                    throw new IgniteCheckedException("Failed to find getter method or field for property named " +
                        "'" + prop + "': " + cls.getName());
                }
            }

            tmp.parent(res);

            cls = tmp.type();

            res = tmp;
        }

        if (!U.box(resType).isAssignableFrom(U.box(res.type())))
            throw new IgniteCheckedException("Failed to create property for given path (actual property type is not assignable" +
                " to declared type [path=" + pathStr + ", actualType=" + res.type().getName() +
                ", declaredType=" + resType.getName() + ']');

        return res;
    }

    /**
     * Gets types for space.
     *
     * @param space Space name.
     * @return Descriptors.
     */
    public Collection<GridQueryTypeDescriptor> types(@Nullable String space) {
        Collection<GridQueryTypeDescriptor> spaceTypes = new ArrayList<>(
            Math.min(10, types.size()));

        for (Map.Entry<TypeId, TypeDescriptor> e : types.entrySet()) {
            TypeDescriptor desc = e.getValue();

            if (desc.registered() && F.eq(e.getKey().space, space))
                spaceTypes.add(desc);
        }

        return spaceTypes;
    }

    /**
     * Gets type for space and type name.
     *
     * @param space Space name.
     * @param typeName Type name.
     * @return Type.
     * @throws IgniteCheckedException If failed.
     */
    public GridQueryTypeDescriptor type(@Nullable String space, String typeName) throws IgniteCheckedException {
        TypeDescriptor type = typesByName.get(new TypeName(space, typeName));

        if (type == null || !type.registered())
            throw new IgniteCheckedException("Failed to find type descriptor for type name: " + typeName);

        return type;
    }

    /**
     * @param cls Field type.
     * @return {@code True} if given type is a spatial geometry type based on {@code com.vividsolutions.jts} library.
     * @throws IgniteCheckedException If failed.
     */
    private static boolean isGeometryClass(Class<?> cls) throws IgniteCheckedException { // TODO optimize
        Class<?> dataTypeCls;

        try {
            dataTypeCls = Class.forName("org.h2.value.DataType");
        }
        catch (ClassNotFoundException ignored) {
            return false; // H2 is not in classpath.
        }

        try {
            Method method = dataTypeCls.getMethod("isGeometryClass", Class.class);

            return (Boolean)method.invoke(null, cls);
        }
        catch (Exception e) {
            throw new IgniteCheckedException("Failed to invoke 'org.h2.value.DataType.isGeometryClass' method.", e);
        }
    }

    /**
     *
     */
    private abstract static class Property {
        /**
         * Gets this property value from the given object.
         *
         * @param x Object with this property.
         * @return Property value.
         * @throws IgniteCheckedException If failed.
         */
        public abstract Object value(Object x) throws IgniteCheckedException;

        /**
         * @return Property name.
         */
        public abstract String name();

        /**
         * @return Class member type.
         */
        public abstract Class<?> type();
    }

    /**
     * Description of type property.
     */
    private static class ClassProperty extends Property {
        /** */
        private final Member member;

        /** */
        private ClassProperty parent;

        /** */
        private String name;

        /** */
        private boolean field;

        /**
         * Constructor.
         *
         * @param member Element.
         */
        ClassProperty(Member member) {
            this.member = member;

            name = member instanceof Method && member.getName().startsWith("get") && member.getName().length() > 3 ?
                member.getName().substring(3) : member.getName();

            ((AccessibleObject) member).setAccessible(true);

            field = member instanceof Field;
        }

        /** {@inheritDoc} */
        @Override public Object value(Object x) throws IgniteCheckedException {
            if (parent != null)
                x = parent.value(x);

            if (x == null)
                return null;

            try {
                if (field) {
                    Field field = (Field)member;

                    return field.get(x);
                }
                else {
                    Method mtd = (Method)member;

                    return mtd.invoke(x);
                }
            }
            catch (Exception e) {
                throw new IgniteCheckedException(e);
            }
        }

        /**
         * @param name Property name.
         */
        public void name(String name) {
            this.name = name;
        }

        /** {@inheritDoc} */
        @Override public String name() {
            return name;
        }

        /** {@inheritDoc} */
        @Override public Class<?> type() {
            return member instanceof Field ? ((Field)member).getType() : ((Method)member).getReturnType();
        }

        /**
         * @param parent Parent property if this is embeddable element.
         */
        public void parent(ClassProperty parent) {
            this.parent = parent;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(ClassProperty.class, this);
        }

        /**
         * @param cls Class.
         * @return {@code true} If this property or some parent relates to member of the given class.
         */
        public boolean knowsClass(Class<?> cls) {
            return member.getDeclaringClass() == cls || (parent != null && parent.knowsClass(cls));
        }
    }

    /**
     *
     */
    private class PortableProperty extends Property {
        /** Property name. */
        private String propName;

        /** Parent property. */
        private PortableProperty parent;

        /** Result class. */
        private Class<?> type;

        /**
         * Constructor.
         *
         * @param propName Property name.
         * @param parent Parent property.
         * @param type Result type.
         */
        private PortableProperty(String propName, PortableProperty parent, Class<?> type) {
            this.propName = propName;
            this.parent = parent;
            this.type = type;
        }

        /** {@inheritDoc} */
        @Override public Object value(Object obj) throws IgniteCheckedException {
            if (parent != null)
                obj = parent.value(obj);

            if (obj == null)
                return null;

            if (!ctx.portable().isPortableObject(obj))
                throw new IgniteCheckedException("Non-portable object received as a result of property extraction " +
                    "[parent=" + parent + ", propName=" + propName + ", obj=" + obj + ']');

            return ctx.portable().field(obj, propName);
        }

        /** {@inheritDoc} */
        @Override public String name() {
            return propName;
        }

        /** {@inheritDoc} */
        @Override public Class<?> type() {
            return type;
        }
    }

    /**
     * Descriptor of type.
     */
    private static class TypeDescriptor implements GridQueryTypeDescriptor {
        /** */
        private String name;

        /** Value field names and types with preserved order. */
        @GridToStringInclude
        private final Map<String, Class<?>> valFields = new LinkedHashMap<>();

        /** */
        @GridToStringExclude
        private final Map<String, Property> props = new HashMap<>();

        /** Key field names and types with preserved order. */
        @GridToStringInclude
        private final Map<String, Class<?>> keyFields = new LinkedHashMap<>();

        /** */
        @GridToStringInclude
        private final Map<String, IndexDescriptor> indexes = new HashMap<>();

        /** */
        private IndexDescriptor fullTextIdx;

        /** */
        private Class<?> keyCls;

        /** */
        private Class<?> valCls;

        /** */
        private boolean valTextIdx;

        /** To ensure that type was registered in SPI and only once. */
        private final GridAtomicInitializer<Void> initializer = new GridAtomicInitializer<>();

        /** SPI can decide not to register this type. */
        private boolean registered;

        /**
         * @param c Initialization callable.
         * @throws IgniteCheckedException In case of error.
         */
        void init(Callable<Void> c) throws IgniteCheckedException {
            initializer.init(c);
        }

        /**
         * @return Waits for initialization.
         * @throws IgniteInterruptedCheckedException If thread is interrupted.
         */
        boolean await() throws IgniteInterruptedCheckedException {
            return initializer.await();
        }

        /**
         * @return Whether initialization was successfully completed.
         */
        boolean succeeded() {
            return initializer.succeeded();
        }

        /**
         * @return {@code True} if type registration in SPI was finished and type was not rejected.
         */
        boolean registered() {
            return initializer.succeeded() && registered;
        }

        /**
         * @param registered Sets registered flag.
         */
        void registered(boolean registered) {
            this.registered = registered;
        }

        /** {@inheritDoc} */
        @Override public String name() {
            return name;
        }

        /**
         * Sets type name.
         *
         * @param name Name.
         */
        void name(String name) {
            this.name = name;
        }

        /** {@inheritDoc} */
        @Override public Map<String, Class<?>> valueFields() {
            return valFields;
        }

        /** {@inheritDoc} */
        @Override public Map<String, Class<?>> keyFields() {
            return keyFields;
        }

        /** {@inheritDoc} */
        @Override public <T> T value(Object obj, String field) throws IgniteCheckedException {
            assert obj != null;
            assert field != null;

            Property prop = props.get(field);

            if (prop == null)
                throw new IgniteCheckedException("Failed to find field '" + field + "' in type '" + name + "'.");

            return (T)prop.value(obj);
        }

        /** {@inheritDoc} */
        @Override public Map<String, GridQueryIndexDescriptor> indexes() {
            return Collections.<String, GridQueryIndexDescriptor>unmodifiableMap(indexes);
        }

        /**
         * Adds index.
         *
         * @param idxName Index name.
         * @param type Index type.
         * @return Index descriptor.
         * @throws IgniteCheckedException In case of error.
         */
        public IndexDescriptor addIndex(String idxName, GridQueryIndexType type) throws IgniteCheckedException {
            IndexDescriptor idx = new IndexDescriptor(type);

            if (indexes.put(idxName, idx) != null)
                throw new IgniteCheckedException("Index with name '" + idxName + "' already exists.");

            return idx;
        }

        /**
         * Adds field to index.
         *
         * @param idxName Index name.
         * @param field Field name.
         * @param orderNum Fields order number in index.
         * @param descending Sorting order.
         * @throws IgniteCheckedException If failed.
         */
        public void addFieldToIndex(String idxName, String field, int orderNum,
            boolean descending) throws IgniteCheckedException {
            IndexDescriptor desc = indexes.get(idxName);

            if (desc == null)
                desc = addIndex(idxName, SORTED);

            desc.addField(field, orderNum, descending);
        }

        /**
         * Adds field to text index.
         *
         * @param field Field name.
         */
        public void addFieldToTextIndex(String field) {
            if (fullTextIdx == null) {
                fullTextIdx = new IndexDescriptor(FULLTEXT);

                indexes.put(null, fullTextIdx);
            }

            fullTextIdx.addField(field, 0, false);
        }

        /** {@inheritDoc} */
        @Override public Class<?> valueClass() {
            return valCls;
        }

        /**
         * Sets value class.
         *
         * @param valCls Value class.
         */
        void valueClass(Class<?> valCls) {
            this.valCls = valCls;
        }

        /** {@inheritDoc} */
        @Override public Class<?> keyClass() {
            return keyCls;
        }

        /**
         * Set key class.
         *
         * @param keyCls Key class.
         */
        void keyClass(Class<?> keyCls) {
            this.keyCls = keyCls;
        }

        /**
         * Adds property to the type descriptor.
         *
         * @param key If given property relates to key.
         * @param prop Property.
         * @param failOnDuplicate Fail on duplicate flag.
         * @throws IgniteCheckedException In case of error.
         */
        public void addProperty(boolean key, Property prop, boolean failOnDuplicate) throws IgniteCheckedException {
            String name = prop.name();

            if (props.put(name, prop) != null && failOnDuplicate)
                throw new IgniteCheckedException("Property with name '" + name + "' already exists.");

            if (key)
                keyFields.put(name, prop.type());
            else
                valFields.put(name, prop.type());
        }

        /** {@inheritDoc} */
        @Override public boolean valueTextIndex() {
            return valTextIdx;
        }

        /**
         * Sets if this value should be text indexed.
         *
         * @param valTextIdx Flag value.
         */
        public void valueTextIndex(boolean valTextIdx) {
            this.valTextIdx = valTextIdx;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(TypeDescriptor.class, this);
        }
    }

    /**
     * Index descriptor.
     */
    private static class IndexDescriptor implements GridQueryIndexDescriptor {
        /** Fields sorted by order number. */
        private final Collection<T2<String, Integer>> fields = new TreeSet<>(
            new Comparator<T2<String, Integer>>() {
                @Override public int compare(T2<String, Integer> o1, T2<String, Integer> o2) {
                    if (o1.get2().equals(o2.get2())) // Order is equal, compare field names to avoid replace in Set.
                        return o1.get1().compareTo(o2.get1());

                    return o1.get2() < o2.get2() ? -1 : 1;
                }
            });

        /** Fields which should be indexed in descending order. */
        private Collection<String> descendings;

        /** */
        private final GridQueryIndexType type;

        /**
         * @param type Type.
         */
        private IndexDescriptor(GridQueryIndexType type) {
            assert type != null;

            this.type = type;
        }

        /** {@inheritDoc} */
        @Override public Collection<String> fields() {
            Collection<String> res = new ArrayList<>(fields.size());

            for (T2<String, Integer> t : fields)
                res.add(t.get1());

            return res;
        }

        /** {@inheritDoc} */
        @Override public boolean descending(String field) {
            return descendings != null && descendings.contains(field);
        }

        /**
         * Adds field to this index.
         *
         * @param field Field name.
         * @param orderNum Field order number in this index.
         * @param descending Sort order.
         */
        public void addField(String field, int orderNum, boolean descending) {
            fields.add(new T2<>(field, orderNum));

            if (descending) {
                if (descendings == null)
                    descendings  = new HashSet<>();

                descendings.add(field);
            }
        }

        /** {@inheritDoc} */
        @Override public GridQueryIndexType type() {
            return type;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(IndexDescriptor.class, this);
        }
    }

    /**
     * Identifying TypeDescriptor by space and value class.
     */
    private static class TypeId {
        /** */
        private final String space;

        /** Value type. */
        private final Class<?> valType;

        /** Value type ID. */
        private final int valTypeId;

        /**
         * Constructor.
         *
         * @param space Space name.
         * @param valType Value type.
         */
        private TypeId(String space, Class<?> valType) {
            assert valType != null;

            this.space = space;
            this.valType = valType;

            valTypeId = 0;
        }

        /**
         * Constructor.
         *
         * @param space Space name.
         * @param valTypeId Value type ID.
         */
        private TypeId(String space, int valTypeId) {
            this.space = space;
            this.valTypeId = valTypeId;

            valType = null;
        }

        /** {@inheritDoc} */
        @Override public boolean equals(Object o) {
            if (this == o)
                return true;

            if (o == null || getClass() != o.getClass())
                return false;

            TypeId typeId = (TypeId)o;

            return (valTypeId == typeId.valTypeId) &&
                (valType != null ? valType == typeId.valType : typeId.valType == null) &&
                (space != null ? space.equals(typeId.space) : typeId.space == null);
        }

        /** {@inheritDoc} */
        @Override public int hashCode() {
            return 31 * (space != null ? space.hashCode() : 0) + (valType != null ? valType.hashCode() : valTypeId);
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(TypeId.class, this);
        }
    }

    /**
     *
     */
    private static class TypeName {
        /** */
        private final String space;

        /** */
        private final String typeName;

        /**
         * @param space Space name.
         * @param typeName Type name.
         */
        private TypeName(@Nullable String space, String typeName) {
            assert !F.isEmpty(typeName) : typeName;

            this.space = space;
            this.typeName = typeName;
        }

        /** {@inheritDoc} */
        @Override public boolean equals(Object o) {
            if (this == o)
                return true;

            if (o == null || getClass() != o.getClass())
                return false;

            TypeName other = (TypeName)o;

            return (space != null ? space.equals(other.space) : other.space == null) &&
                typeName.equals(other.typeName);
        }

        /** {@inheritDoc} */
        @Override public int hashCode() {
            return 31 * (space != null ? space.hashCode() : 0) + typeName.hashCode();
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(TypeName.class, this);
        }
    }
}
