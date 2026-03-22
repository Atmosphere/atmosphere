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
 * When a message is received, the registered {@link #onMessage} callback
 * is invoked — typically bridging to an Atmosphere {@code @AiEndpoint}
 * or {@code Broadcaster}.
 */
@RestController
public class ChannelWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ChannelWebhookController.class);

    private final Map<String, MessagingChannel> channelsByPath;
    private final ChannelFilterChain filterChain;
    private Consumer<IncomingMessage> onMessage = msg ->
            log.warn("No message handler registered, dropping message from {}", msg.channelType());

    public ChannelWebhookController(List<MessagingChannel> channels, ChannelFilterChain filterChain) {
        this.channelsByPath = new HashMap<>();
        this.filterChain = filterChain;
        for (MessagingChannel channel : channels) {
            channelsByPath.put(channel.webhookPath(), channel);
            log.info("Registered {} channel at {}", channel.channelType().id(), channel.webhookPath());
        }
    }

    /**
     * Register a callback for incoming messages from any channel.
     */
    public void onMessage(Consumer<IncomingMessage> handler) {
        this.onMessage = handler;
    }

    /**
     * Route an incoming message through the registered handler.
     * Used by Gateway-based channels (e.g., Discord) that receive messages
     * via WebSocket instead of HTTP webhooks.
     */
    public void routeMessage(IncomingMessage message) {
        var filtered = filterChain.filterIncoming(message);
        if (filtered != null) {
            onMessage.accept(filtered);
        }
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
            // Verify webhook signature
            adapter.verifySignature(headers, body);

            // Parse incoming messages
            List<IncomingMessage> messages = adapter.receive(headers, body);

            for (IncomingMessage msg : messages) {
                var filtered = filterChain.filterIncoming(msg);
                if (filtered == null) {
                    log.debug("Inbound message from {} blocked by filter", msg.senderId());
                    continue;
                }
                log.debug("Received {} message from {}: {}",
                        filtered.channelType().id(), filtered.senderId(),
                        filtered.text().substring(0, Math.min(50, filtered.text().length())));
                onMessage.accept(filtered);
            }

            return ResponseEntity.ok("ok");
        } catch (ChannelException e) {
            log.warn("Webhook error for {}: {}", channel, e.getMessage());
            return ResponseEntity.status(e.isRetryable() ? 500 : 400)
                    .body(e.getMessage());
        }
    }
}
