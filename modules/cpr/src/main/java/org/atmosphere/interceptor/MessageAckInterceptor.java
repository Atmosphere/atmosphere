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
package org.atmosphere.interceptor;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Interceptor that implements message acknowledgment for reliable delivery.
 *
 * <p>When a client sends a message with an {@code X-Atmosphere-Message-Id} header
 * or query parameter, the server echoes back an {@code X-Atmosphere-Ack} header
 * with the same ID to confirm receipt. If no ID is provided, the server generates
 * one and attaches it as a request attribute for downstream handlers.</p>
 *
 * <p>This interceptor works together with the client-side offline queue and
 * optimistic update system to provide reliable, at-least-once delivery.</p>
 *
 * @since 4.0.8
 */
public class MessageAckInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(MessageAckInterceptor.class);

    private final AtomicLong ackCount = new AtomicLong();

    @Override
    public void configure(AtmosphereConfig config) {
        logger.info("MessageAckInterceptor configured");
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        // Skip post-upgrade WebSocket messages that are just heartbeats
        if (Utils.webSocketMessage(r)) {
            var messageId = extractMessageId(r);
            if (messageId != null && !messageId.isBlank()) {
                // Acknowledge the message
                r.getResponse().setHeader(HeaderConfig.X_ATMOSPHERE_ACK, messageId);
                r.getRequest().setAttribute(FrameworkConfig.MESSAGE_ID, messageId);
                ackCount.incrementAndGet();
                logger.debug("ACK message {} for resource {}", messageId, r.uuid());
            }
            return Action.CONTINUE;
        }

        // For initial requests, assign a server-generated message ID
        var messageId = extractMessageId(r);
        if (messageId == null || messageId.isBlank()) {
            messageId = UUID.randomUUID().toString();
        }
        r.getRequest().setAttribute(FrameworkConfig.MESSAGE_ID, messageId);

        return Action.CONTINUE;
    }

    /**
     * Returns the total number of acknowledged messages.
     */
    public long totalAcks() {
        return ackCount.get();
    }

    private String extractMessageId(AtmosphereResource r) {
        // Check header first
        var id = r.getRequest().getHeader(HeaderConfig.X_ATMOSPHERE_MESSAGE_ID);
        if (id != null && !id.isBlank()) {
            return id;
        }
        // Fallback to query parameter
        return r.getRequest().getParameter(HeaderConfig.X_ATMOSPHERE_MESSAGE_ID);
    }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.BEFORE_DEFAULT;
    }

    @Override
    public String toString() {
        return "MessageAckInterceptor{acks=" + ackCount.get() + "}";
    }
}
