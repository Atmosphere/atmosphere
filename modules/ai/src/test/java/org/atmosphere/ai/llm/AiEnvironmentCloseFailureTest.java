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
import org.atmosphere.ai.AiConfigTestSupport;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.CloseProbeAgentRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closing an {@link AiEnvironment} finishes every step even when a runtime's
 * close fails (Correctness Invariant 2): the environment is marked closed
 * before the first step, so a close that stopped half way could never be
 * retried. A runtime compiled before {@code AgentRuntime.close()} existed may
 * throw a checked exception from it, and any close may throw an {@link Error}.
 */
// Resets the static AI facade (AiConfig) between tests: deprecated for
// framework code, kept for code that runs with no application.
@SuppressWarnings("deprecation")
class AiEnvironmentCloseFailureTest {

    private static final String URL = "http://127.0.0.1:1/v1";

    private AiEnvironment application;

    @BeforeEach
    void setUp() {
        AiConfig.resetHostConfig();
        AiConfigTestSupport.resetSettings();
    }

    @AfterEach
    void tearDown() {
        if (application != null) {
            CloseProbeAgentRuntime.disable(application);
        }
        AiConfig.resetHostConfig();
        AiConfigTestSupport.resetSettings();
    }

    /** What the environment built: its settings' HTTP client and its embedding runtime's. */
    private record Built(HttpClient settingsHttp, HttpClient embeddingHttp) {
    }

    private Built application(Throwable closeFailure) {
        application = AiEnvironment.create(
                Map.of(AiConfig.EMBEDDING_MODEL_PROPERTY, "text-embedding-3-small")::get);
        var settings = application.configure("remote", "gpt-4o-mini", "k", URL);
        application.configureForAgent("agent", "remote", "gpt-4o", "k", URL, null);
        CloseProbeAgentRuntime.enable(application);
        CloseProbeAgentRuntime.failClose(application, closeFailure);
        var probe = application.runtimes().resolveAll().stream()
                .filter(CloseProbeAgentRuntime.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertInstanceOf(CloseProbeAgentRuntime.class, probe);
        var embedding = assertInstanceOf(BuiltInEmbeddingRuntime.class,
                application.embeddings().resolve().orElseThrow());
        return new Built(((OpenAiCompatibleClient) settings.client()).httpClient(), embedding.httpClient());
    }

    private void assertEveryStepRan(Built built) throws InterruptedException {
        assertTrue(built.embeddingHttp().awaitTermination(Duration.ofSeconds(5)),
                "the embedding runtime is closed after the runtime that failed");
        assertTrue(built.settingsHttp().awaitTermination(Duration.ofSeconds(5)),
                "the client of the settings the environment built is shut down");
        assertNull(application.agentScoped("agent"), "the agent settings are dropped");
    }

    @Test
    void aCheckedExceptionFromARuntimeCloseDoesNotStopTheClose() throws Exception {
        var built = application(new IOException("old SPI close() throws Exception"));

        assertDoesNotThrow(application::close, "a failed runtime close is logged");

        assertEveryStepRan(built);
    }

    @Test
    void anErrorFromARuntimeCloseIsRethrownOnceEveryStepRan() throws Exception {
        var failure = new Error("runtime close failed");
        var built = application(failure);

        var thrown = assertThrows(Error.class, application::close);

        assertSame(failure, thrown);
        assertEveryStepRan(built);
    }
}
