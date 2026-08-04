/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.ai.approval;

import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.BroadcasterListenerAdapter;
import org.atmosphere.cpr.Deliver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Predicate;

/**
 * Relays a human's approve/deny decision to whichever node is actually parking
 * the run — "approve on any node".
 *
 * <h2>The problem</h2>
 *
 * <p>A pending approval is a {@code CompletableFuture} on some node's heap
 * ({@link ApprovalRegistry}), and the only way to finish it is to call
 * {@link ApprovalRegistry#resolve} on <em>that</em> node's registry. Behind a
 * load balancer the reviewer's answer routinely lands somewhere else: the
 * receiving node finds no such id, and before this relay existed it logged a
 * warning and dropped the message, so the run sat parked until it timed out.
 * That forced sticky sessions on any deployment using HITL.</p>
 *
 * <h2>The mechanism</h2>
 *
 * <p>The receiving node re-broadcasts the approval message on a dedicated
 * cluster channel; every node tries it against its own registries, and the one
 * that owns the id completes the future. This is correct by construction rather
 * than by routing, because {@link ApprovalRegistry#resolve} already returns a
 * tri-state in which {@link ApprovalRegistry.ResolveResult#UNKNOWN_ID} is a
 * side-effect-free no-op — a node that does not own the approval simply does
 * nothing, and a duplicate delivery finds the entry already removed.</p>
 *
 * <p>The transport is Atmosphere's existing cluster plumbing
 * ({@code ClusterBroadcastFilter} / {@code RedisBroadcaster} /
 * {@code KafkaBroadcaster}), so this adds no new infrastructure. Inbound
 * cluster messages reach {@code BroadcasterListener.onMessage} even on a node
 * with no subscriber attached to the channel — the notification fires before
 * {@code DefaultBroadcaster}'s empty-resource early return — which is what lets
 * a headless application node act on an approval answered elsewhere.</p>
 *
 * <h2>Scope and safety</h2>
 *
 * <ul>
 *   <li><b>Opt-in, and single-node behaviour is unchanged.</b> Nothing is
 *       installed unless {@link #install} is called, and with no cluster
 *       broadcaster configured the broadcast stays node-local, so the relay
 *       degrades to exactly today's behaviour rather than failing.</li>
 *   <li><b>No re-publish on the inbound path.</b> A relayed message is only
 *       ever resolved locally, never re-broadcast, so two nodes cannot bounce
 *       an unknown id between them.</li>
 *   <li><b>Trusted channel.</b> An approval message can carry reviewer-edited
 *       tool arguments, so it rides the operator's existing cluster bus and
 *       adds no new externally-reachable surface. Malformed payloads keep
 *       {@link ApprovalRegistry}'s fail-closed deny (Invariant #6).</li>
 * </ul>
 */
public final class ClusterApprovalRelay {

    private static final Logger logger = LoggerFactory.getLogger(ClusterApprovalRelay.class);

    /**
     * Dedicated broadcaster id. Prefixed like the approval wire protocol so it
     * cannot collide with an application's own channel names.
     */
    public static final String CHANNEL = "/__atmosphere/approvals";

    private static volatile BroadcasterFactory factory;
    private static volatile Listener listener;
    private static volatile Predicate<String> resolver;

    private ClusterApprovalRelay() {
    }

    /**
     * Install the relay against a broadcaster factory.
     *
     * <p>Idempotent: installing twice replaces the previous registration rather
     * than stacking listeners, so a re-init cannot double-resolve a message.</p>
     *
     * @param broadcasterFactory the factory to look the relay channel up in
     * @param localResolver      attempts a node-local resolve; returns
     *                           {@code true} iff this node owned the approval.
     *                           Injected rather than referenced directly so the
     *                           relay stays testable without a live session map
     * @return {@code true} when the relay is active
     */
    public static synchronized boolean install(BroadcasterFactory broadcasterFactory,
                                               Predicate<String> localResolver) {
        if (broadcasterFactory == null || localResolver == null) {
            return false;
        }
        uninstall();
        var broadcaster = broadcasterFactory.lookup(CHANNEL, true);
        if (broadcaster == null) {
            logger.warn("Cluster approval relay not installed: no broadcaster for {}", CHANNEL);
            return false;
        }
        var installed = new Listener();
        broadcaster.addBroadcasterListener(installed);
        factory = broadcasterFactory;
        listener = installed;
        resolver = localResolver;
        logger.info("Cluster approval relay installed on {} — approvals may be answered "
                + "on any node", CHANNEL);
        return true;
    }

    /**
     * Remove the listener registered by {@link #install}.
     *
     * <p>Registration must have a symmetric removal (Correctness Invariant #1):
     * without this, a redeployed context would leave a listener bound to a
     * stale resolver.</p>
     */
    public static synchronized void uninstall() {
        var currentFactory = factory;
        var currentListener = listener;
        if (currentFactory != null && currentListener != null) {
            var broadcaster = currentFactory.lookup(CHANNEL, false);
            if (broadcaster != null) {
                broadcaster.removeBroadcasterListener(currentListener);
            }
        }
        factory = null;
        listener = null;
        resolver = null;
    }

    /** Whether the relay is currently installed. */
    public static boolean isInstalled() {
        return listener != null;
    }

    /**
     * Publish an approval message the local node could not resolve, so a node
     * that owns it can.
     *
     * <p>Call only after a local resolve has already failed — publishing an
     * approval this node owns would be a pointless round trip.</p>
     *
     * @param message the approval wire message
     * @return {@code true} when the message was handed to the cluster channel
     */
    public static boolean publish(String message) {
        var currentFactory = factory;
        if (currentFactory == null || !ApprovalRegistry.isApprovalMessage(message)) {
            return false;
        }
        var broadcaster = currentFactory.lookup(CHANNEL, false);
        if (broadcaster == null) {
            return false;
        }
        broadcaster.broadcast(message);
        logger.debug("Relayed unresolved approval to the cluster on {}", CHANNEL);
        return true;
    }

    /**
     * Fires for every message pushed through the relay channel, including ones
     * a cluster broadcaster received from a peer.
     */
    private static final class Listener extends BroadcasterListenerAdapter {

        @Override
        public void onMessage(Broadcaster b, Deliver deliver) {
            var currentResolver = resolver;
            if (currentResolver == null || deliver == null) {
                return;
            }
            if (!(deliver.getMessage() instanceof String message)
                    || !ApprovalRegistry.isApprovalMessage(message)) {
                return;
            }
            // Resolve locally only. Never re-publish: an id no node owns would
            // otherwise bounce around the cluster forever.
            if (currentResolver.test(message)) {
                logger.debug("Relayed approval resolved on this node");
            }
        }
    }
}
