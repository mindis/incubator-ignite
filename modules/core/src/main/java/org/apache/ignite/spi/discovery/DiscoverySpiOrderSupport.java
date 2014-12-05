/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.apache.ignite.spi.discovery;

import java.lang.annotation.*;

/**
 * This annotation is for all implementations of {@link DiscoverySpi} that support
 * proper node ordering. This includes:
 * <ul>
 * <li>
 * Every node gets an order number assigned to it which is provided via {@link org.apache.ignite.cluster.ClusterNode#order()}
 * method. There is no requirement about order value other than that nodes that join grid
 * at later point of time have order values greater than previous nodes.
 * </li>
 * <li>
 * All {@link org.apache.ignite.events.IgniteEventType#EVT_NODE_JOINED} events come in proper order. This means that all
 * listeners to discovery events will receive discovery notifications in proper order.
 * </li>
 * </ul>
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface DiscoverySpiOrderSupport {
    /**
     * Whether or not target SPI supports node startup order.
     */
    @SuppressWarnings({"JavaDoc"})
    public boolean value();
}