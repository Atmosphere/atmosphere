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

import java.util.List;
import java.util.Map;

/**
 * Unified messaging channel interface for external chat platforms.
 * <p>
 * Each channel adapter (Telegram, Slack, Discord, WhatsApp) implements this
 * to provide webhook verification, inbound message parsing, and outbound
 * message delivery with platform-specific formatting.
 * <p>
 * Inspired by <a href="https://github.com/dravr-ai/dravr-canot">Canot</a>'s
 * {@code MessagingChannel} trait.
 */
public interface MessagingChannel {

    /**
     * Channel type identifier (e.g., "telegram", "slack").
     */
    ChannelType channelType();

    /**
     * Webhook path this channel listens on (e.g., "/webhook/telegram").
     */
    String webhookPath();

    /**
     * Verify the webhook signature using the channel's algorithm.
     *
     * @param headers HTTP headers from the webhook request
     * @param body    raw request body bytes
     * @throws ChannelException if verification fails
     */
    void verifySignature(Map<String, String> headers, byte[] body);

    /**
     * Parse an inbound webhook payload into structured messages.
     * A single webhook may contain multiple messages (e.g., Messenger batches).
     *
     * @param headers HTTP headers
     * @param body    raw request body
     * @return list of parsed incoming messages
     */
    List<IncomingMessage> receive(Map<String, String> headers, byte[] body);

    /**
     * Send an outbound message through the channel's API.
     *
     * @param message the message to send
     * @return delivery receipt with the channel's message ID
     */
    DeliveryReceipt send(OutgoingMessage message);

    /**
     * Maximum message length supported by this channel.
     */
    int maxMessageLength();
}
