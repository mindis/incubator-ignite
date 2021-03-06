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

package org.apache.ignite.internal.processors.dr;

import org.apache.ignite.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.processors.cache.dr.*;
import org.apache.ignite.internal.processors.cache.version.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;

import java.util.*;

/**
 * Data center replication cache updater for data loader.
 */
public class GridDrDataLoadCacheUpdater<K, V> implements IgniteDataLoader.Updater<K, V> {
    /** */
    private static final long serialVersionUID = 0L;

    /** {@inheritDoc} */
    @Override public void update(IgniteCache<K, V> cache0, Collection<Map.Entry<K, V>> col) {
        try {
            String cacheName = cache0.getConfiguration(CacheConfiguration.class).getName();

            GridKernalContext ctx = ((IgniteKernal)cache0.unwrap(Ignite.class)).context();
            IgniteLogger log = ctx.log(GridDrDataLoadCacheUpdater.class);
            GridCacheAdapter<K, V> cache = ctx.cache().internalCache(cacheName);

            assert !F.isEmpty(col);

            if (log.isDebugEnabled())
                log.debug("Running DR put job [nodeId=" + ctx.localNodeId() + ", cacheName=" + cacheName + ']');

            IgniteInternalFuture<?> f = cache.context().preloader().startFuture();

            if (!f.isDone())
                f.get();

            for (Map.Entry<K, V> entry0 : col) {
                GridCacheRawVersionedEntry<K, V> entry = (GridCacheRawVersionedEntry<K, V>)entry0;

                entry.unmarshal(ctx.config().getMarshaller());

                K key = entry.key();

                GridCacheDrInfo<V> val = entry.value() != null ? entry.expireTime() != 0 ?
                    new GridCacheDrExpirationInfo<>(entry.value(), entry.version(), entry.ttl(), entry.expireTime()) :
                    new GridCacheDrInfo<>(entry.value(), entry.version()) : null;

                if (val == null)
                    cache.removeAllDr(Collections.singletonMap(key, entry.version()));
                else
                    cache.putAllDr(Collections.singletonMap(key, val));
            }

            if (log.isDebugEnabled())
                log.debug("DR put job finished [nodeId=" + ctx.localNodeId() + ", cacheName=" + cacheName + ']');
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }
}
