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

import org.apache.ignite.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.marshaller.*;
import org.apache.ignite.plugin.extensions.communication.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.nio.*;

/**
 * Indicates that entry scheduled for delete was actually deleted.
 */
public class IgfsDeleteMessage extends IgfsCommunicationMessage {
    /** */
    private static final long serialVersionUID = 0L;

    /** Deleted entry ID. */
    private IgniteUuid id;

    /** Optional error. */
    @GridDirectTransient
    private IgniteCheckedException err;

    /** */
    private byte[] errBytes;

    /**
     * {@link Externalizable} support.
     */
    public IgfsDeleteMessage() {
        // No-op.
    }

    /**
     * Constructor.
     *
     * @param id Deleted entry ID.
     */
    public IgfsDeleteMessage(IgniteUuid id) {
        assert id != null;

        this.id = id;
    }

    /**
     * Constructor.
     *
     * @param id Entry ID.
     * @param err Error.
     */
    public IgfsDeleteMessage(IgniteUuid id, IgniteCheckedException err) {
        assert err != null;

        this.id = id;
        this.err = err;
    }

    /**
     * @return Deleted entry ID.
     */
    public IgniteUuid id() {
        return id;
    }

    /**
     * @return Error.
     */
    public IgniteCheckedException error() {
        return err;
    }

    /** {@inheritDoc} */
    @Override public void prepareMarshal(Marshaller marsh) throws IgniteCheckedException {
        super.prepareMarshal(marsh);

        if (err != null)
            errBytes = marsh.marshal(err);
    }

    /** {@inheritDoc} */
    @Override public void finishUnmarshal(Marshaller marsh, @Nullable ClassLoader ldr) throws IgniteCheckedException {
        super.finishUnmarshal(marsh, ldr);

        if (errBytes != null)
            err = marsh.unmarshal(errBytes, ldr);
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"CloneDoesntCallSuperClone", "CloneCallsConstructors"})
    @Override public MessageAdapter clone() {
        IgfsDeleteMessage _clone = new IgfsDeleteMessage();

        clone0(_clone);

        return _clone;
    }

    /** {@inheritDoc} */
    @Override protected void clone0(MessageAdapter _msg) {
        super.clone0(_msg);

        IgfsDeleteMessage _clone = (IgfsDeleteMessage)_msg;

        _clone.id = id;
        _clone.err = err;
        _clone.errBytes = errBytes;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("all")
    @Override public boolean writeTo(ByteBuffer buf) {
        writer.setBuffer(buf);

        if (!super.writeTo(buf))
            return false;

        if (!typeWritten) {
            if (!writer.writeByte(null, directType()))
                return false;

            typeWritten = true;
        }

        switch (state) {
            case 0:
                if (!writer.writeByteArray("errBytes", errBytes))
                    return false;

                state++;

            case 1:
                if (!writer.writeIgniteUuid("id", id))
                    return false;

                state++;

        }

        return true;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("all")
    @Override public boolean readFrom(ByteBuffer buf) {
        reader.setBuffer(buf);

        if (!super.readFrom(buf))
            return false;

        switch (state) {
            case 0:
                errBytes = reader.readByteArray("errBytes");

                if (!reader.isLastRead())
                    return false;

                state++;

            case 1:
                id = reader.readIgniteUuid("id");

                if (!reader.isLastRead())
                    return false;

                state++;

        }

        return true;
    }

    /** {@inheritDoc} */
    @Override public byte directType() {
        return 67;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(IgfsDeleteMessage.class, this);
    }
}