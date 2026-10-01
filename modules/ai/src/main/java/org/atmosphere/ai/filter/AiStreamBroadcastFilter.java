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

import org.atmosphere.ai.DefaultStreamingSession;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.BroadcastFilterLifecycle;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Base class for {@link BroadcastFilter} implementations that operate on the AI streaming
 * wire protocol. Handles {@link RawMessage} unwrapping and JSON parsing via
 * {@link AiStreamMessage}; subclasses implement {@link #filterAiMessage} to inspect
 * or transform AI messages. Non-AI messages pass through unchanged.
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
     * Subclasses use this to emit additional messages (e.g., flushing buffered streaming texts
     * before a stream-end signal) when a single {@link BroadcastAction} is insufficient.
     *
     * @return the broadcaster factory, or {@code null} if not yet initialized
     */
    protected BroadcasterFactory broadcasterFactory() {
        return broadcasterFactory;
    }

    /**
     * Text a filter holds back for one session until a sentence boundary, with
     * where that session's frames go, captured when its first text was held
     * (the session is still live then; it is not by the time its terminal frame
     * arrives).
     *
     * @param text     the held-back text
     * @param delivery the session's delivery, or {@code null} for a session
     *                 {@link DefaultStreamingSession} does not know
     */
    protected record PendingText(StringBuffer text, DefaultStreamingSession.Delivery delivery) {

        /** Start holding text for {@code sessionId}, capturing its delivery now. */
        public static PendingText forSession(String sessionId) {
            return new PendingText(new StringBuffer(),
                    DefaultStreamingSession.deliveryForSession(sessionId).orElse(null));
        }
    }

    /**
     * Emit {@code message}, a stream-end frame held back while the filter flushed
     * buffered text in its place, once the current filter chain has delivered
     * that text. It goes where the session's own frames go: to the originating
     * resource only, or to the whole room for a room session. The session is
     * deregistered by the time its terminal frame reaches a filter, so
     * {@code delivery} must be captured while the session was live
     * ({@link DefaultStreamingSession#deliveryForSession}). Without one the frame
     * is dropped and logged: a per-path broadcast would hand one user's reply to
     * every subscriber of the path.
     *
     * @param broadcasterId the broadcaster the terminal frame was broadcast on
     * @param sessionId     the streaming session the frame belongs to
     * @param delivery      the session's delivery, captured while it was live; may be {@code null}
     * @param message       the deferred stream-end frame
     */
    protected void deferStreamEnd(String broadcasterId, String sessionId,
                                  DefaultStreamingSession.Delivery delivery, RawMessage message) {
        if (delivery == null) {
            logger.warn("Dropping the deferred stream-end frame of session {}: its recipient is unknown", sessionId);
            return;
        }
        var factory = broadcasterFactory();
        Thread.ofVirtual().name("ai-stream-end-flush").start(() -> {
            try {
                // Wait for the current filter chain to complete and deliver the flushed streaming text
                Thread.sleep(50);
                if (factory != null) {
                    factory.findBroadcaster(broadcasterId).ifPresent(b -> {
                        if (delivery.toRoom()) {
                            b.broadcast(message);
                        } else {
                            b.broadcast(message, Set.of(delivery.resource()));
                        }
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted before emitting the deferred stream-end frame of session {}", sessionId, e);
            } catch (Exception e) {
                logger.warn("Failed to emit deferred stream-end message: {}", e.getMessage(), e);
            }
        });
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
