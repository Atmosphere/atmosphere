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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives webhooks from external messaging platforms and routes them
 * to the appropriate {@link MessagingChannel} adapter.
 * <p>
 * When a message is received, all registered message handlers are invoked
 * in order — typically bridging to an Atmosphere {@code @AiEndpoint},
 * {@code @Agent} command router, or {@code Broadcaster}.
 */
@RestController
public class ChannelWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ChannelWebhookController.class);

    private final Map<String, MessagingChannel> channelsByPath;
    private final ChannelFilterChain filterChain;
    private final SeenMessageCache seenMessages;
    private final List<Consumer<IncomingMessage>> handlers = new CopyOnWriteArrayList<>();

    /**
     * Controller with the default inbound-deduplication window
     * ({@link SeenMessageCache#DEFAULT_MAX_ENTRIES} ids,
     * {@link SeenMessageCache#DEFAULT_TTL}).
     */
    public ChannelWebhookController(List<MessagingChannel> channels, ChannelFilterChain filterChain) {
        this(channels, filterChain, new SeenMessageCache());
    }

    /**
     * @param seenMessages the idempotency window for inbound deliveries; use
     *                     {@link SeenMessageCache#disabled()} to accept every
     *                     platform retry as a fresh message
     */
    public ChannelWebhookController(List<MessagingChannel> channels, ChannelFilterChain filterChain,
                                    SeenMessageCache seenMessages) {
        this.channelsByPath = new HashMap<>();
        this.filterChain = filterChain;
        this.seenMessages = seenMessages != null ? seenMessages : SeenMessageCache.disabled();
        for (MessagingChannel channel : channels) {
            channelsByPath.put(channel.webhookPath(), channel);
            log.info("Registered {} channel at {}", channel.channelType().id(), channel.webhookPath());
        }
        log.info("Inbound webhook deduplication {}",
                this.seenMessages.isEnabled() ? "enabled" : "disabled");
    }

    /**
     * Add a message handler that will be called for every incoming message.
     * Multiple handlers can be registered and will be called in order.
     *
     * @param handler the handler to add
     */
    public void addMessageHandler(Consumer<IncomingMessage> handler) {
        handlers.add(handler);
    }

    /**
     * Register a callback for incoming messages from any channel.
     *
     * @deprecated Use {@link #addMessageHandler(Consumer)} instead. This method
     *             clears all existing handlers and replaces them with the given one.
     */
    @Deprecated
    public synchronized void onMessage(Consumer<IncomingMessage> handler) {
        handlers.clear();
        handlers.add(handler);
    }

    /**
     * Route an incoming message through all registered handlers.
     * Used by Gateway-based channels (e.g., Discord) that receive messages
     * via WebSocket instead of HTTP webhooks.
     */
    public void routeMessage(IncomingMessage message) {
        deliverOnce(message);
    }

    /**
     * The idempotency window this controller enforces on inbound deliveries.
     */
    public SeenMessageCache seenMessages() {
        return seenMessages;
    }

    /**
     * Apply outbound filters before sending via a channel.
     */
    public ChannelFilterChain filterChain() {
        return filterChain;
    }

    @PostMapping("/webhook/{channel}")
    public ResponseEntity<String> handleWebhook(
            @PathVariable("channel") String channel,
            @RequestBody byte[] body,
            HttpServletRequest request) {

        String path = "/webhook/" + channel;
        MessagingChannel adapter = channelsByPath.get(path);

        if (adapter == null) {
            log.warn("No channel adapter for webhook path: {}", path);
            return ResponseEntity.notFound().build();
        }

        // Extract headers
        Map<String, String> headers = new HashMap<>();
        var headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            headers.put(name.toLowerCase(), request.getHeader(name));
        }

        try {
            // Handle Slack URL verification challenge before signature check
            if ("slack".equals(channel)) {
                try {
                    var json = new tools.jackson.databind.ObjectMapper().readTree(body);
                    if ("url_verification".equals(json.path("type").stringValue())) {
                        return ResponseEntity.ok(json.path("challenge").stringValue());
                    }
                } catch (tools.jackson.core.JacksonException ex) {
                    log.trace("Slack challenge detection: not JSON or not a challenge", ex);
                }
            }

            // Verify webhook signature
            adapter.verifySignature(headers, body);

            // Parse incoming messages
            List<IncomingMessage> messages = adapter.receive(headers, body);

            boolean handlerFailed = false;
            for (IncomingMessage msg : messages) {
                if (!deliverOnce(msg)) {
                    handlerFailed = true;
                }
            }

            if (handlerFailed) {
                return ResponseEntity.status(500).body("One or more message handlers failed");
            }
            return ResponseEntity.ok("ok");
        } catch (ChannelException e) {
            log.warn("Webhook error for {}: {}", channel, e.getMessage());
            return ResponseEntity.status(e.isRetryable() ? 500 : 400)
                    .body(e.isRetryable() ? "Internal server error" : "Bad request");
        }
    }

    /**
     * Deliver one inbound message exactly once: deduplicate, filter, dispatch.
     *
     * <p>Deduplication runs <em>before</em> the filter chain and before any
     * handler so a platform retry costs nothing — no filter side effects, no
     * agent run, no outbound reply — and is acknowledged 200 by the caller.
     * Messages carrying no platform message id bypass the check entirely
     * (see {@link SeenMessageCache#firstDelivery}); dropping unkeyed traffic
     * would lose real user messages.</p>
     *
     * <p>When dispatch fails the claim is released again: the endpoint answers
     * 5xx, the platform retries, and that retry must actually run the handlers
     * instead of being swallowed as a duplicate of the delivery that never
     * completed (Correctness Invariant #2).</p>
     *
     * @return {@code true} when the message was handled (or safely skipped),
     *         {@code false} when a handler threw
     */
    private boolean deliverOnce(IncomingMessage message) {
        var messageId = message.messageId();
        if (!seenMessages.firstDelivery(message.channelType(), messageId)) {
            log.debug("Duplicate {} delivery for message {} — acknowledged without re-processing",
                    message.channelType().id(), messageId);
            return true;
        }
        var filtered = filterChain.filterIncoming(message);
        if (filtered == null) {
            log.debug("Inbound message from {} blocked by filter", message.senderId());
            return true;
        }
        log.debug("Received {} message from {}: {}",
                filtered.channelType().id(), filtered.senderId(),
                filtered.text().substring(0, Math.min(50, filtered.text().length())));
        var dispatched = dispatchToHandlers(filtered);
        if (!dispatched) {
            seenMessages.forget(message.channelType(), messageId);
        }
        return dispatched;
    }

    /**
     * Dispatch a message to all registered handlers.
     *
     * @param message the incoming message
     * @return {@code true} if all handlers succeeded, {@code false} if any handler threw
     */
    private boolean dispatchToHandlers(IncomingMessage message) {
        if (handlers.isEmpty()) {
            log.warn("No message handler registered, dropping message from {}", message.channelType());
            return true;
        }
        boolean allSucceeded = true;
        for (Consumer<IncomingMessage> handler : handlers) {
            try {
                handler.accept(message);
            } catch (Exception e) {
                log.error("Message handler failed for {} message from {}: {}",
                        message.channelType().id(), message.senderId(), e.getMessage(), e);
                allSucceeded = false;
            }
        }
        return allSucceeded;
    }
}
