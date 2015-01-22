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

package org.apache.ignite.examples.messaging;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.examples.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.resources.*;
import org.gridgain.examples.*;
import org.apache.ignite.internal.util.lang.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Demonstrates simple message exchange between local and remote nodes.
 * <p>
 * To run this example you must have at least one remote node started.
 * <p>
 * Remote nodes should always be started with special configuration file which
 * enables P2P class loading: {@code 'ggstart.{sh|bat} examples/config/example-compute.xml'}.
 * <p>
 * Alternatively you can run {@link ComputeNodeStartup} in another JVM which will start GridGain node
 * with {@code examples/config/example-compute.xml} configuration.
 */
public class MessagingPingPongExample {
    /**
     * Executes example.
     *
     * @param args Command line arguments, none required.
     * @throws IgniteCheckedException If example execution failed.
     */
    public static void main(String[] args) throws IgniteCheckedException {
        // Game is played over the default grid.
        try (Ignite g = Ignition.start("examples/config/example-compute.xml")) {
            if (!ExamplesUtils.checkMinTopologySize(g.cluster(), 2))
                return;

            System.out.println();
            System.out.println(">>> Messaging ping-pong example started.");

            // Pick random remote node as a partner.
            ClusterGroup nodeB = g.cluster().forRemotes().forRandom();

            // Note that both nodeA and nodeB will always point to
            // same nodes regardless of whether they were implicitly
            // serialized and deserialized on another node as part of
            // anonymous closure's state during its remote execution.

            // Set up remote player.
            g.message(nodeB).remoteListen(null, new IgniteBiPredicate<UUID, String>() {
                /** This will be injected on node listener comes to. */
                @IgniteInstanceResource
                private Ignite ignite;

                @Override public boolean apply(UUID nodeId, String rcvMsg) {
                    System.out.println("Received message [msg=" + rcvMsg + ", sender=" + nodeId + ']');

                    try {
                        if ("PING".equals(rcvMsg)) {
                            ignite.message(ignite.cluster().forNodeId(nodeId)).send(null, "PONG");

                            return true; // Continue listening.
                        }

                        return false; // Unsubscribe.
                    }
                    catch (IgniteCheckedException e) {
                        throw new GridClosureException(e);
                    }
                }
            });

            int MAX_PLAYS = 10;

            final CountDownLatch cnt = new CountDownLatch(MAX_PLAYS);

            // Set up local player.
            g.message().localListen(null, new IgniteBiPredicate<UUID, String>() {
                @Override public boolean apply(UUID nodeId, String rcvMsg) {
                    System.out.println("Received message [msg=" + rcvMsg + ", sender=" + nodeId + ']');

                    try {
                        if (cnt.getCount() == 1) {
                            g.message(g.cluster().forNodeId(nodeId)).send(null, "STOP");

                            cnt.countDown();

                            return false; // Stop listening.
                        }
                        else if ("PONG".equals(rcvMsg))
                            g.message(g.cluster().forNodeId(nodeId)).send(null, "PING");
                        else
                            throw new RuntimeException("Received unexpected message: " + rcvMsg);

                        cnt.countDown();

                        return true; // Continue listening.
                    }
                    catch (IgniteCheckedException e) {
                        throw new GridClosureException(e);
                    }
                }
            });

            // Serve!
            g.message(nodeB).send(null, "PING");

            // Wait til the game is over.
            try {
                cnt.await();
            }
            catch (InterruptedException e) {
                System.err.println("Hm... let us finish the game!\n" + e);
            }
        }
    }
}