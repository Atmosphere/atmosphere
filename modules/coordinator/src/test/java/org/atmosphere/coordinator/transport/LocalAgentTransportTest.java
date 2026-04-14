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
package org.atmosphere.coordinator.transport;

import org.atmosphere.a2a.runtime.LocalDispatchable;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LocalAgentTransportTest {

    @Test
    void isAvailableReturnsFalseWhenHandlerNotInFramework() {
        var framework = mock(AtmosphereFramework.class);
        when(framework.getAtmosphereHandlers()).thenReturn(Map.of());

        var transport = new LocalAgentTransport(framework, "test-agent", "/a2a/test");
        assertFalse(transport.isAvailable());
    }

    @Test
    void sendReturnsFailureWhenHandlerNotFound() {
        var framework = mock(AtmosphereFramework.class);
        when(framework.getAtmosphereHandlers()).thenReturn(Map.of());

        var transport = new LocalAgentTransport(framework, "test-agent", "/a2a/test");
        var result = transport.send("test-agent", "search", Map.of("q", "hello"));

        assertFalse(result.success());
        assertTrue(result.text().contains("Agent not found"));
    }

    @Test
    void streamFallsBackToSendWhenHandlerNotFound() {
        var framework = mock(AtmosphereFramework.class);
        when(framework.getAtmosphereHandlers()).thenReturn(Map.of());

        var transport = new LocalAgentTransport(framework, "test-agent", "/a2a/test");
        var tokens = new ArrayList<String>();
        var completed = new boolean[]{false};

        transport.stream("test-agent", "search", Map.of("q", "hello"),
                tokens::add, () -> completed[0] = true);

        assertTrue(completed[0], "onComplete must be called even on fallback");
        // Fallback to send() which also fails — produces a failure text token
        assertFalse(tokens.isEmpty(), "Fallback send() should produce a token");
        assertTrue(tokens.getFirst().contains("Agent not found"));
    }

    /**
     * Regression — fu2 follow-up #3: construct a transport before the agent
     * handler exists, then register it on a real {@link AtmosphereFramework}
     * afterwards, and verify the FIRST {@code send} call resolves it in-JVM.
     * This is the coordinator-boot race: {@code @Coordinator.resolveTransport}
     * ran before the {@code @Agent} bean's {@code addAtmosphereHandler}
     * completed, so the transport must re-query the framework handler map
     * per call — not cache a lookup at construction time.
     *
     * <p>Uses a real framework (not a mock) because
     * {@code AtmosphereHandlerWrapper} is {@code final} and the coordinator
     * module does not ship mockito-inline; only the framework itself can
     * legitimately wire the wrapper chain. This also makes the test an
     * integration-level regression, not just a mock dance.</p>
     */
    @Test
    void sendResolvesHandlerRegisteredAfterConstruction() {
        var framework = new AtmosphereFramework();
        // Construct BEFORE the handler registers — this reproduces the race
        // where coordinator wiring beats the @Agent bean's @PostConstruct.
        var transport = new LocalAgentTransport(framework, "analyzer",
                "/atmosphere/agent/analyzer/a2a");

        assertFalse(transport.isAvailable(),
                "Transport should report unavailable until the handler registers");

        // Now the @Agent processor's registerA2a path runs (in production
        // this is an A2aHandler wrapping an A2aProtocolHandler; the exact
        // class is irrelevant here — what matters is that it implements
        // both AtmosphereHandler AND LocalDispatchable, which is the SPI
        // contract LocalAgentTransport relies on).
        var captured = new ArrayList<String>();
        var recording = new RecordingDispatchable(captured,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":{\"state\":\"completed\"},"
                        + "\"artifacts\":[{\"parts\":[{\"text\":\"analysis-ok\"}]}]}}");
        framework.addAtmosphereHandler("/atmosphere/agent/analyzer/a2a", recording);

        // First call AFTER registration must land on the local handler.
        var result = transport.send("analyzer", "analyze",
                Map.of("request", "inspect foo"));

        assertTrue(result.success(),
                "Late-registered handler must be resolved on the first send() call. "
                        + "Got: " + result.text());
        assertEquals("analysis-ok", result.text());
        assertEquals(1, captured.size(),
                "Local handler must be invoked exactly once — no A2A fall-through");
        assertTrue(transport.isAvailable(),
                "Transport must report available once the handler is registered");
    }

    /**
     * Regression — fu2 follow-up #3: the coordinator probes multiple
     * candidate paths for each agent (customEndpoint, default
     * {@code /atmosphere/agent/{name}/a2a}, and legacy alt
     * {@code /atmosphere/a2a/{name}}). If the handler registers at the
     * non-primary variant, a single-path transport would miss it. The
     * multi-candidate constructor must find it.
     */
    @Test
    void sendProbesAllCandidatePathsInOrder() {
        var framework = new AtmosphereFramework();

        var transport = new LocalAgentTransport(framework, "analyzer",
                List.of(
                        "/atmosphere/agent/analyzer/a2a",
                        "/atmosphere/a2a/analyzer"));

        // Handler registers at the SECOND (alt) candidate, not the first.
        var captured = new ArrayList<String>();
        var recording = new RecordingDispatchable(captured,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":{\"state\":\"completed\"},"
                        + "\"artifacts\":[{\"parts\":[{\"text\":\"from-alt\"}]}]}}");
        framework.addAtmosphereHandler("/atmosphere/a2a/analyzer", recording);

        var result = transport.send("analyzer", "analyze", Map.of("q", "hi"));

        assertTrue(result.success(),
                "Transport must walk the candidate list and find the alt path. "
                        + "Got: " + result.text());
        assertEquals("from-alt", result.text());
        assertEquals(1, captured.size());
    }

    /**
     * Regression — fu2 follow-up #3: when a handler is registered at a
     * candidate path but does NOT implement {@link LocalDispatchable}, the
     * failure message must surface the wrong-type path (not the generic
     * "agent not found"). This keeps the misconfiguration case distinct
     * from the startup race case.
     */
    @Test
    void sendReportsWrongTypeHandlerDistinctlyFromMissingHandler() {
        var framework = new AtmosphereFramework();
        // Register a PLAIN AtmosphereHandler — no LocalDispatchable mixin.
        framework.addAtmosphereHandler("/atmosphere/agent/analyzer/a2a", new AtmosphereHandler() {
            @Override public void onRequest(AtmosphereResource r) { }
            @Override public void onStateChange(AtmosphereResourceEvent e) { }
            @Override public void destroy() { }
        });

        var transport = new LocalAgentTransport(framework, "analyzer",
                List.of("/atmosphere/agent/analyzer/a2a"));

        var result = transport.send("analyzer", "analyze", Map.of());

        assertFalse(result.success());
        assertTrue(result.text().contains("does not support local dispatch"),
                "Wrong-type failure must be distinguishable from 'not found': " + result.text());
    }

    @Test
    void constructorRejectsEmptyCandidateList() {
        var framework = mock(AtmosphereFramework.class);
        assertThrows(IllegalArgumentException.class,
                () -> new LocalAgentTransport(framework, "x", List.of()));
    }

    // --- helpers ---

    /**
     * Test handler that implements both {@link AtmosphereHandler} and
     * {@link LocalDispatchable}. We capture every dispatched request body
     * so tests can assert the local path was invoked exactly once and no
     * A2A HTTP transport interposed itself.
     */
    private static final class RecordingDispatchable implements AtmosphereHandler, LocalDispatchable {

        private final List<String> captured;
        private final String cannedResponse;

        RecordingDispatchable(List<String> captured, String cannedResponse) {
            this.captured = captured;
            this.cannedResponse = cannedResponse;
        }

        @Override
        public String dispatchLocal(String jsonRpcRequest) {
            captured.add(jsonRpcRequest);
            return cannedResponse;
        }

        @Override
        public void dispatchLocalStreaming(String jsonRpcRequest,
                                            Consumer<String> onToken, Runnable onComplete) {
            captured.add(jsonRpcRequest);
            onToken.accept(cannedResponse);
            onComplete.run();
        }

        @Override public void onRequest(AtmosphereResource r) { }

        @Override public void onStateChange(AtmosphereResourceEvent e) { }

        @Override public void destroy() { }
    }
}
