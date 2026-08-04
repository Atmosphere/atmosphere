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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins "approve on any node".
 *
 * <p>A pending approval is a {@code CompletableFuture} on one node's heap, so
 * before the relay existed an answer that landed on a different node found no
 * such id and was dropped — the run stayed parked until timeout, which is why
 * HITL forced sticky sessions.</p>
 *
 * <p>Two {@link ApprovalRegistry} instances stand in for two nodes. They are
 * joined by a fake cluster bus rather than a live {@code RedisBroadcaster}
 * because what needs pinning is the routing contract — every node attempts the
 * message, the owner completes it, non-owners are side-effect-free — not
 * Redis's own wire, which its module already covers.</p>
 */
class ClusterApprovalRelayTest {

    /** Node A parks the run; node B receives the human's answer. */
    private final ApprovalRegistry nodeA = new ApprovalRegistry();
    private final ApprovalRegistry nodeB = new ApprovalRegistry();

    @AfterEach
    void tearDown() {
        ClusterApprovalRelay.uninstall();
    }

    private CompletableFuture<ApprovalResolution> park(ApprovalRegistry node, String id) {
        return node.registerForResolution(new PendingApproval(
                id, "delete_user", java.util.Map.of("id", "u-1"),
                "Approve deleting u-1?", "conv-1",
                Instant.now().plus(Duration.ofMinutes(5))));
    }

    /**
     * The fake bus: deliver to every node exactly as the relay's listener does —
     * try locally, never re-publish.
     */
    private boolean deliverToCluster(String message) {
        return nodeA.resolve(message) == ApprovalRegistry.ResolveResult.RESOLVED
                || nodeB.resolve(message) == ApprovalRegistry.ResolveResult.RESOLVED;
    }

    @Test
    void anApprovalAnsweredOnAnotherNodeUnparksTheOwningNode() throws Exception {
        var parked = park(nodeA, "apr-1");

        // The answer lands on node B, which does not own it.
        assertEquals(ApprovalRegistry.ResolveResult.UNKNOWN_ID,
                nodeB.resolve("/__approval/apr-1/approve"),
                "the receiving node must not claim an approval it does not hold");
        assertFalse(parked.isDone(), "a non-owning node must not complete the future");

        // Relayed across the cluster, the owner picks it up.
        assertTrue(deliverToCluster("/__approval/apr-1/approve"),
                "some node must own the approval");

        var resolution = parked.get(5, TimeUnit.SECONDS);
        assertTrue(resolution.approved(), "the relayed decision must be the one applied");
    }

    @Test
    void aDuplicateDeliveryIsANoOp() throws Exception {
        var parked = park(nodeA, "apr-2");

        assertTrue(deliverToCluster("/__approval/apr-2/approve"));
        assertTrue(parked.get(5, TimeUnit.SECONDS).approved());

        // At-least-once delivery is normal on a cluster bus; the second pass
        // finds the entry already removed rather than double-resolving.
        assertFalse(deliverToCluster("/__approval/apr-2/deny"),
                "a replayed message must not resolve a second time");
        assertTrue(parked.get(5, TimeUnit.SECONDS).approved(),
                "the original decision must stand");
    }

    @Test
    void anIdNoNodeOwnsIsDroppedRatherThanCirculated() {
        assertFalse(deliverToCluster("/__approval/nobody/approve"),
                "an unknown id must resolve nowhere");
        // Both registries stay clean — an UNKNOWN_ID must be side-effect-free,
        // which is what makes broadcast-to-all-nodes safe.
        assertEquals(ApprovalRegistry.ResolveResult.UNKNOWN_ID,
                nodeA.resolve("/__approval/nobody/approve"));
        assertEquals(ApprovalRegistry.ResolveResult.UNKNOWN_ID,
                nodeB.resolve("/__approval/nobody/approve"));
    }

    @Test
    void publishIsANoOpUntilTheRelayIsInstalled() {
        assertFalse(ClusterApprovalRelay.isInstalled(),
                "the relay must be opt-in so single-node deployments are unchanged");
        assertFalse(ClusterApprovalRelay.publish("/__approval/apr-3/approve"),
                "publishing without an installed relay must be a no-op, not a failure");
    }

