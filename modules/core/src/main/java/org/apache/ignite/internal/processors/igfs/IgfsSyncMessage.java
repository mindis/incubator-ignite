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

package org.apache.ignite.internal.processors.igfs;

import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.plugin.extensions.communication.*;

import java.io.*;
import java.nio.*;

/**
 * Basic sync message.
 */
public class IgfsSyncMessage extends IgfsCommunicationMessage {
    /** */
    private static final long serialVersionUID = 0L;

    /** Coordinator node order. */
    private long order;

    /** Response flag. */
    private boolean res;

    /**
     * Empty constructor required by {@link Externalizable}.
     */
    public IgfsSyncMessage() {
        // No-op.
    }

    /**
     * @param order Node order.
     * @param res Response flag.
     */
    public IgfsSyncMessage(long order, boolean res) {
        this.order = order;
        this.res = res;
    }

    /**
     * @return Coordinator node order.
     */
    public long order() {
        return order;
    }

    /**
     * @return {@code True} if response message.
     */
    public boolean response() {
        return res;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(IgfsSyncMessage.class, this);
    }

    /** {@inheritDoc} */
    @Override public boolean writeTo(ByteBuffer buf, MessageWriter writer) {
        writer.setBuffer(buf);

        if (!super.writeTo(buf, writer))
            return false;

        if (!writer.isTypeWritten()) {
            if (!writer.writeByte(null, directType()))
                return false;

            writer.onTypeWritten();
        }

        switch (writer.state()) {
            case 0:
                if (!writer.writeLong("order", order))
                    return false;

                writer.incrementState();

            case 1:
                if (!writer.writeBoolean("res", res))
                    return false;

                writer.incrementState();

        }

        return true;
    }

    /** {@inheritDoc} */
    @Override public boolean readFrom(ByteBuffer buf) {
        reader.setBuffer(buf);

        if (!super.readFrom(buf))
            return false;

        switch (readState) {
            case 0:
                order = reader.readLong("order");

                if (!reader.isLastRead())
                    return false;

                readState++;

            case 1:
                res = reader.readBoolean("res");

                if (!reader.isLastRead())
                    return false;

                readState++;

        }

        return true;
    }

    /** {@inheritDoc} */
    @Override public byte directType() {
        return 71;
    }
}
