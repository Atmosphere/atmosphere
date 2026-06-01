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
package org.atmosphere.ai.langchain4j;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.llm.OpenAiCompatibleClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that native LangChain4j {@link ChatModelListener}s registered with
 * {@link LangChain4jAgentRuntime} are attached to the model the runtime
 * auto-builds — the integration point langchain4j-opentelemetry and
 * langchain4j-micrometer-metrics depend on.
 */
class LangChain4jChatModelListenerTest {

    @AfterEach
    void cleanup() {
        LangChain4jAgentRuntime.clearChatModelListeners();
    }

    private static AiConfig.LlmSettings remoteSettings() {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("https://example.test/v1")
                .apiKey("test-key")
                .build();
        return new AiConfig.LlmSettings(client, "gpt-4o-mini", "remote", "https://example.test/v1");
    }

    @Test
    void registeredListenerIsAttachedToAutoBuiltModel() {
        var marker = new ChatModelListener() { };
        LangChain4jAgentRuntime.registerChatModelListener(marker);

        var model = new LangChain4jAgentRuntime().createNativeClient(remoteSettings());

        assertNotNull(model, "auto-built model should be created when an API key is present");
        var openAi = assertInstanceOf(OpenAiStreamingChatModel.class, model);
        assertTrue(openAi.listeners().contains(marker),
                "registered ChatModelListener must be attached to the auto-built model");
    }

    @Test
    void nullRegistrationIsIgnored() {
        LangChain4jAgentRuntime.registerChatModelListener(null);
        assertTrue(LangChain4jAgentRuntime.chatModelListeners().isEmpty(),
                "null listener must not be registered");
    }

    @Test
    void modelWithNoRegisteredListenersHasNoneAttached() {
        var model = new LangChain4jAgentRuntime().createNativeClient(remoteSettings());

        var openAi = assertInstanceOf(OpenAiStreamingChatModel.class, model);
        assertFalse(openAi.listeners().stream()
                        .anyMatch(l -> l.getClass().getName().startsWith("org.atmosphere")),
                "no Atmosphere-side listener should be attached when none is registered");
    }

    @Test
    void registrySnapshotIsImmutableCopy() {
        var marker = new ChatModelListener() { };
        LangChain4jAgentRuntime.registerChatModelListener(marker);

        var snapshot = LangChain4jAgentRuntime.chatModelListeners();
        assertTrue(snapshot.contains(marker));
        // A later registration must not mutate an already-handed-out snapshot.
        LangChain4jAgentRuntime.registerChatModelListener(new ChatModelListener() { });
        assertTrue(snapshot.size() == 1, "snapshot must be an independent copy");
    }
}
