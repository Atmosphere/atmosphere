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
package org.atmosphere.spring.boot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.atmosphere.ai.RagRetrieval;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Spring bridge of the RAG over-fetch + rerank retrieval policy:
 * {@code atmosphere.ai.rag.reranker} / {@code overfetch} /
 * {@code reranker-timeout-ms} land as {@code org.atmosphere.ai.rag.*}
 * framework init-params, which {@code AiEndpointProcessor} resolves per
 * endpoint via {@link RagRetrieval#resolve}. Off by default — the bridged
 * default value must resolve to a disabled policy.
 */
class RagRetrievalBridgeTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withBean(RecordingFramework.class)
            .withConfiguration(AutoConfigurations.of(AtmosphereAiAutoConfiguration.class));

    @Test
    void defaultBridgesRerankerOff() {
        runner.run(context -> {
            var recorded = context.getBean(RecordingFramework.class).recorded;
            assertThat(recorded)
                    .containsEntry(RagRetrieval.RERANKER_KEY, "none")
                    .containsEntry(RagRetrieval.OVERFETCH_KEY, "3")
                    .containsEntry(RagRetrieval.RERANKER_TIMEOUT_MS_KEY, "10000");
        });
    }

    @Test
    void optInBridgesLlmRerankerWithTuning() {
        runner
                .withPropertyValues(
                        "atmosphere.ai.rag.reranker=llm",
                        "atmosphere.ai.rag.overfetch=4",
                        "atmosphere.ai.rag.reranker-timeout-ms=2500")
                .run(context -> {
                    var recorded = context.getBean(RecordingFramework.class).recorded;
                    assertThat(recorded)
                            .containsEntry(RagRetrieval.RERANKER_KEY, "llm")
                            .containsEntry(RagRetrieval.OVERFETCH_KEY, "4")
                            .containsEntry(RagRetrieval.RERANKER_TIMEOUT_MS_KEY, "2500");
                });
    }

    /**
     * Bare framework capturing {@code addInitParameter} calls so the bridge
     * can be asserted without booting a servlet container.
     */
    static class RecordingFramework extends AtmosphereFramework {
        final Map<String, String> recorded = new ConcurrentHashMap<>();

        @Override
        public AtmosphereFramework addInitParameter(String name, String value) {
            recorded.put(name, value);
            return super.addInitParameter(name, value);
        }
    }
}
