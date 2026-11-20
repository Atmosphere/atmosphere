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
package org.atmosphere.ai.adk;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The genai client under the shared runner Atmosphere builds: an OkHttp client
 * it owns, whose read timeout is the application's stream idle bound plus the
 * transport grace (a gap between two reads, not genai's {@code callTimeout},
 * which would cut a healthy answer longer than that), and which closes with the
 * application's environment (Correctness Invariant #1).
 */
class AdkGeminiTransportTest {

    private static AiConfig.LlmSettings settings() {
        return new AiConfig.LlmSettings(null, "gemini-2.5-flash", "remote", null, "test-key");
    }

    @Test
    void theReadTimeoutIsTheApplicationsBoundPlusGraceAndNoCallTimeoutIsSet() {
        var environment = AiEnvironment.create(Map.of(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY, "2s")::get);
        try {
            var runtime = new AdkAgentRuntime();
            runtime.attach(environment);
            var runner = runtime.createNativeClient(settings());
            assertNotNull(runner);
            var http = runtime.httpClientOf(runner).orElseThrow();
            assertEquals(2_000 + AdkAgentRuntime.TRANSPORT_GRACE.toMillis(), http.readTimeoutMillis());
            assertEquals(0, http.callTimeoutMillis(), "a call timeout would cut a healthy answer");
        } finally {
            environment.close();
        }
    }

    @Test
    void withTheBoundOffTheClientHasNoReadTimeout() {
        var environment = AiEnvironment.create(Map.of(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY, "0")::get);
        try {
            var runtime = new AdkAgentRuntime();
            runtime.attach(environment);
            var http = runtime.httpClientOf(runtime.createNativeClient(settings())).orElseThrow();
            assertEquals(0, http.readTimeoutMillis());
        } finally {
            environment.close();
        }
    }

    @Test
    void theClientItBuiltClosesWithTheRuntime() {
        var environment = AiEnvironment.create(Map.<String, String>of()::get);
        try {
            var runtime = new AdkAgentRuntime();
            runtime.attach(environment);
            var runner = runtime.createNativeClient(settings());
            var http = runtime.httpClientOf(runner).orElseThrow();
            assertFalse(http.dispatcher().executorService().isShutdown());
            runtime.closeNativeClient(runner);
            assertTrue(http.dispatcher().executorService().isShutdown(),
                    "closeNativeClient must close the genai client the runtime built");
            assertTrue(runtime.httpClientOf(runner).isEmpty());
        } finally {
            environment.close();
        }
    }
}
