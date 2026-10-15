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
package org.atmosphere.agent.processor;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AgentRuntimeSelectionException;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@code @Agent} whose {@code atmosphere.ai.runtime} names no available
 * runtime fails its registration with the selection failure, which fails the
 * framework's initialization, as an {@code @AiEndpoint} and a
 * {@code @Coordinator} do (Mode Parity).
 */
class AgentRuntimeSelectionTest {

    @Agent(name = "selection-agent")
    public static class SelectionAgent {
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(AiConfig.RUNTIME_PROPERTY);
        AgentRuntimeResolver.reset();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"}) // Processor.handle takes Class<Object>
    void aRuntimeNamedButNotAvailableFailsTheRegistration() throws Exception {
        System.setProperty(AiConfig.RUNTIME_PROPERTY, "no-such-runtime");
        AgentRuntimeResolver.reset();
        var framework = new AtmosphereFramework();
        framework.init();
        try {
            var failure = assertThrows(AgentRuntimeSelectionException.class, () ->
                    new AgentProcessor().handle(framework, (Class) SelectionAgent.class));
            assertTrue(failure.getMessage().contains(
                    "atmosphere.ai.runtime=no-such-runtime names no available AgentRuntime"), failure.getMessage());
        } finally {
            framework.destroy();
        }
    }
}
