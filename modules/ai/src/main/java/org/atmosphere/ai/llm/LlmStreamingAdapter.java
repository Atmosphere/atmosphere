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

import org.atmosphere.ai.AiStreamingAdapter;
import org.atmosphere.ai.StreamingSession;

/**
 * Adapter that bridges an {@link LlmClient} to the Atmosphere
 * {@link AiStreamingAdapter} SPI. This allows using any OpenAI-compatible
 * LLM endpoint through the standard adapter interface.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * var client = OpenAiCompatibleClient.gemini(apiKey);
 * var adapter = new LlmStreamingAdapter(client);
 *
 * var request = ChatCompletionRequest.of("gemini-2.0-flash", "Hello!");
 * adapter.stream(request, session);
 * }</pre>
 */
public class LlmStreamingAdapter implements AiStreamingAdapter<ChatCompletionRequest> {

    private final LlmClient client;

    public LlmStreamingAdapter(LlmClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "openai-compatible";
    }

    @Override
    public void stream(ChatCompletionRequest request, StreamingSession session) {
        client.streamChatCompletion(request, session);
    }
}
