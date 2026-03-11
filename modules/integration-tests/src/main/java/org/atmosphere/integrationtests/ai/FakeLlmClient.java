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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.llm.LlmClient;

/**
 * Configurable fake LLM client for E2E testing.
 * Emits pre-configured streaming texts with optional delays and errors.
 */
public class FakeLlmClient implements LlmClient {

    private final String modelName;
    private final String[] texts;
    private final long delayPerTextMs;
    private final int errorAfterText;

    private FakeLlmClient(String modelName, String[] texts, long delayPerTextMs, int errorAfterText) {
        this.modelName = modelName;
        this.texts = texts;
        this.delayPerTextMs = delayPerTextMs;
        this.errorAfterText = errorAfterText;
    }

    /** Create a fake client that emits the given streaming texts instantly. */
    public static FakeLlmClient withTexts(String modelName, String... texts) {
        return new FakeLlmClient(modelName, texts, 0, -1);
    }

    /** Create a fake client that emits streaming texts with a delay between each. */
    public static FakeLlmClient slow(String modelName, long delayMs, String... texts) {
        return new FakeLlmClient(modelName, texts, delayMs, -1);
    }

    /** Create a fake client that errors after emitting a certain number of streaming texts. */
    public static FakeLlmClient erroring(String modelName, int errorAfterText, String... texts) {
        return new FakeLlmClient(modelName, texts, 0, errorAfterText);
    }

    /** Create a fake client that emits streaming texts containing PII data. */
    public static FakeLlmClient withPii(String modelName) {
        return new FakeLlmClient(modelName, new String[]{
                "Contact us at ", "john.doe@example.com", " or call ",
                "555-123-4567", ". ", "SSN is ", "123-45-6789", "."
        }, 0, -1);
    }

    /** Create a fake client that emits harmful content keywords. */
    public static FakeLlmClient withHarmfulContent(String modelName, String harmfulWord) {
        return new FakeLlmClient(modelName, new String[]{
                "Here is some ", harmfulWord, " content."
        }, 0, -1);
    }

    public String modelName() {
        return modelName;
    }

    @Override
    public void streamChatCompletion(ChatCompletionRequest request, StreamingSession session) {
        try {
            session.sendMetadata("model", modelName);
            for (int i = 0; i < texts.length; i++) {
                if (errorAfterText >= 0 && i >= errorAfterText) {
                    session.error(new RuntimeException("Simulated error after text " + i));
                    return;
                }
                if (delayPerTextMs > 0) {
                    Thread.sleep(delayPerTextMs);
                }
                session.send(texts[i]);
            }
            session.complete();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!session.isClosed()) {
                session.error(e);
            }
        }
    }
}
