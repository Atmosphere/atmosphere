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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A fake {@link LlmClient} that returns canned streaming responses without
 * making any network calls. Used when {@code LLM_MODE=fake} for integration
 * testing and demo purposes.
 *
 * <p>Responses are deterministic: each prompt receives the same pre-configured
 * text chunks streamed with a small delay to simulate realistic streaming.</p>
 */
public class FakeLlmClient implements LlmClient {

    private static final Logger logger = LoggerFactory.getLogger(FakeLlmClient.class);

    private static final long DEFAULT_DELAY_MS = 20;
    private static final String[] DEFAULT_RESPONSE = {
            "This", " is", " a", " demo", " response", " from", " Atmosphere", ".",
            " The", " LLM", " is", " running", " in", " fake", " mode", ".",
            " Set", " LLM_MODE=remote", " and", " configure", " an", " API", " key",
            " to", " use", " a", " real", " model", "."
    };

    private final String modelName;
    private final String[] texts;
    private final long delayPerTextMs;

    /**
     * Creates a fake client with the given model name, streaming texts, and delay.
     *
     * @param modelName      the model name to report in metadata
     * @param delayPerTextMs delay in milliseconds between each streamed text
     * @param texts          the text chunks to stream
     */
    public FakeLlmClient(String modelName, long delayPerTextMs, String... texts) {
        this.modelName = modelName;
        this.texts = texts.length > 0 ? texts : DEFAULT_RESPONSE;
        this.delayPerTextMs = delayPerTextMs;
    }

    /**
     * Creates a fake client with default response texts and timing.
     *
     * @param modelName the model name to report in metadata
     */
    public FakeLlmClient(String modelName) {
        this(modelName, DEFAULT_DELAY_MS);
    }

    @Override
    public void streamChatCompletion(ChatCompletionRequest request, StreamingSession session) {
        logger.debug("Fake LLM: model={}, prompt={}", modelName,
                request.messages().isEmpty() ? "(empty)" : request.messages().getLast().content());
        try {
            session.sendMetadata("model", modelName);
            for (var text : texts) {
                if (session.isClosed()) {
                    return;
                }
                if (delayPerTextMs > 0) {
                    Thread.sleep(delayPerTextMs);
                }
                session.send(text);
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
