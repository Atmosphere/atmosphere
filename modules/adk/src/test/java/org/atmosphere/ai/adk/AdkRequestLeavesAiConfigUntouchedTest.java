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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.CollectingSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-request runner the ADK runtime builds for a request reads the LLM
 * settings; it never writes them. With nothing configured, it falls back to the
 * {@code LLM_*} environment for its own use and leaves {@link AiConfig#get()}
 * as it found it: publishing the fallback would hand every other agent and
 * application in the JVM this request's environment-derived settings.
 *
 * <p>Relies on a fresh JVM with nothing configured: surefire runs each test
 * class in its own fork ({@code reuseForks=false}), and the precondition is
 * asserted.</p>
 */
// Exercises the static AI facade (the AiConfig / AgentRuntimeResolver readers
// or an adapter's process-wide binder): deprecated for framework code, kept for
// code that runs with no application.
@SuppressWarnings("deprecation")
class AdkRequestLeavesAiConfigUntouchedTest {

    @Test
    void perRequestRunnerDoesNotPublishEnvironmentSettings() {
        assertTrue(AiConfig.get() == null, "precondition: no LLM settings configured in this JVM");

        var runtime = new AdkAgentRuntime();
        var context = new AgentExecutionContext(
                "Hi", "You are helpful", "gemini-2.5-flash",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
        assertNotNull(runtime.buildRequestRunner(context, new CollectingSession()));
        assertNotNull(runtime.buildRequestRunner(context, new CollectingSession()));

        // assertTrue, not assertNull: the failure message must not print the
        // settings, whose apiKey component may hold an ambient credential.
        assertTrue(AiConfig.get() == null, "an ADK request must not configure the JVM-wide LLM settings");
    }
}
