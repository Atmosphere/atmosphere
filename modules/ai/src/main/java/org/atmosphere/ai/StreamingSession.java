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
package org.atmosphere.ai;

/**
 * A streaming session that delivers streaming text chunks from an AI model to connected
 * clients via Atmosphere's broadcast infrastructure.
 *
 * <p>This is the core SPI interface — all AI framework adapters (Spring AI,
 * LangChain4j, Embabel, etc.) push streaming texts through this interface. The wire
 * protocol, caching, reconnect handling, and client-side hooks are handled
 * automatically.</p>
 *
 * <p>Thread-safe: multiple threads may call {@link #send(String)} concurrently.</p>
 */
public interface StreamingSession extends AutoCloseable {

    /**
     * Unique identifier for this streaming session.
     */
    String sessionId();

    /**
     * Send a streaming text chunk to the client.
     *
     * @param text the text chunk (typically a single streaming text from an LLM)
     */
    void send(String text);

    /**
     * Send structured metadata alongside the stream (e.g., model name, usage stats).
     *
     * @param key   metadata key
     * @param value metadata value (must be JSON-serializable)
     */
    void sendMetadata(String key, Object value);

    /**
     * Report typed token usage for this chat completion. Promoted in Phase 1 of
     * the unified {@code @Agent} API from three ad-hoc
     * {@code sendMetadata("ai.tokens.input|output|total", ...)} calls into a
     * single typed event.
     *
     * <p>The default implementation re-emits the legacy {@code ai.tokens.*}
     * metadata keys so existing consumers ({@code MetricsCapturingSession},
     * {@code MicrometerAiMetrics}, budget interceptors, cost dashboards) keep
     * working without any change. Sessions that want to capture the typed
     * record itself should override this method.</p>
     *
     * <p>Runtime bridges should call this exactly once per chat completion,
     * after the model reports its usage, and should skip the call entirely
     * when {@link TokenUsage#hasCounts()} returns {@code false}.</p>
     *
     * @param usage the typed token counts; ignored when {@code null}
     */
    default void usage(TokenUsage usage) {
        if (usage == null) {
            return;
        }
        if (usage.input() > 0) {
            sendMetadata("ai.tokens.input", usage.input());
        }
        if (usage.output() > 0) {
            sendMetadata("ai.tokens.output", usage.output());
        }
        if (usage.cachedInput() > 0) {
            sendMetadata("ai.tokens.cached_input", usage.cachedInput());
        }
        if (usage.total() > 0) {
            sendMetadata("ai.tokens.total", usage.total());
        }
        if (usage.model() != null && !usage.model().isBlank()) {
            sendMetadata("ai.tokens.model", usage.model());
        }
    }

    /**
     * Progressive tool-argument streaming (Phase 5 of the unified {@code @Agent}
     * API). Runtimes whose provider emits incremental JSON for tool arguments
     * (Spring AI, LangChain4j {@code onPartialToolExecutionRequest}, ADK
     * streaming events, Koog {@code StreamFrame.ToolCallDelta}) call this
     * method for each chunk so browser UIs can render "typing" state on
     * tool-argument fields before the final consolidated
     * {@code AiEvent.ToolStart} fires.
     *
     * <p>The default implementation emits a structured {@code metadata} frame
     * keyed by the tool-call id so existing wire-format consumers observe the
     * delta without a new event type. Runtimes that want a richer wire
     * representation can override this method.</p>
     *
     * @param toolCallId provider-assigned tool-call identifier; ignored when null
     * @param argsChunk  incremental JSON fragment; ignored when null or empty
     */
    default void toolCallDelta(String toolCallId, String argsChunk) {
        if (toolCallId == null || argsChunk == null || argsChunk.isEmpty()) {
            return;
        }
        sendMetadata("ai.toolCall.delta." + toolCallId, argsChunk);
    }

    /**
     * Send a progress/status update (e.g., "Thinking...", "Searching documents...").
     *
     * @param message human-readable progress message
     */
    void progress(String message);

    /**
     * Signal that the stream has completed successfully.
     */
    void complete();

