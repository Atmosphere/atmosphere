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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ── inbound idempotency ──

    @Test
    void duplicateDeliveryOfTheSameMessageIdIsProcessedOnce() {
        var received = new CopyOnWriteArrayList<IncomingMessage>();
        controller.addMessageHandler(received::add);

        // Slack/Meta/Telegram re-deliver the identical payload after a non-2xx
        // or a timeout. The retry must not re-run the agent or re-send a reply.
        controller.routeMessage(makeIncoming("hello", "msg-42"));
        controller.routeMessage(makeIncoming("hello", "msg-42"));
        controller.routeMessage(makeIncoming("hello", "msg-42"));

        assertEquals(1, received.size(), "a re-delivered message must be handled exactly once");
    }

    @Test
    void distinctMessageIdsAreEachProcessed() {
        var received = new CopyOnWriteArrayList<IncomingMessage>();
        controller.addMessageHandler(received::add);

        controller.routeMessage(makeIncoming("first", "msg-1"));
        controller.routeMessage(makeIncoming("second", "msg-2"));

        assertEquals(List.of("first", "second"), received.stream().map(IncomingMessage::text).toList());
    }

    @Test
    void messagesWithoutAMessageIdBypassDeduplication() {
        var received = new CopyOnWriteArrayList<IncomingMessage>();
        controller.addMessageHandler(received::add);

        // An unkeyed message can't be proven to be a retry — dropping it would
        // silently lose real user traffic.
        controller.routeMessage(makeIncoming("unkeyed", null));
        controller.routeMessage(makeIncoming("unkeyed", ""));
        controller.routeMessage(makeIncoming("unkeyed", "   "));

        assertEquals(3, received.size(), "unkeyed messages must never be deduplicated away");
    }

    @Test
    void aFailedDispatchReleasesTheClaimSoThePlatformRetryRuns() {
        var attempts = new AtomicInteger();
        controller.addMessageHandler(m -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("handler blew up — endpoint answers 5xx");
            }
        });

        controller.routeMessage(makeIncoming("retry me", "msg-7"));
        controller.routeMessage(makeIncoming("retry me", "msg-7"));

        assertEquals(2, attempts.get(),
                "a delivery that failed must not leave a dedup claim behind");
    }

    @Test
    void deduplicationCanBeDisabled() {
        controller = new ChannelWebhookController(List.of(mockChannel), filterChain,
                SeenMessageCache.disabled());
        var received = new CopyOnWriteArrayList<IncomingMessage>();
        controller.addMessageHandler(received::add);

        controller.routeMessage(makeIncoming("hello", "msg-42"));
        controller.routeMessage(makeIncoming("hello", "msg-42"));

        assertEquals(2, received.size());
        assertFalse(controller.seenMessages().isEnabled());
    }

    @Test
    void aRetriedWebhookDeliveryIsAckedWithoutReProcessing() throws Exception {
        // The real HTTP entry point: a platform that got a 500 (or timed out)
        // POSTs the identical payload again. The retry must be acknowledged 200
        // — not 500, which would trigger yet another retry — and must not run
        // the handlers a second time.
        var body = "{\"update_id\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var incoming = makeIncoming("hello", "msg-77");
        when(mockChannel.receive(org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenReturn(List.of(incoming));

        var received = new CopyOnWriteArrayList<IncomingMessage>();
        controller.addMessageHandler(received::add);

        var first = controller.handleWebhook("telegram", body, emptyRequest());
        var retry = controller.handleWebhook("telegram", body, emptyRequest());

        assertEquals(200, first.getStatusCode().value());
        assertEquals(200, retry.getStatusCode().value(),
                "a duplicate delivery must be acknowledged, not answered with a retry-triggering 5xx");
        assertEquals(1, received.size(), "the retried webhook must not re-run the agent");
    }

    @Test
    void aFailedWebhookDeliveryAnswers500AndThePlatformRetryIsProcessed() throws Exception {
        var body = "{\"update_id\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var incoming = makeIncoming("hello", "msg-78");
        when(mockChannel.receive(org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenReturn(List.of(incoming));

        var attempts = new AtomicInteger();
        controller.addMessageHandler(m -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("handler blew up");
            }
        });

        var first = controller.handleWebhook("telegram", body, emptyRequest());
        var retry = controller.handleWebhook("telegram", body, emptyRequest());

        assertEquals(500, first.getStatusCode().value());
        assertEquals(200, retry.getStatusCode().value());
        assertEquals(2, attempts.get(),
                "the retry of a failed delivery must actually be processed");
    }

    @Test
    void deduplicationRunsBeforeTheFilterChain() {
        var filterCalls = new AtomicInteger();
        var countingChain = new ChannelFilterChain(List.of(new ChannelFilter() {
            @Override
            public IncomingMessage onIncoming(IncomingMessage message) {
                filterCalls.incrementAndGet();
                return message;
            }
        }));
        controller = new ChannelWebhookController(List.of(mockChannel), countingChain);
        controller.addMessageHandler(m -> { /* accept */ });

        controller.routeMessage(makeIncoming("hello", "msg-9"));
        controller.routeMessage(makeIncoming("hello", "msg-9"));

        assertEquals(1, filterCalls.get(),
                "a duplicate must cost nothing — not even a filter-chain pass");
    }

    // ── helpers ──

    /** A request with no headers — enough for the webhook entry point. */
    private static jakarta.servlet.http.HttpServletRequest emptyRequest() {
        var request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getHeaderNames()).thenReturn(java.util.Collections.emptyEnumeration());
        return request;
    }

    private static IncomingMessage makeIncoming(String text) {
        return makeIncoming(text, "msg-" + UUID.randomUUID());
    }

    private static IncomingMessage makeIncoming(String text, String messageId) {
        return new IncomingMessage(ChannelType.TELEGRAM, "sender-1",
                Optional.empty(), text, "conv-1", messageId, Instant.now());
    }
}
