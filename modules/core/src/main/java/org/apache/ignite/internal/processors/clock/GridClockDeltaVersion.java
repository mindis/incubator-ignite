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

package org.apache.ignite.internal.processors.clock;

import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.plugin.extensions.communication.*;

import java.io.*;
import java.nio.*;

/**
 * Version for time delta snapshot.
 */
public class GridClockDeltaVersion extends MessageAdapter implements Comparable<GridClockDeltaVersion>, Externalizable {
    /** */
    private static final long serialVersionUID = 0L;

    /** Snapshot local version. */
    private long ver;

    /** Topology version. */
    private long topVer;

    /**
     * Empty constructor required by {@link Externalizable}.
     */
    public GridClockDeltaVersion() {
        // No-op.
    }

    /**
     * @param ver Version.
     * @param topVer Topology version.
     */
    public GridClockDeltaVersion(long ver, long topVer) {
        this.ver = ver;
        this.topVer = topVer;
    }

    /**
     * @return Snapshot local version.
     */
    public long version() {
        return ver;
    }

    /**
     * @return Snapshot topology version.
     */
    public long topologyVersion() {
        return topVer;
    }

    /** {@inheritDoc} */
    @Override public int compareTo(GridClockDeltaVersion o) {
        if (topVer == o.topVer) {
            if (ver == o.ver)
                return 0;

            return ver > o.ver ? 1 : -1;
        }

        return topVer > o.topVer ? 1 : -1;
    }

    /** {@inheritDoc} */
    @Override public boolean equals(Object o) {
        if (this == o)
            return true;

        if (!(o instanceof GridClockDeltaVersion))
            return false;

        GridClockDeltaVersion that = (GridClockDeltaVersion)o;

        return topVer == that.topVer && ver == that.ver;
    }

    /** {@inheritDoc} */
    @Override public int hashCode() {
        int res = (int)(ver ^ (ver >>> 32));

        res = 31 * res + (int)(topVer ^ (topVer >>> 32));

        return res;
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        out.writeLong(ver);
        out.writeLong(topVer);
    }

    /** {@inheritDoc} */
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        ver = in.readLong();
        topVer = in.readLong();
    }

    /** {@inheritDoc} */
    @Override public boolean writeTo(ByteBuffer buf, MessageWriter writer) {
        writer.setBuffer(buf);

        if (!writer.isTypeWritten()) {
            if (!writer.writeByte(null, directType()))
                return false;

            writer.onTypeWritten();
        }

        switch (writer.state()) {
            case 0:
                if (!writer.writeLong("topVer", topVer))
                    return false;

                writer.incrementState();

            case 1:
                if (!writer.writeLong("ver", ver))
                    return false;

                writer.incrementState();

        }

        return true;
    }

    /** {@inheritDoc} */
    @Override public boolean readFrom(ByteBuffer buf) {
        reader.setBuffer(buf);

        switch (readState) {
            case 0:
                topVer = reader.readLong("topVer");

                if (!reader.isLastRead())
                    return false;

                readState++;

            case 1:
                ver = reader.readLong("ver");

                if (!reader.isLastRead())
                    return false;

                readState++;

        }

        return true;
    }

    /** {@inheritDoc} */
    @Override public byte directType() {
        return 83;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridClockDeltaVersion.class, this);
    }
}