    /**
     * Signal that the stream has completed with a final summary.
     *
     * @param summary aggregated final response
     */
    void complete(String summary);

    /**
     * Signal that the stream has failed.
     *
     * @param t the cause of the failure
     */
    void error(Throwable t);

    /**
     * Whether this session has been completed or errored.
     */
    boolean isClosed();

    /**
     * Send multi-modal content to the client. Default implementation handles
     * text content via {@link #send(String)} and throws for binary content.
     *
     * <p>Wire protocol for content:</p>
     * <pre>{@code
     * {"type":"content","contentType":"text","data":"...","sessionId":"...","seq":N}
     * {"type":"content","contentType":"image","mimeType":"image/png","data":"<base64>","sessionId":"...","seq":N}
     * {"type":"content","contentType":"file","mimeType":"text/csv","fileName":"results.csv","data":"<base64>","sessionId":"...","seq":N}
     * }</pre>
     *
     * @param content the content to send
     */
    default void sendContent(Content content) {
        if (content instanceof Content.Text text) {
            send(text.text());
        } else {
            throw new UnsupportedOperationException(
                    "This session does not support binary content. "
                            + "Override sendContent() to handle multi-modal content.");
        }
    }

    /**
     * Emit a structured {@link AiEvent} to the client. Events are serialized
     * as JSON frames on the wire, enabling rich real-time UIs that display
     * tool calls, agent steps, structured output fields, and routing decisions.
     *
     * <p>The default implementation maps common events to legacy methods for
     * backward compatibility:</p>
     * <ul>
     *   <li>{@link AiEvent.TextDelta} → {@link #send(String)}</li>
     *   <li>{@link AiEvent.Progress} → {@link #progress(String)}</li>
     *   <li>{@link AiEvent.Error} → {@link #error(Throwable)}</li>
     *   <li>{@link AiEvent.Complete} → {@link #complete(String)}</li>
     * </ul>
     *
     * <p>Implementations that support rich event streaming should override
     * this method to serialize the full event as a JSON frame.</p>
     *
     * @param event the event to emit
     * @see AiEvent
     */
    default void emit(AiEvent event) {
        switch (event) {
            case AiEvent.TextDelta delta -> send(delta.text());
            case AiEvent.Progress p -> progress(p.message());
            case AiEvent.Error err -> error(new RuntimeException(err.message()));
            case AiEvent.Complete c -> {
                if (c.summary() != null) {
                    complete(c.summary());
                } else {
                    complete();
                }
            }
            default -> sendMetadata("event." + event.eventType(),
                    event.toString());
        }
    }

    /**
     * Hand off the current conversation to another agent. The target agent
     * receives the message along with the conversation history from this session.
     *
     * <p>Only supported on sessions created by the {@code @Agent} infrastructure,
     * where the agent transport and memory are available. Emits an
     * {@link AiEvent.Handoff} event to the client before routing.</p>
     *
     * @param agentName the target agent to hand off to
     * @param message   the message to forward to the target agent
     * @throws UnsupportedOperationException if this session does not support handoffs
     * @throws IllegalStateException if a handoff is already in progress (cycle guard)
     */
    default void handoff(String agentName, String message) {
        throw new UnsupportedOperationException(
                "handoff() is only supported on agent-backed sessions. "
                        + "Use @Agent to enable handoff support.");
    }

    /**
     * Send a user message to the resolved {@link AiSupport} and stream the
     * response back through this session. Only supported on sessions created
     * by the {@code @AiEndpoint} infrastructure (i.e., {@link AiStreamingSession}).
     *
     * @param message the user message to send to the AI model
     * @throws UnsupportedOperationException if this session does not support
     *         auto-resolved AI streaming
     */
    default void stream(String message) {
        throw new UnsupportedOperationException(
                "stream(String) is only supported on AiStreamingSession. "
                        + "Use @AiEndpoint or create an AiStreamingSession explicitly.");
    }

    @Override
    default void close() {
        if (!isClosed()) {
            complete();
        }
    }
}
