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

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.AiSupport;
import org.atmosphere.ai.StreamingSession;

/**
 * Default {@link AiSupport} implementation that uses the built-in
 * {@link OpenAiCompatibleClient}. Always available, priority {@code 0}.
 *
 * <p>This is the fallback when no framework-specific adapter (Spring AI,
 * LangChain4j, etc.) is on the classpath.</p>
 */
public class BuiltInAiSupport implements AiSupport {

    private AiConfig.LlmSettings settings;

    @Override
    public String name() {
        return "built-in";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        this.settings = settings;
    }

    @Override
    public void stream(AiRequest request, StreamingSession session) {
        var llmSettings = this.settings;
        if (llmSettings == null) {
            llmSettings = AiConfig.get();
        }
        if (llmSettings == null) {
            llmSettings = AiConfig.fromEnvironment();
        }

        var model = request.model() != null ? request.model() : llmSettings.model();

        var builder = ChatCompletionRequest.builder(model);
        if (request.systemPrompt() != null && !request.systemPrompt().isEmpty()) {
            builder.system(request.systemPrompt());
        }
        builder.user(request.message());

        // Apply hints
        var hints = request.hints();
        if (hints.containsKey("temperature")) {
            builder.temperature(((Number) hints.get("temperature")).doubleValue());
        }
        if (hints.containsKey("maxTokens")) {
            builder.maxTokens(((Number) hints.get("maxTokens")).intValue());
        }

        llmSettings.client().streamChatCompletion(builder.build(), session);
    }
}
