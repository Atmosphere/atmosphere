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
package org.atmosphere.ai.processor;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.ModelRouter;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@code @AiEndpoint} asking for a {@code fallbackStrategy} with a single
 * runtime to route to is told why: a runtime named by
 * {@code atmosphere.ai.runtime} is the only one resolved whatever JARs are on
 * the classpath, so adding JARs is the wrong advice then.
 */
class AiEndpointRoutingWarningTest {

    @Test
    void aNamedRuntimeIsGivenAsTheReasonInsteadOfMissingJars() {
        var warning = AiEndpointProcessor.routingUnavailableWarning(
                ModelRouter.FallbackStrategy.FAILOVER, 1, Optional.of("spring-ai"));

        assertTrue(warning.contains(AiConfig.RUNTIME_PROPERTY + "=spring-ai"), warning);
        assertTrue(warning.contains(AiConfig.RUNTIME_ENV), warning);
        assertFalse(warning.contains("Add more AgentRuntime JARs"), warning);
    }

    @Test
    void withoutANamedRuntimeTheAdviceIsToAddRuntimes() {
        var warning = AiEndpointProcessor.routingUnavailableWarning(
                ModelRouter.FallbackStrategy.FAILOVER, 1, Optional.empty());

        assertTrue(warning.contains("FAILOVER"), warning);
        assertTrue(warning.contains("Add more AgentRuntime JARs"), warning);
    }
}
