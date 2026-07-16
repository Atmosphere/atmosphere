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

import org.atmosphere.agent.test.FakeAtmosphereAgentHandler;
import org.atmosphere.ai.test.FakeAtmosphereAiHandler;
import org.atmosphere.config.managed.ManagedAtmosphereHandler;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereHandlerWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@code /api/console/info}'s {@code mode} field — the auto-detection
 * that keeps the bundled Console UI's empty-state copy honest for samples
 * whose Console-mounted endpoint is a plain {@code @ManagedService} chat
 * (e.g. {@code spring-boot-mcp-server}, {@code spring-boot-otel-chat}) vs.
 * a real AI endpoint ({@code @AiEndpoint} / {@code @Agent} /
 * {@code @Coordinator}).
 *
 * <p>Pre-fix, the Console always rendered "AI assistant" copy regardless of
 * the underlying handler — misleading for broadcast-shaped samples. The
 * detection logic lives in {@code AtmosphereConsoleInfoEndpoint#detectMode},
 * is class-name based (so it doesn't drag in compile-time deps on
 * {@code modules/ai} or {@code modules/agent}), and falls open to
 * {@code "ai"} on any error so the existing AI-Console default copy still
 * loads when the framework hasn't initialised yet.</p>
 */
class AtmosphereConsoleInfoEndpointModeTest {

    @Test
    void aiEndpointHandlerYieldsAiMode() {
        var info = newEndpoint(Map.of("/atmosphere/ai-chat", wrapperFor(new FakeAtmosphereAiHandler())));
        var result = info.info();
        assertThat(result).containsEntry("mode", "ai");
        // Default subtitle uses the runtime label when in ai mode (no override).
        assertThat((String) result.get("subtitle")).startsWith("Runtime: ");
    }

    @Test
    void agentHandlerPackageYieldsAiMode() {
        var props = new AtmosphereProperties();
        props.setConsoleEndpoint("/atmosphere/agent/dispatch");
        var info = newEndpoint(props,
                Map.of("/atmosphere/agent/dispatch", wrapperFor(new FakeAtmosphereAgentHandler())));
        assertThat(info.info()).containsEntry("mode", "ai");
    }

    @Test
    void a2aProtocolBridgeYieldsAiMode() {
        // A2aHandler bridges an AI agent over JSON-RPC — the console must
        // render assistant copy + streaming indicator, not broadcast copy.
        var props = new AtmosphereProperties();
        props.setConsoleEndpoint("/atmosphere/a2a");
        var info = newEndpoint(props,
                Map.of("/atmosphere/a2a", wrapperFor(new org.atmosphere.a2a.test.FakeA2aHandler())));
        assertThat(info.info()).containsEntry("mode", "ai");
    }

    @Test
    void managedAtmosphereHandlerYieldsBroadcastMode() {
        var info = newEndpoint(Map.of("/atmosphere/ai-chat", wrapperFor(mock(ManagedAtmosphereHandler.class))));
        var result = info.info();
        assertThat(result).containsEntry("mode", "broadcast");
        // Mode-aware default subtitle replaces the "Runtime: " label so
        // broadcast samples don't borrow the AI runtime branding.
        assertThat(result).containsEntry("subtitle", "Multi-client broadcast chat");
    }

    @Test
    void explicitSubtitleOverridesBroadcastDefault() {
        // Per-sample subtitle (atmosphere.console-subtitle) wins over the
        // mode-aware default — pins the existing override path doesn't
        // regress when broadcast is detected.
        var props = new AtmosphereProperties();
        props.setConsoleSubtitle("MCP server demo");
        var info = newEndpoint(props,
                Map.of("/atmosphere/ai-chat", wrapperFor(mock(ManagedAtmosphereHandler.class))));
        var result = info.info();
        assertThat(result).containsEntry("mode", "broadcast");
        assertThat(result).containsEntry("subtitle", "MCP server demo");
    }

    @Test
    void noWrapperRegisteredFallsBackToAiMode() {
        // Endpoint resolves to a path that has no registered handler yet —
        // happens on cold-start before the @AiEndpoint scanner has run.
        // Defaulting to "ai" preserves the previous Console behaviour.
        var info = newEndpoint(Map.of());
        assertThat(info.info()).containsEntry("mode", "ai");
    }

    @Test
    void transportDefaultsToAtmosphereWhenUnset() {
        // Every AI/broadcast sample leaves atmosphere.console-transport at its
        // default, so the console loads its built-in WS transport.
        assertThat(newEndpoint(Map.of()).info()).containsEntry("transport", "atmosphere");
    }

    @Test
    void transportReflectsConfiguredForeignAdapter() {
        // A sample exposing a foreign transport opts in; the console lazy-loads
        // the matching adapter. Each recognized value is passed through.
        for (var t : java.util.List.of("grpc", "a2a", "ag-ui")) {
            var props = new AtmosphereProperties();
            props.setConsoleTransport(t);
            assertThat(newEndpoint(props, Map.of()).info())
                    .as("transport %s", t)
                    .containsEntry("transport", t);
        }
    }

    @Test
    void transportNormalizesCaseAndWhitespace() {
        var props = new AtmosphereProperties();
        props.setConsoleTransport("  AG-UI  ");
        assertThat(newEndpoint(props, Map.of()).info()).containsEntry("transport", "ag-ui");
    }

    @Test
    void unknownTransportFallsBackToAtmosphere() {
        // Runtime Truth (#5): a typo must not make the console try to load an
        // adapter that doesn't exist and silently fail to connect.
        var props = new AtmosphereProperties();
        props.setConsoleTransport("websocket-turbo");
        assertThat(newEndpoint(props, Map.of()).info()).containsEntry("transport", "atmosphere");
    }

    @Test
    void roomAndEndpointOptionsEmittedFromConfig() {
        var props = new AtmosphereProperties();
        props.setConsoleRoom("lobby");
        props.setConsoleEndpoints("Math=/atmosphere/classroom/math,Code=/atmosphere/classroom/code");
        var result = newEndpoint(props, Map.of()).info();
        assertThat(result).containsEntry("room", "lobby");
        assertThat(result.get("endpointOptions")).isEqualTo(java.util.List.of(
                Map.of("label", "Math", "endpoint", "/atmosphere/classroom/math"),
                Map.of("label", "Code", "endpoint", "/atmosphere/classroom/code")));
    }

    @Test
    void malformedEndpointOptionsAreDroppedNotEchoed() {
        // Boundary Safety: bad pairs (no '=', blank label, relative path) are
        // dropped; an all-bad config omits the field entirely.
        assertThat(AtmosphereConsoleInfoEndpoint.parseEndpointOptions(
                "NoEquals,=/x,Label=relative,Ok=/fine"))
                .isEqualTo(java.util.List.of(Map.of("label", "Ok", "endpoint", "/fine")));
        var props = new AtmosphereProperties();
        props.setConsoleEndpoints("broken");
        assertThat(newEndpoint(props, Map.of()).info()).doesNotContainKey("endpointOptions");
    }

    @Test
    void webTransportOmittedWhenNoSidecarIsRunning() {
        // The block is Runtime Truth: absent unless the HTTP/3 server bean
        // exists AND reports running — a mock context provides no bean, so
        // the console must fall back to plain WebSocket.
        assertThat(newEndpoint(Map.of()).info()).doesNotContainKey("webTransport");
    }

    @Test
    void capabilityFlagsEmittedAsBooleansDefaultFalse() {
        // The frontend gates the Interactions/Validation tabs on these flags
        // instead of probing (which 404s on samples without those modules).
        // With no beans wired they must be present and false.
        var result = newEndpoint(Map.of()).info();
        assertThat(result).containsEntry("hasInteractions", false);
        assertThat(result).containsEntry("hasVerifier", false);
    }

    private AtmosphereConsoleInfoEndpoint newEndpoint(Map<String, AtmosphereHandlerWrapper> handlers) {
        return newEndpoint(new AtmosphereProperties(), handlers);
    }

    private AtmosphereConsoleInfoEndpoint newEndpoint(
            AtmosphereProperties properties,
            Map<String, AtmosphereHandlerWrapper> handlers) {
        var framework = mock(AtmosphereFramework.class);
        when(framework.getAtmosphereHandlers()).thenReturn(handlers);
        // These tests pin mode/subtitle, not the capability flags; stub the
        // context so hasInteractions/hasVerifier resolve to false (no beans).
        var context = mock(ApplicationContext.class);
        when(context.getClassLoader()).thenReturn(getClass().getClassLoader());
        when(context.getBeanNamesForType(any(Class.class), anyBoolean(), anyBoolean()))
                .thenReturn(new String[0]);
        return new AtmosphereConsoleInfoEndpoint(properties, framework, context);
    }

    private AtmosphereHandlerWrapper wrapperFor(AtmosphereHandler handler) {
        var wrapper = mock(AtmosphereHandlerWrapper.class);
        when(wrapper.atmosphereHandler()).thenReturn(handler);
        return wrapper;
    }
}
