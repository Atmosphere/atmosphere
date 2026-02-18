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
 * SPI for AI framework adapters. Implement this interface to bridge any
 * AI/LLM framework's streaming API to Atmosphere.
 *
 * <p>Each adapter is a thin layer that converts the framework's streaming
 * mechanism (Flux, callbacks, channels, etc.) into calls on
 * {@link StreamingSession}.</p>
 *
 * <p>Example for a hypothetical framework:</p>
 * <pre>{@code
 * public class MyAiAdapter implements AiStreamingAdapter<MyPrompt> {
 *     @Override
 *     public String name() { return "my-ai"; }
 *
 *     @Override
 *     public void stream(MyPrompt request, StreamingSession session) {
 *         myModel.streamTokens(request, token -> session.send(token),
 *             () -> session.complete(),
 *             err -> session.error(err));
 *     }
 * }
 * }</pre>
 *
 * @param <T> the request type specific to the AI framework
 */
public interface AiStreamingAdapter<T> {

    /**
     * Human-readable name for this adapter (e.g., "spring-ai", "langchain4j", "embabel").
     */
    String name();

    /**
     * Start streaming a response for the given request. Implementations should
     * call {@link StreamingSession#send(String)} for each token and
     * {@link StreamingSession#complete()} when done.
     *
     * @param request the AI framework-specific request
     * @param session the streaming session to push tokens through
     */
    void stream(T request, StreamingSession session);
}
