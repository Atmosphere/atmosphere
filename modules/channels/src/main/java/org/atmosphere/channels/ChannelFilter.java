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

/**
 * Intercepts messages flowing through messaging channels.
 * <p>
 * Inbound filters run before the message reaches the AI or application logic.
 * Outbound filters run before the response is sent to the external platform.
 * <p>
 * Return the message (possibly transformed) to continue the chain.
 * Return {@code null} to block the message.
 * <p>
 * Inspired by Atmosphere's {@code BroadcastFilter} pattern — same concept,
 * different concerns (platform adaptation vs. transport).
 */
public interface ChannelFilter {

    /**
     * Filter an incoming message from an external platform.
     * <p>
     * Use cases: rate limiting, spam detection, PII detection, audit logging,
     * language detection for routing.
     *
     * @param message the incoming message
     * @return the message (possibly transformed), or {@code null} to block
     */
    default IncomingMessage onIncoming(IncomingMessage message) {
        return message;
    }

    /**
     * Filter an outgoing message before it's sent to an external platform.
     * <p>
     * Use cases: message splitting, PII redaction, content moderation,
     * platform-specific formatting, cost metering.
     *
     * @param message the outgoing message
     * @param target  the target platform
     * @return the message (possibly transformed), or {@code null} to block
     */
    default OutgoingMessage onOutgoing(OutgoingMessage message, ChannelType target) {
        return message;
    }

    /**
     * Filter priority. Lower values run first. Default is 100.
     */
    default int order() {
        return 100;
    }
}
