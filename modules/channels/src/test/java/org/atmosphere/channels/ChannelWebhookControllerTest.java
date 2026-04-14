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
package org.atmosphere.channels;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ChannelWebhookController} — message routing, handler dispatch,
 * and filter chain integration (no HTTP layer, just the Java API).
 */
class ChannelWebhookControllerTest {

    private ChannelWebhookController controller;
    private ChannelFilterChain filterChain;
    private MessagingChannel mockChannel;

    @BeforeEach
    void setUp() {
        filterChain = new ChannelFilterChain(List.of());
        mockChannel = mock(MessagingChannel.class);
        when(mockChannel.channelType()).thenReturn(ChannelType.TELEGRAM);
        when(mockChannel.webhookPath()).thenReturn("/webhook/telegram");
        controller = new ChannelWebhookController(List.of(mockChannel), filterChain);
    }

    // ── routeMessage ──

    @Test
    void routeMessage_dispatchesToRegisteredHandler() {
        var received = new CopyOnWriteArrayList<IncomingMessage>();
        controller.addMessageHandler(received::add);

        var msg = makeIncoming("hello from telegram");
        controller.routeMessage(msg);

        assertEquals(1, received.size());
        assertEquals("hello from telegram", received.getFirst().text());
    }

    @Test
    void routeMessage_blockedByFilter() {
        // Create a blocking filter chain
        var blockingChain = new ChannelFilterChain(List.of(new ChannelFilter() {
            @Override
            public IncomingMessage onIncoming(IncomingMessage message) {
                return null; // block
            }
        }));
        controller = new ChannelWebhookController(List.of(mockChannel), blockingChain);

        var received = new CopyOnWriteArrayList<IncomingMessage>();
        controller.addMessageHandler(received::add);

        controller.routeMessage(makeIncoming("blocked"));
        assertTrue(received.isEmpty());
    }

    @Test
    void routeMessage_noHandlersDropsMessage() {
        // No handlers registered — should not throw
        controller.routeMessage(makeIncoming("dropped"));
    }

    // ── addMessageHandler ──

    @Test
    void addMessageHandler_multipleHandlersCalledInOrder() {
        var order = new ArrayList<String>();
        controller.addMessageHandler(m -> order.add("first"));
        controller.addMessageHandler(m -> order.add("second"));

        controller.routeMessage(makeIncoming("test"));

        assertEquals(List.of("first", "second"), order);
    }

    @Test
    void addMessageHandler_handlerExceptionDoesNotStopOthers() {
        var received = new CopyOnWriteArrayList<String>();
        controller.addMessageHandler(m -> { throw new RuntimeException("boom"); });
        controller.addMessageHandler(m -> received.add(m.text()));

        controller.routeMessage(makeIncoming("after error"));

        assertEquals(1, received.size());
        assertEquals("after error", received.getFirst());
    }

    // ── onMessage (deprecated) ──

    @Test
    @SuppressWarnings("deprecation")
    void onMessage_replacesAllHandlers() {
        controller.addMessageHandler(m -> { /* first */ });

        var received = new CopyOnWriteArrayList<IncomingMessage>();
        controller.onMessage(received::add);

        controller.routeMessage(makeIncoming("replaced"));

        assertEquals(1, received.size());
    }

    // ── filterChain accessor ──

    @Test
    void filterChain_returnsSameInstance() {
        assertEquals(filterChain, controller.filterChain());
    }

    // ── constructor wiring ──

    @Test
    void constructor_multipleChannelsRegistered() {
        var slackChannel = mock(MessagingChannel.class);
        when(slackChannel.channelType()).thenReturn(ChannelType.SLACK);
        when(slackChannel.webhookPath()).thenReturn("/webhook/slack");

        var multiController = new ChannelWebhookController(
                List.of(mockChannel, slackChannel), filterChain);
        assertNotNull(multiController.filterChain());
    }

    // ── helpers ──

    private static IncomingMessage makeIncoming(String text) {
        return new IncomingMessage(ChannelType.TELEGRAM, "sender-1",
                Optional.empty(), text, "conv-1", "msg-1", Instant.now());
    }
}
