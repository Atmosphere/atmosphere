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
package org.atmosphere.ai.batch;

import org.atmosphere.ai.AiPipeline;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchServingRegistrarTest {

    private static AtmosphereFramework framework(boolean enabled,
                                                 Map<String, Object> properties) {
        var framework = mock(AtmosphereFramework.class);
        var config = mock(AtmosphereConfig.class);
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(config.getInitParameter(eq(BatchServing.ENABLED_PARAM), eq(false)))
                .thenReturn(enabled);
        when(config.getInitParameter(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(config.properties()).thenReturn(properties);
        return framework;
    }

    private static AiPipeline pipeline() {
        return new AiPipeline(null, "sys", "model", null, null, List.of(), List.of(), null);
    }

    @Test
    void disabledByDefaultRegistersNoHandlerAndNoInboundSurface() {
        var properties = new HashMap<String, Object>();
        var framework = framework(false, properties);

        assertFalse(BatchServingRegistrar.registerAgent(framework, "demo", pipeline(), null));
        assertFalse(BatchServingRegistrar.enabled(framework));
        assertTrue(BatchServingRegistrar.executor(framework).isEmpty());
        // Correctness Invariant #6: no new inbound surface without opt-in.
        verify(framework, never()).addAtmosphereHandler(anyString(),
                any(AtmosphereHandler.class), anyList());
        assertTrue(properties.isEmpty());
    }

    @Test
    void firstEnabledRegistrationCreatesTheSharedHandlerOnce() {
        var properties = new HashMap<String, Object>();
        var framework = framework(true, properties);
        try {
            assertTrue(BatchServingRegistrar.registerAgent(framework, "demo", pipeline(), null));
            assertTrue(BatchServingRegistrar.registerAgent(framework, "other", pipeline(), null));
            verify(framework).addAtmosphereHandler(eq(BatchServing.BATCHES_PATH),
                    any(AtmosphereHandler.class), anyList());
            assertTrue(BatchServingRegistrar.executor(framework).isPresent());
            var handler = (BatchHandler) properties.get(BatchServingRegistrar.HANDLER_PROPERTY);
            assertTrue(handler.registeredAgents().containsAll(List.of("demo", "other")));
        } finally {
            var handler = (BatchHandler) properties.get(BatchServingRegistrar.HANDLER_PROPERTY);
            if (handler != null) {
                handler.destroy();
            }
        }
    }
}
