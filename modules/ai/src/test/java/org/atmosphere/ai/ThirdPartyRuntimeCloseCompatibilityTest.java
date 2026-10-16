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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The environment-close hook of the {@link AgentRuntime} and
 * {@link EmbeddingRuntime} SPIs does not collide with a {@code close()} an
 * implementation already declares: a third-party runtime that is also
 * {@link AutoCloseable}, with its own {@code close() throws Exception},
 * compiles against the SPIs unchanged, and its environment calls
 * {@link AgentRuntime#onEnvironmentClose()} /
 * {@link EmbeddingRuntime#onEnvironmentClose()} on it once, never its
 * {@code close()}.
 */
// Resets the static AI facade (AiConfig) between tests: deprecated for
// framework code, kept for code that runs with no application.
@SuppressWarnings("deprecation")
class ThirdPartyRuntimeCloseCompatibilityTest {

    private AiEnvironment application;

    @BeforeEach
    void setUp() {
        AiConfig.resetHostConfig();
        AiConfig.resetForTesting();
        application = AiEnvironment.create(Map.<String, String>of()::get);
    }

    @AfterEach
    void tearDown() {
        application.close();
        ThirdPartyCloseableAgentRuntime.disable(application);
        ThirdPartyCloseableEmbeddingRuntime.disable(application);
        AiConfig.resetHostConfig();
        AiConfig.resetForTesting();
    }

    private ThirdPartyCloseableAgentRuntime agentRuntime() {
        return application.runtimes().resolveAll().stream()
                .filter(ThirdPartyCloseableAgentRuntime.class::isInstance)
                .map(ThirdPartyCloseableAgentRuntime.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the third-party runtime is available"));
    }

    private ThirdPartyCloseableEmbeddingRuntime embeddingRuntime() {
        return application.embeddings().resolveAll().stream()
                .filter(ThirdPartyCloseableEmbeddingRuntime.class::isInstance)
                .map(ThirdPartyCloseableEmbeddingRuntime.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the third-party embedding runtime is available"));
    }

    @Test
    void closingTheEnvironmentCallsTheHookOnceAndNeverTheRuntimesOwnClose() throws Exception {
        ThirdPartyCloseableAgentRuntime.enable(application);
        ThirdPartyCloseableEmbeddingRuntime.enable(application);
        var agent = agentRuntime();
        var embedding = embeddingRuntime();

        application.close();
        application.close();

        assertEquals(0, agent.closes(), "the agent runtime's own close() is the application's to call");
        assertEquals(1, agent.environmentCloses(), "the agent runtime hears of the environment close once");
        assertEquals(0, embedding.closes(), "the embedding runtime's own close() is the application's to call");
        assertEquals(1, embedding.environmentCloses(),
                "the embedding runtime hears of the environment close once");

        agent.close();
        embedding.close();
        assertEquals(1, agent.closes());
        assertEquals(1, embedding.closes());
        assertEquals(1, agent.environmentCloses(), "its own close() does not call the hook");
        assertEquals(1, embedding.environmentCloses(), "its own close() does not call the hook");
    }

    @Test
    void anInstanceTheEnvironmentDoesNotKeepGetsTheHookOnceAndNeverItsOwnClose() {
        ThirdPartyCloseableAgentRuntime.watch(application);
        ThirdPartyCloseableEmbeddingRuntime.watch(application);

        application.runtimes().resolveAll();
        application.embeddings().resolveAll();
        application.close();

        var agents = ThirdPartyCloseableAgentRuntime.attachedTo(application);
        var embeddings = ThirdPartyCloseableEmbeddingRuntime.attachedTo(application);
        assertFalse(agents.isEmpty(), "the agent registry loaded the third-party runtime");
        assertFalse(embeddings.isEmpty(), "the embedding registry loaded the third-party runtime");
        for (var agent : agents) {
            assertEquals(0, agent.closes(), "an unavailable agent runtime's own close() is not called");
            assertEquals(1, agent.environmentCloses(), "an unavailable agent runtime gets the hook once");
        }
        for (var embedding : embeddings) {
            assertEquals(0, embedding.closes(), "an unavailable embedding runtime's own close() is not called");
            assertEquals(1, embedding.environmentCloses(), "an unavailable embedding runtime gets the hook once");
        }
    }
}