    @Test
    void nonApprovalTrafficIsNeverRelayed() {
        var relayed = new AtomicInteger();
        assertFalse(ClusterApprovalRelay.publish("hello world"),
                "ordinary chat traffic must never reach the approval channel");
        assertFalse(ClusterApprovalRelay.publish(null));
        assertEquals(0, relayed.get());
    }

    // --- the relay itself, not a stand-in for it -------------------------

    /**
     * The tests above pin the routing contract against a hand-rolled bus, which
     * proves the contract but would stay green if {@link ClusterApprovalRelay}
     * did nothing at all. This one drives the real class: install it, publish
     * through it, and let its own listener deliver the message.
     */
    @Test
    void theRelayItselfDeliversAPublishedApprovalToTheOwningNode() throws Exception {
        var broadcaster = new CapturingBroadcaster();
        var parked = park(nodeA, "apr-relay");

        assertTrue(ClusterApprovalRelay.install(broadcaster.factory(),
                        message -> nodeA.resolve(message) == ApprovalRegistry.ResolveResult.RESOLVED),
                "install must report success when a broadcaster is available");
        assertTrue(ClusterApprovalRelay.isInstalled());

        assertTrue(ClusterApprovalRelay.publish("/__approval/apr-relay/approve"),
                "an approval must reach the cluster channel");
        broadcaster.deliverLast();

        assertTrue(parked.get(5, TimeUnit.SECONDS).approved(),
                "the relay's own listener must complete the parked future");
    }

    @Test
    void uninstallRemovesTheListenerSoAReinstallCannotDoubleResolve() {
        var broadcaster = new CapturingBroadcaster();
        ClusterApprovalRelay.install(broadcaster.factory(), message -> true);
        assertEquals(1, broadcaster.listeners.size(), "one listener after install");

        // Installing twice must replace, not stack — otherwise one message
        // would be resolved once per stale registration.
        ClusterApprovalRelay.install(broadcaster.factory(), message -> true);
        assertEquals(1, broadcaster.listeners.size(),
                "a re-install must replace the previous listener, not stack on it");

        ClusterApprovalRelay.uninstall();
        assertEquals(0, broadcaster.listeners.size(),
                "registration must have a symmetric removal (Invariant #1)");
        assertFalse(ClusterApprovalRelay.isInstalled());
    }

    /**
     * Minimal broadcaster/factory pair that records listeners and lets the test
     * replay the last broadcast, standing in for the cluster hop a
     * {@code RedisBroadcaster} would make.
     */
    private static final class CapturingBroadcaster {
        final java.util.List<org.atmosphere.cpr.BroadcasterListener> listeners =
                new java.util.ArrayList<>();
        volatile Object last;

        org.atmosphere.cpr.BroadcasterFactory factory() {
            var broadcaster = org.mockito.Mockito.mock(org.atmosphere.cpr.Broadcaster.class);
            org.mockito.Mockito.when(broadcaster.addBroadcasterListener(
                            org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(inv -> {
                        listeners.add(inv.getArgument(0));
                        return broadcaster;
                    });
            org.mockito.Mockito.when(broadcaster.removeBroadcasterListener(
                            org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(inv -> {
                        listeners.remove(inv.getArgument(0));
                        return broadcaster;
                    });
            org.mockito.Mockito.when(broadcaster.broadcast(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(inv -> {
                        last = inv.getArgument(0);
                        return null;
                    });
            var factory = org.mockito.Mockito.mock(org.atmosphere.cpr.BroadcasterFactory.class);
            org.mockito.Mockito.when(factory.lookup(
                            org.mockito.ArgumentMatchers.eq(ClusterApprovalRelay.CHANNEL),
                            org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenReturn(broadcaster);
            return factory;
        }

        /** Replay the last broadcast into every registered listener. */
        void deliverLast() {
            var deliver = new org.atmosphere.cpr.Deliver(last, null, last);
            for (var listener : java.util.List.copyOf(listeners)) {
                listener.onMessage(null, deliver);
            }
        }
    }
}
