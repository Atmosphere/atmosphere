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
package org.atmosphere.samples.springboot.ragchat;

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the rag-chat runtime selection.
 *
 * <p>This sample originally depended on {@code atmosphere-spring-ai}, whose
 * {@code SpringAiAgentRuntime} (priority &gt; built-in) won runtime resolution
 * and drove the chat through the openai-java streaming path. That path rejects
 * the Gemini OpenAI-compatible endpoint's tool-call deltas (missing {@code
 * index}) with {@code OpenAIInvalidDataException}, so every RAG answer crashed.</p>
 *
 * <p>The fix dropped the {@code atmosphere-spring-ai} runtime adapter (keeping
 * the raw Spring AI {@code VectorStore} for retrieval) so the built-in runtime
 * — which tolerates Gemini's tool-call streaming — drives the chat. This test
 * pins that: it fails the moment the spring-ai adapter is back on the
 * classpath and steals runtime resolution again.</p>
 */
class RuntimeSelectionTest {

    @Test
    void chatResolvesToBuiltInRuntimeNotSpringAi() {
        // Rescan the ServiceLoader against this module's runtime classpath.
        AgentRuntimeResolver.reset();

        // The headline regression guard: the spring-ai runtime adapter must be
        // absent from the classpath, so it cannot win resolution and route chat
        // through the openai-java streaming path that crashes on Gemini's
        // tool-call deltas. (Which non-spring-ai runtime wins — built-in with a
        // key, demo without — is config-dependent and not the point here.)
        boolean noSpringAiRuntime = AgentRuntimeResolver.resolveAll().stream()
                .map(r -> r.getClass().getName())
                .noneMatch(n -> n.contains("SpringAiAgentRuntime"));
        assertTrue(noSpringAiRuntime,
                "SpringAiAgentRuntime must not be resolvable for rag-chat — the "
                        + "atmosphere-spring-ai adapter must stay off the classpath");

        AgentRuntime resolved = AgentRuntimeResolver.resolve();
        assertTrue(!resolved.getClass().getName().contains("SpringAiAgentRuntime"),
                "resolved chat runtime must not be the Spring AI adapter, was: "
                        + resolved.getClass().getName());
    }
}
