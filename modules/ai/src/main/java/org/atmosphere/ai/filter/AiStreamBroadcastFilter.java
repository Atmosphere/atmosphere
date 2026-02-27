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

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.BroadcastFilterLifecycle;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for {@link BroadcastFilter} implementations that operate
 * on the AI streaming wire protocol.
 *
 * <p>When {@code DefaultStreamingSession} broadcasts a message, it wraps the JSON
 * string in a {@link RawMessage}: {@code broadcaster.broadcast(new RawMessage(json))}.
 * The {@code FilterManipulator} registered by {@code ManagedServiceProcessor} only
 * unwraps {@code Managed} instances (a deprecated subclass of {@code RawMessage}),
 * so plain {@code RawMessage} objects pass through to filters as-is.</p>
 *
 * <p>This base class handles the {@code RawMessage} unwrapping, JSON parsing via
 * {@link AiStreamMessage}, and re-wrapping automatically. Subclasses only need to
 * implement {@link #filterAiMessage} to inspect or transform AI messages. Non-AI
 * messages (anything that is not a {@code RawMessage} wrapping a JSON string with
 * a valid "type" field) pass through unchanged.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * public class MyFilter extends AiStreamBroadcastFilter {
 *     @Override
 *     protected BroadcastAction filterAiMessage(
 *             String broadcasterId, AiStreamMessage msg,
 *             String originalJson, RawMessage rawMessage) {
 *         if (msg.isToken()) {
 *             // transform the token
 *             var modified = msg.withData(transform(msg.data()));
 *             return new BroadcastAction(new RawMessage(modified.toJson()));
 *         }
 *         return new BroadcastAction(rawMessage);
 *     }
 * }
 * }</pre>
 *
 * @see AiStreamMessage
 * @see BroadcastFilter
 */
public abstract class AiStreamBroadcastFilter implements BroadcastFilterLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(AiStreamBroadcastFilter.class);

    private volatile BroadcasterFactory broadcasterFactory;

    @Override
    public void init(AtmosphereConfig config) {
        this.broadcasterFactory = config.getBroadcasterFactory();
    }

    @Override
    public void destroy() {
        // no-op by default
    }

    /**
     * Get the broadcaster factory for deferred broadcasts.
     * Subclasses use this to emit additional messages (e.g., flushing buffered tokens
     * before a stream-end signal) when a single {@link BroadcastAction} is insufficient.
     *
     * @return the broadcaster factory, or {@code null} if not yet initialized
     */
    protected BroadcasterFactory broadcasterFactory() {
        return broadcasterFactory;
    }

    @Override
    public final BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
        // Only process RawMessage from AI streaming
        if (!(message instanceof RawMessage raw)) {
            return new BroadcastAction(message);
        }

        var inner = raw.message();
        if (!(inner instanceof String json)) {
            return new BroadcastAction(message);
        }

        try {
            var parsed = AiStreamMessage.parse(json);
            if (parsed == null) {
                return new BroadcastAction(message);
            }
            return filterAiMessage(broadcasterId, parsed, json, raw);
        } catch (Exception e) {
            logger.debug("Failed to parse AI stream message, passing through: {}", e.getMessage());
            return new BroadcastAction(message);
        }
    }

    /**
     * Filter an AI streaming message.
     *
     * <p>Implementations should return:</p>
     * <ul>
     *   <li>{@code new BroadcastAction(rawMessage)} — pass through unchanged</li>
     *   <li>{@code new BroadcastAction(new RawMessage(modified.toJson()))} — pass through modified</li>
     *   <li>{@code new BroadcastAction(ACTION.ABORT, rawMessage)} — drop the message</li>
     *   <li>{@code new BroadcastAction(ACTION.SKIP, rawMessage)} — stop filter chain, deliver</li>
     * </ul>
     *
     * @param broadcasterId the broadcaster ID
     * @param msg           the parsed AI stream message
     * @param originalJson  the original JSON string before parsing
     * @param rawMessage    the original {@link RawMessage} wrapper
     * @return the filter action
     */
    protected abstract BroadcastAction filterAiMessage(
            String broadcasterId, AiStreamMessage msg, String originalJson, RawMessage rawMessage);
}
