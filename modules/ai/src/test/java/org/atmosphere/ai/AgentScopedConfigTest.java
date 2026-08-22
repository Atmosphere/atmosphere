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

import org.atmosphere.ai.llm.BuiltInAgentRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (registre#39): a workspace {@code RUNTIME.md} used to mutate
 * the process-wide {@link AiConfig} singleton — one agent's runtime file
 * reconfigured every other agent in the JVM. Settings are now agent-scoped
 * via {@link AiConfig#configureForAgent}, and the built-in runtime serves a
 * scoped agent from its own settings and client.
 */
class AgentScopedConfigTest {

    @AfterEach
    void tearDown() {
        AiConfig.resetAgent("scoped-agent");
        AiConfig.resetForTesting();
    }

    @Test
    void configureForAgentNeverTouchesTheProcessWideSingleton() {
        AiConfig.resetForTesting();
        AiConfig.configureForAgent("scoped-agent", "fake", "scoped-model", null, null);

        assertNull(AiConfig.get(), "the global singleton must stay unconfigured");
        assertEquals("scoped-model", AiConfig.forAgent("scoped-agent").model());
        assertEquals("scoped-model", AiConfig.agentScoped("scoped-agent").model());
        assertNull(AiConfig.agentScoped("other-agent"),
                "no scope means no scoped settings — callers fall back explicitly");

        AiConfig.resetAgent("scoped-agent");
        assertNull(AiConfig.agentScoped("scoped-agent"));
    }

    @Test
    void agentScopedSettingsDriveThatAgentsDispatch() {
        AiConfig.configure("fake", "global-model", null, null);
        AiConfig.configureForAgent("scoped-agent", "fake", "scoped-model", null, null);

        var models = new CopyOnWriteArrayList<Object>();
        var session = new StreamingSession() {
            @Override public String sessionId() { return "scoped-test"; }
            @Override public void send(String text) { }
            @Override public void sendMetadata(String key, Object value) {
                if ("model".equals(key)) {
                    models.add(value);
                }
            }
            @Override public void progress(String message) { }
            @Override public void complete() { }
            @Override public void complete(String summary) { }
            @Override public void error(Throwable t) { }
            @Override public boolean isClosed() { return false; }
        };
        var context = new AgentExecutionContext(
                "hi", null, null,
                "scoped-agent", null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(),
                null, null);

        new BuiltInAgentRuntime().execute(context, session);

        assertTrue(models.contains("scoped-model"),
                "the scoped agent must be served by its scoped settings: " + models);
        assertFalse(models.contains("global-model"),
                "the process-wide model must not answer for a scoped agent");
    }
}
