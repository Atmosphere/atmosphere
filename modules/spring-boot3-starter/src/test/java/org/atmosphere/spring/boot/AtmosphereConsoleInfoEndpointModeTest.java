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

import java.util.LinkedHashMap;
import java.util.Map;

import org.atmosphere.a2a.test.FakeA2aHandler;
import org.atmosphere.agent.test.FakeAtmosphereAgentHandler;
import org.atmosphere.ai.test.FakeAtmosphereAiHandler;
import org.atmosphere.config.managed.ManagedAtmosphereHandler;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereHandlerWrapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@code /api/console/info}'s {@code mode} field on the boot3 starter, in
 * parity with the Spring Boot 4 starter and Quarkus: {@code ai} for an
 * AI-shaped handler, {@code broadcast} for a plain {@code @ManagedService}, a
 * path-template service classified by its template before any client connects,
 * and the mode-aware default subtitle.
 */
class AtmosphereConsoleInfoEndpointModeTest {

    @Test
    void aiEndpointHandlerYieldsAiMode() {
        var result = newEndpoint(new AtmosphereProperties(),
                Map.of("/atmosphere/ai-chat", wrapperFor(new FakeAtmosphereAiHandler()))).info();
        assertThat(result).containsEntry("mode", "ai");
        assertThat((String) result.get("subtitle")).startsWith("Runtime: ");
    }

    @Test
    void agentHandlerPackageYieldsAiMode() {
        var result = newEndpoint(new AtmosphereProperties(),
                Map.of("/atmosphere/agent/dispatch", wrapperFor(new FakeAtmosphereAgentHandler()))).info();
        assertThat(result).containsEntry("endpoint", "/atmosphere/agent/dispatch");
        assertThat(result).containsEntry("mode", "ai");
    }

    @Test
    void a2aProtocolBridgeYieldsAiMode() {
        var props = new AtmosphereProperties();
        props.getAi().setPath("/atmosphere/a2a");
        var result = newEndpoint(props,
                Map.of("/atmosphere/a2a", wrapperFor(new FakeA2aHandler()))).info();
        assertThat(result).containsEntry("mode", "ai");
    }

    @Test
    void managedAtmosphereHandlerYieldsBroadcastMode() {
        var result = newEndpoint(new AtmosphereProperties(),
                Map.of("/atmosphere/ai-chat", wrapperFor(mock(ManagedAtmosphereHandler.class)))).info();
        assertThat(result).containsEntry("mode", "broadcast");
        assertThat(result).containsEntry("subtitle", "Multi-client broadcast chat");
    }

    @Test
    void explicitSubtitleOverridesBroadcastDefault() {
        var props = new AtmosphereProperties();
        props.setConsoleSubtitle("MCP server demo");
        var result = newEndpoint(props,
                Map.of("/atmosphere/ai-chat", wrapperFor(mock(ManagedAtmosphereHandler.class)))).info();
        assertThat(result).containsEntry("mode", "broadcast");
        assertThat(result).containsEntry("subtitle", "MCP server demo");
    }

    @Test
    void pathTemplateManagedServiceYieldsBroadcastModeBeforeAnyClientConnects() {
        // A templated @ManagedService registers only its template until the first
        // request for a concrete path: the console mounts before that request.
        var props = new AtmosphereProperties();
        props.getAi().setPath("/atmosphere/rooms/general");
        var result = newEndpoint(props,
                Map.of("/atmosphere/rooms/{room}", wrapperFor(mock(ManagedAtmosphereHandler.class)))).info();
        assertThat(result).containsEntry("mode", "broadcast");
    }

    @Test
    void pathTemplateAiEndpointResolvesToItsHandler() {
        // "ai" is also the answer when nothing matches, so pin the handler the
        // template resolves to.
        var aiWrapper = wrapperFor(new FakeAtmosphereAiHandler());
        Map<String, AtmosphereHandlerWrapper> handlers = Map.of("/atmosphere/classroom/{room}", aiWrapper);
        assertThat(AtmosphereConsoleInfoEndpoint.templateWrapper(handlers, "/atmosphere/classroom/math"))
                .isSameAs(aiWrapper);

        var props = new AtmosphereProperties();
        props.getAi().setPath("/atmosphere/classroom/math");
        assertThat(newEndpoint(props, handlers).info()).containsEntry("mode", "ai");
    }

    @Test
    void pathTemplateDoesNotResolveAPathWithMoreSegments() {
        Map<String, AtmosphereHandlerWrapper> handlers =
                Map.of("/atmosphere/rooms/{room}", wrapperFor(mock(ManagedAtmosphereHandler.class)));
        assertThat(AtmosphereConsoleInfoEndpoint.templateWrapper(handlers, "/atmosphere/rooms/general/admin"))
                .isNull();
    }

    @Test
    void managedServiceOffTheDefaultPathIsDiscoveredWithDefaultProperties() {
        // Default ai.path (/atmosphere/ai-chat) is not registered: the console
        // falls back to the @ManagedService actually mounted, as on Spring Boot 4.
        var result = newEndpoint(new AtmosphereProperties(),
                Map.of("/atmosphere/chat", wrapperFor(mock(ManagedAtmosphereHandler.class)))).info();
        assertThat(result).containsEntry("endpoint", "/atmosphere/chat");
        assertThat(result).containsEntry("mode", "broadcast");
        assertThat(result).containsEntry("subtitle", "Multi-client broadcast chat");
    }

