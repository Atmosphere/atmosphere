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
 * A streaming session that delivers tokens/chunks from an AI model to connected
 * clients via Atmosphere's broadcast infrastructure.
 *
 * <p>This is the core SPI interface — all AI framework adapters (Spring AI,
 * LangChain4j, Embabel, etc.) push tokens through this interface. The wire
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
     * Send a token/chunk to the client.
     *
     * @param token the text chunk (typically a single token from an LLM)
     */
    void send(String token);

    /**
     * Send structured metadata alongside the stream (e.g., model name, usage stats).
     *
     * @param key   metadata key
     * @param value metadata value (must be JSON-serializable)
     */
    void sendMetadata(String key, Object value);

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

    @Override
    default void close() {
        if (!isClosed()) {
            complete();
        }
    }
}
