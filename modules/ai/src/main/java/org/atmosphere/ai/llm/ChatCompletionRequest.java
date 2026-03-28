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

import java.util.ArrayList;
import java.util.List;

/**
 * A chat completion request following the OpenAI-compatible format.
 * Works with OpenAI, Gemini, Ollama, Azure OpenAI, and any compatible endpoint.
 */
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        double temperature,
        int maxStreamingTexts,
        boolean jsonMode
) {
    /**
     * Create a simple single-prompt request.
     */
    public static ChatCompletionRequest of(String model, String userPrompt) {
        return new ChatCompletionRequest(model, List.of(ChatMessage.user(userPrompt)), 0.7, 2048, false);
    }

    /**
     * Builder for constructing complex requests.
     */
    public static Builder builder(String model) {
        return new Builder(model);
    }

    public static final class Builder {
        private final String model;
        private final List<ChatMessage> messages = new ArrayList<>();
        private double temperature = 0.7;
        private int maxStreamingTexts = 2048;
        private boolean jsonMode = false;

        private Builder(String model) {
            this.model = model;
        }

        public Builder system(String content) {
            messages.add(ChatMessage.system(content));
            return this;
        }

        public Builder user(String content) {
            messages.add(ChatMessage.user(content));
            return this;
        }

        public Builder assistant(String content) {
            messages.add(ChatMessage.assistant(content));
            return this;
        }

        public Builder message(ChatMessage message) {
            messages.add(message);
            return this;
        }

        public Builder temperature(double newTemperature) {
            this.temperature = newTemperature;
            return this;
        }

        public Builder maxStreamingTexts(int newMaxStreamingTexts) {
            this.maxStreamingTexts = newMaxStreamingTexts;
            return this;
        }

        public Builder jsonMode(boolean newJsonMode) {
            this.jsonMode = newJsonMode;
            return this;
        }

        public ChatCompletionRequest build() {
            return new ChatCompletionRequest(model, List.copyOf(messages), temperature, maxStreamingTexts, jsonMode);
        }
    }
}
