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
package org.atmosphere.ai.filter;

import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcastFilter.BroadcastAction;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The stream-end frame a buffering filter defers (the reply's last text did not
 * end on a sentence boundary) goes where the session's own frames go: to the
 * sender only, or to the room for a room session. It used to be broadcast to
 * every subscriber of the path, because the session is deregistered before its
 * terminal frame reaches the filter, so the sender's answer (a complete summary
 * is often the whole reply) reached every other user.
 */
public class DeferredStreamEndDeliveryTest {

    /** A frame the broadcaster delivered, and to whom: {@code null} means every subscriber. */
    private record Delivery(AiStreamMessage message, Set<AtmosphereResource> targets) {
    }

    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    private final Broadcaster broadcaster = mock(Broadcaster.class);
    private final AtmosphereResource sender = mock(AtmosphereResource.class);

    /** A broadcaster that runs {@code filter} on each frame, as its filter chain does, then delivers it. */
    private void wire(AiStreamBroadcastFilter filter) {
        var factory = mock(BroadcasterFactory.class);
        when(factory.<Broadcaster>findBroadcaster("b1")).thenReturn(Optional.of(broadcaster));
        var config = mock(AtmosphereConfig.class);
        when(config.getBroadcasterFactory()).thenReturn(factory);
        filter.init(config);

        when(sender.uuid()).thenReturn("sender");
        when(sender.getBroadcaster()).thenReturn(broadcaster);
        doAnswer(inv -> {
            deliver(filter, inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(broadcaster).broadcast(any(), ArgumentMatchers.<Set<AtmosphereResource>>any());
        doAnswer(inv -> {
            deliver(filter, inv.getArgument(0), null);
            return null;
        }).when(broadcaster).broadcast(any());
    }

    private void deliver(AiStreamBroadcastFilter filter, Object message, Set<AtmosphereResource> targets)
            throws Exception {
        var action = filter.filter("b1", message, message);
        if (action.action() == BroadcastAction.ACTION.ABORT) {
            return;
        }
        var json = (String) ((RawMessage) action.message()).message();
        deliveries.add(new Delivery(AiStreamMessage.parse(json), targets));
    }

    private Delivery awaitTerminal() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            for (var d : deliveries) {
                if (d.message().isComplete() || d.message().isError()) {
                    return d;
                }
            }
            Thread.sleep(10);
        }
        return null;
    }

    @Test
    public void piiDeferredCompleteReachesOnlyTheSenderAndIsRedacted() throws Exception {
        wire(new PiiRedactionFilter());
        var session = StreamingSessions.start("pii-unicast-" + UUID.randomUUID(), sender);

        session.send("Call me at 555-123-4567");
        session.complete("Call me at 555-123-4567");

        var terminal = awaitTerminal();
        assertNotNull(terminal, "the deferred complete frame must be delivered");
        for (var d : deliveries) {
            assertEquals(Set.of(sender), d.targets(),
                    "every frame, the deferred complete included, must go to the sender only: " + d);
        }
        assertFalse(terminal.message().data().contains("555-123-4567"),
                "the complete summary must be redacted, got " + terminal.message().data());
        assertTrue(terminal.message().data().contains("[REDACTED]"));
    }

    @Test
    public void piiCompleteSummaryIsRedactedWithoutBufferedText() throws Exception {
        wire(new PiiRedactionFilter());
        var session = StreamingSessions.start("pii-summary-" + UUID.randomUUID(), sender);

        session.send("Noted.");
        session.complete("Reach me at user@example.com");

        var terminal = awaitTerminal();
        assertNotNull(terminal);
        assertEquals(Set.of(sender), terminal.targets());
        assertFalse(terminal.message().data().contains("user@example.com"), terminal.message().data());
    }

    @Test
    public void safetyDeferredCompleteReachesOnlyTheSender() throws Exception {
        wire(new ContentSafetyFilter(text -> new ContentSafetyFilter.SafetyResult.Safe()));
        var session = StreamingSessions.start("safety-unicast-" + UUID.randomUUID(), sender);

        session.send("- a list item without a full stop");
        session.complete("the whole private reply");

        var terminal = awaitTerminal();
        assertNotNull(terminal, "the deferred complete frame must be delivered");
        for (var d : deliveries) {
            assertEquals(Set.of(sender), d.targets(),
                    "every frame, the deferred complete included, must go to the sender only: " + d);
        }
    }

    @Test
    public void roomSessionDeferredCompleteStillReachesTheRoom() throws Exception {
        wire(new PiiRedactionFilter());
        var session = StreamingSessions.startRoomBroadcast(sender);

        session.send("Call me at 555-123-4567");
        session.complete();

        var terminal = awaitTerminal();
        assertNotNull(terminal, "the deferred complete frame must be delivered");
        for (var d : deliveries) {
            assertNull(d.targets(), "a room session's frames, the deferred complete included, go to the room: " + d);
        }
    }

    @Test
    public void topicSessionDeferredCompleteStillReachesEverySubscriber() throws Exception {
        // StreamingSessions.start(Broadcaster): every frame goes to the whole
        // broadcaster, so its deferred end frame does too.
        wire(new PiiRedactionFilter());
        var session = StreamingSessions.start(broadcaster);

        session.send("Call me at 555-123-4567");
        session.complete();

        var terminal = awaitTerminal();
        assertNotNull(terminal, "the deferred complete frame must be delivered");
        for (var d : deliveries) {
            assertNull(d.targets(), "a topic session's frames, the deferred complete included, go to all: " + d);
        }
    }
}
