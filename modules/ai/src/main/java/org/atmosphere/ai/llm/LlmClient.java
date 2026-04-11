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
package org.atmosphere.ai.llm;

import org.atmosphere.ai.StreamingSession;

/**
 * Common interface for calling OpenAI-compatible chat completion APIs.
 * Works with any endpoint that follows the OpenAI format (Gemini, Ollama, Azure, etc.).
 *
 * <p>Usage:</p>
 * <pre>{@code
 * var client = OpenAiCompatibleClient.builder()
 *     .baseUrl("https://generativelanguage.googleapis.com/v1beta/openai")
 *     .apiKey(System.getenv("GEMINI_API_KEY"))
 *     .build();
 *
 * var request = ChatCompletionRequest.of("gemini-2.0-flash", "Hello!");
 * client.streamChatCompletion(request, session);
 * }</pre>
 */
public interface LlmClient {

    /**
     * Stream a chat completion response, sending each streaming text to the session.
     * This method blocks until the response is fully streamed or an error occurs.
     *
     * @param request the chat completion request
     * @param session the streaming session to push streaming texts through
     */
    void streamChatCompletion(ChatCompletionRequest request, StreamingSession session);

    /**
     * Cancellation-aware variant of
     * {@link #streamChatCompletion(ChatCompletionRequest, StreamingSession)}.
     * Implementations that support hard-cancel hand the caller a reference
     * to the in-flight network resource via {@code streamSink}. The caller
     * can then close that resource from another thread to interrupt a
     * blocked read. The {@code cancelled} flag is a secondary safeguard for
     * tool-round re-submission loops and gaps between network reads.
     *
     * <p>Default implementation delegates to the 2-arg form; implementations
     * that can't expose a closeable handle rely solely on the
     * {@code cancelled} flag.</p>
     *
     * @param request    the chat completion request
     * @param session    the streaming session to push streaming texts through
     * @param cancelled  caller-managed cancel flag polled at loop boundaries
     * @param streamSink callback that receives a reference to the in-flight
     *                   {@link java.io.Closeable} (may be {@code null}); the
     *                   caller closes this to interrupt a blocked read
     */
    default void streamChatCompletion(ChatCompletionRequest request, StreamingSession session,
                                      java.util.concurrent.atomic.AtomicBoolean cancelled,
                                      java.util.function.Consumer<java.io.Closeable> streamSink) {
        streamChatCompletion(request, session);
    }
}
