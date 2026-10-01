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

import org.atmosphere.ai.processor.AiEndpointHandler;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The default AI chat endpoint serves prompts through an
 * {@link AiEndpointHandler}, so its mapping must carry the handler's protocol
 * handshake recorder: without it a prompt posted right after a long-polling
 * protocol handshake names an id the endpoint never saw and is refused with
 * {@code 503} instead of waiting for the client's first poll.
 */
class AtmosphereAiEndpointRegistrarTest {

    @Test
    void defaultEndpointMappingCarriesTheProtocolHandshakeRecorder() {
        var framework = mock(AtmosphereFramework.class);
        var config = mock(AtmosphereConfig.class);
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(framework.getAtmosphereHandlers()).thenReturn(Map.of());
        when(framework.excludedInterceptors()).thenReturn(List.of());
        var properties = new AtmosphereProperties();
        var handlers = new ArrayList<AtmosphereHandler>();
        var interceptors = new ArrayList<List<AtmosphereInterceptor>>();
        when(framework.addAtmosphereHandler(eq(properties.getAi().getPath()),
                any(AtmosphereHandler.class), anyList())).thenAnswer(inv -> {
                    handlers.add(inv.getArgument(1));
                    interceptors.add(inv.getArgument(2));
                    return framework;
                });

        new AtmosphereAiEndpointRegistrar(framework, properties, List.of());
        var hook = ArgumentCaptor.forClass(AtmosphereConfig.StartupHook.class);
        verify(config).startupHook(hook.capture());
        hook.getValue().started(framework);

        assertEquals(1, handlers.size(), "the default AI endpoint must be registered once");
        assertInstanceOf(AiEndpointHandler.class, handlers.get(0));
        assertEquals(1, interceptors.get(0).stream()
                        .filter(Objects::nonNull)
                        .filter(i -> "AiEndpoint protocol handshake recorder".equals(i.toString()))
                        .count(),
                "the default AI endpoint must carry its handshake recorder, got " + interceptors.get(0));
    }
}
