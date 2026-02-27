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
 * Emits pre-configured tokens with optional delays and errors.
 */
public class FakeLlmClient implements LlmClient {

    private final String modelName;
    private final String[] tokens;
    private final long delayPerTokenMs;
    private final int errorAfterToken;

    private FakeLlmClient(String modelName, String[] tokens, long delayPerTokenMs, int errorAfterToken) {
        this.modelName = modelName;
        this.tokens = tokens;
        this.delayPerTokenMs = delayPerTokenMs;
        this.errorAfterToken = errorAfterToken;
    }

    /** Create a fake client that emits the given tokens instantly. */
    public static FakeLlmClient withTokens(String modelName, String... tokens) {
        return new FakeLlmClient(modelName, tokens, 0, -1);
    }

    /** Create a fake client that emits tokens with a delay between each. */
    public static FakeLlmClient slow(String modelName, long delayMs, String... tokens) {
        return new FakeLlmClient(modelName, tokens, delayMs, -1);
    }

    /** Create a fake client that errors after emitting a certain number of tokens. */
    public static FakeLlmClient erroring(String modelName, int errorAfterToken, String... tokens) {
        return new FakeLlmClient(modelName, tokens, 0, errorAfterToken);
    }

    /** Create a fake client that emits tokens containing PII data. */
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
            for (int i = 0; i < tokens.length; i++) {
                if (errorAfterToken >= 0 && i >= errorAfterToken) {
                    session.error(new RuntimeException("Simulated error after token " + i));
                    return;
                }
                if (delayPerTokenMs > 0) {
                    Thread.sleep(delayPerTokenMs);
                }
                session.send(tokens[i]);
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
