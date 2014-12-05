/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.apache.ignite.logger.log4j;

import org.apache.ignite.*;
import org.apache.ignite.lang.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;

import java.io.*;
import java.util.*;

/**
 * Closure that generates file path adding node id to filename as a suffix.
 */
class IgniteLog4jNodeIdFilePath implements IgniteClosure<String, String> {
    /** */
    private static final long serialVersionUID = 0L;

    /** Node id. */
    private final UUID nodeId;

    /**
     * Creates new instance.
     *
     * @param id Node id.
     */
    IgniteLog4jNodeIdFilePath(UUID id) {
        nodeId = id;
    }

    /** {@inheritDoc} */
    @Override public String apply(String oldPath) {
        if (!F.isEmpty(U.GRIDGAIN_LOG_DIR))
            return U.nodeIdLogFileName(nodeId, new File(U.GRIDGAIN_LOG_DIR, "gridgain.log").getAbsolutePath());

        if (oldPath != null) // fileName could be null if GRIDGAIN_HOME is not defined.
            return U.nodeIdLogFileName(nodeId, oldPath);

        String tmpDir = IgniteSystemProperties.getString("java.io.tmpdir");

        if (tmpDir != null)
            return U.nodeIdLogFileName(nodeId, new File(tmpDir, "gridgain.log").getAbsolutePath());

        System.err.println("Failed to get tmp directory for log file.");

        return null;
    }
}