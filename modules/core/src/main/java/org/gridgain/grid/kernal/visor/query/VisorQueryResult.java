/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.visor.query;

import org.gridgain.grid.util.typedef.internal.*;

import java.io.*;
import java.util.*;

/**
 * Result for cache query tasks.
 */
public class VisorQueryResult implements Serializable {
    /** */
    private static final long serialVersionUID = 0L;

    /** Rows fetched from query. */
    private final List<Object[]> rows;

    /** Whether query has more rows to fetch. */
    private final Boolean hasMore;

    /** Query duration */
    private final long duration;

    /**
     * Create task result with given parameters
     *
     * @param rows Rows fetched from query.
     * @param hasMore Whether query has more rows to fetch.
     * @param duration Query duration.
     */
    public VisorQueryResult(List<Object[]> rows, Boolean hasMore, long duration) {
        this.rows = rows;
        this.hasMore = hasMore;
        this.duration = duration;
    }

    /**
     * @return Rows fetched from query.
     */
    public List<Object[]> rows() {
        return rows;
    }

    /**
     * @return Whether query has more rows to fetch.
     */
    public Boolean hasMore() {
        return hasMore;
    }

    /**
     * @return Duration of next page fetching.
     */
    public long duration() {
        return duration;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(VisorQueryResult.class, this);
    }
}