    @Test
    void pathTemplateManagedServiceIsDiscoveredWithDefaultProperties() {
        var result = newEndpoint(new AtmosphereProperties(),
                Map.of("/atmosphere/rooms/{room}", wrapperFor(mock(ManagedAtmosphereHandler.class)))).info();
        assertThat(result).containsEntry("endpoint", "/atmosphere/rooms/{room}");
        assertThat(result).containsEntry("mode", "broadcast");
    }

    @Test
    void configuredAiPathWinsOverOtherRegisteredHandlers() {
        // Insertion order puts the competing handlers first, so the configured
        // path must win on purpose, not by iteration luck.
        var handlers = new LinkedHashMap<String, AtmosphereHandlerWrapper>();
        handlers.put("/atmosphere/agent/dispatch", wrapperFor(new FakeAtmosphereAgentHandler()));
        handlers.put("/atmosphere/chat", wrapperFor(mock(ManagedAtmosphereHandler.class)));
        handlers.put("/atmosphere/ai-chat", wrapperFor(new FakeAtmosphereAiHandler()));
        var result = newEndpoint(new AtmosphereProperties(), handlers).info();
        assertThat(result).containsEntry("endpoint", "/atmosphere/ai-chat");
        assertThat(result).containsEntry("mode", "ai");
    }

    @Test
    void agentHandlerWinsOverAManagedServiceWhenAiPathIsNotRegistered() {
        var handlers = new LinkedHashMap<String, AtmosphereHandlerWrapper>();
        handlers.put("/atmosphere/chat", wrapperFor(mock(ManagedAtmosphereHandler.class)));
        handlers.put("/atmosphere/agent/dispatch", wrapperFor(new FakeAtmosphereAgentHandler()));
        var result = newEndpoint(new AtmosphereProperties(), handlers).info();
        assertThat(result).containsEntry("endpoint", "/atmosphere/agent/dispatch");
        assertThat(result).containsEntry("mode", "ai");
    }

    @Test
    void adminHandlerIsNeverPickedAsTheConsoleEndpoint() {
        var result = newEndpoint(new AtmosphereProperties(),
                Map.of("/atmosphere/admin/events", wrapperFor(mock(ManagedAtmosphereHandler.class)))).info();
        assertThat(result).containsEntry("endpoint", "/atmosphere/ai-chat");
        assertThat(result).containsEntry("mode", "ai");
    }

    @Test
    void initializedFrameworkServingNoHandlerAdvertisesNoChatEndpoint() {
        // Parity with the Spring Boot 4 starter: an initialized framework that
        // serves no handler names no endpoint, instead of the /atmosphere/ai-chat
        // default nothing answers on (Runtime Truth).
        var result = newInitializedEndpoint(new AtmosphereProperties(), Map.of()).info();
        assertThat(result).containsEntry("hasChat", false);
        assertThat(result).doesNotContainKey("endpoint");
        assertThat(result).doesNotContainKey("mode");
        assertThat(result).containsEntry("subtitle", "No chat endpoint");
    }

    @Test
    void initializedFrameworkServingTheChatHandlerAdvertisesIt() {
        var result = newInitializedEndpoint(new AtmosphereProperties(),
                Map.of("/atmosphere/ai-chat", wrapperFor(new FakeAtmosphereAiHandler()))).info();
        assertThat(result).containsEntry("hasChat", true);
        assertThat(result).containsEntry("endpoint", "/atmosphere/ai-chat");
        assertThat(result).containsEntry("mode", "ai");
    }

    @Test
    void noWrapperRegisteredFallsBackToAiMode() {
        assertThat(newEndpoint(new AtmosphereProperties(), Map.of()).info())
                .containsEntry("mode", "ai");
    }

    private AtmosphereConsoleInfoEndpoint newEndpoint(AtmosphereProperties properties,
                                                      Map<String, AtmosphereHandlerWrapper> handlers) {
        var framework = mock(AtmosphereFramework.class);
        when(framework.getAtmosphereHandlers()).thenReturn(handlers);
        return new AtmosphereConsoleInfoEndpoint(properties, framework);
    }

    private AtmosphereConsoleInfoEndpoint newInitializedEndpoint(AtmosphereProperties properties,
                                                                 Map<String, AtmosphereHandlerWrapper> handlers) {
        var framework = mock(AtmosphereFramework.class);
        when(framework.getAtmosphereHandlers()).thenReturn(handlers);
        when(framework.initialized()).thenReturn(true);
        return new AtmosphereConsoleInfoEndpoint(properties, framework);
    }

    private AtmosphereHandlerWrapper wrapperFor(AtmosphereHandler handler) {
        var wrapper = mock(AtmosphereHandlerWrapper.class);
        when(wrapper.atmosphereHandler()).thenReturn(handler);
        return wrapper;
    }
}
