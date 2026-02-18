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
     * Stream a chat completion response, sending each token to the session.
     * This method blocks until the response is fully streamed or an error occurs.
     *
     * @param request the chat completion request
     * @param session the streaming session to push tokens through
     */
    void streamChatCompletion(ChatCompletionRequest request, StreamingSession session);
}
