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

import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.llm.FakeLlmClient;
import org.atmosphere.cpr.AtmosphereConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The LLM summarizing compaction and the LLM reranker an application's
 * configuration selects run on that application's runtime, whichever thread
 * they are called on. Before, they resolved the runtime through the static
 * {@code AgentRuntimeResolver}, which on a thread bound to no application,
 * with two applications running, is the process default's: no one
 * application's settings.
 */
class ApplicationRuntimeDefaultsTest {

    private static final List<ChatMessage> HISTORY = List.of(
            ChatMessage.user("hello"),
            ChatMessage.assistant("hi, how can I help?"));

    private final List<AiEnvironment> environments = new ArrayList<>();

    @AfterEach
    void tearDown() {
        environments.forEach(AiEnvironment::close);
        AiConfig.resetHostConfig();
        AiConfigTestSupport.resetSettings();
    }

    /**
     * One application: a framework configuration with {@code initParams},
     * whose environment's model replies {@code reply} to every prompt.
     */
    private AtmosphereConfig application(String reply, Map<String, String> initParams) {
        var config = mock(AtmosphereConfig.class);
        when(config.properties()).thenReturn(new ConcurrentHashMap<>());
        initParams.forEach((key, value) -> when(config.getInitParameter(key)).thenReturn(value));
        when(config.getInitParameter(RagRetrieval.OVERFETCH_KEY, RagRetrieval.DEFAULT_OVERFETCH))
                .thenReturn(RagRetrieval.DEFAULT_OVERFETCH);
        var environment = AiEnvironment.create(config, Map.of(AiConfig.RUNTIME_PROPERTY, "built-in")::get);
        environments.add(environment);
        environment.configure("fake", "model", null, null);
        environment.installClient(new FakeLlmClient("model", 0, reply));
        return config;
    }

    @Test
    void theSummarizingCompactionSummarizesOnItsApplicationsRuntime() {
        var a = application("summary from application A",
                Map.of(CompactionConfig.STRATEGY_KEY, "summarizing"));
        application("summary from application B", Map.of());

        var compaction = assertInstanceOf(LlmSummarizingCompaction.class, CompactionConfig.resolve(a));

        assertEquals("summary from application A", compaction.summarize(HISTORY),
                "the compaction of application A must summarize through A's runtime");
    }

    @Test
    void theLlmRerankerScoresOnItsApplicationsRuntime() {
        var a = application("[1,0]", Map.of(RagRetrieval.RERANKER_KEY, RagRetrieval.RERANKER_LLM));
        application("[0,1]", Map.of());
        var documents = List.of(
                new ContextProvider.Document("first", "doc-0", 0.9),
                new ContextProvider.Document("second", "doc-1", 0.8));

        var reranked = RagRetrieval.resolve(a, null).rerank("which one?", documents, 2);

        assertEquals(List.of("second", "first"),
                reranked.stream().map(ContextProvider.Document::content).toList(),
                "application A's runtime ranks the second document first");
    }
}
