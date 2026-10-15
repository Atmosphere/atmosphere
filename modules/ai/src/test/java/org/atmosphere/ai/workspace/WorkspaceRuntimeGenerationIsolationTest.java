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
package org.atmosphere.ai.workspace;

import com.sun.net.httpserver.HttpServer;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.llm.BuiltInAgentRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two agents whose {@code RUNTIME.md} files set different generation values
 * each send their own on the wire, and neither reaches any other settings built
 * in the JVM: the values are scoped to the settings of the workspace that
 * declares them, never written to the JVM system properties (which every
 * later {@link AiConfig#configure} would read, at the highest precedence).
 */
// Exercises the static AI facade (the AiConfig / AgentRuntimeResolver readers
// or an adapter's process-wide binder): deprecated for framework code, kept for
// code that runs with no application.
@SuppressWarnings("deprecation")
class WorkspaceRuntimeGenerationIsolationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SSE = """
            data: {"choices":[{"delta":{"content":"ok"}}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    private final Map<String, String> bodiesByMessage = new ConcurrentHashMap<>();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        WorkspaceExtensions.envReader = key -> null;
        WorkspaceExtensions.resetRuntimeOwnershipForTests();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var json = MAPPER.readTree(body);
            var messages = json.get("messages");
            bodiesByMessage.put(messages.get(messages.size() - 1).get("content").asString(), body);
            var bytes = SSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        WorkspaceExtensions.envReader = System::getenv;
        WorkspaceExtensions.resetRuntimeOwnershipForTests();
        for (var agent : List.of("agent-a", "agent-b", "agent-c")) {
            AiConfig.resetAgent(agent);
        }
        for (var property : List.of(AiConfig.TEMPERATURE_PROPERTY, AiConfig.MAX_TOKENS_PROPERTY,
                AiConfig.TOP_P_PROPERTY, AiConfig.STOP_PROPERTY)) {
            System.clearProperty(property);
        }
        AiConfig.configure("local", "llama3.2", null, null);
    }

    @Test
    void eachAgentSendsItsOwnGenerationValuesAndNoneLeaksIntoOtherSettings(
            @TempDir Path rootA, @TempDir Path rootB) throws IOException {
        workspace(rootA, "model-a", "temperature: 0.2", "max-tokens: 111", "top-p: 0.5", "stop: END-A");
        workspace(rootB, "model-b", "temperature: 0.9");

        assertTrue(WorkspaceExtensions.applyRuntime(WorkspaceExtensions.load(rootA.toString()).orElseThrow(),
                "agent-a"));
        assertTrue(WorkspaceExtensions.applyRuntime(WorkspaceExtensions.load(rootB.toString()).orElseThrow(),
                "agent-b"));

        // An agent with no RUNTIME.md generation values, configured afterwards.
        AiConfig.configureForAgent("agent-c", "remote", "model-c", "k", baseUrl);

        var a = dispatch("agent-a", "from-a");
        var b = dispatch("agent-b", "from-b");
        var c = dispatch("agent-c", "from-c");

        assertEquals(0.2, a.get("temperature").asDouble(), 1e-9, "agent-a's own temperature: " + a);
        assertEquals(111, a.get("max_tokens").asInt(), "agent-a's own max-tokens: " + a);
        assertEquals(0.5, a.get("top_p").asDouble(), 1e-9, "agent-a's own top-p: " + a);
        assertEquals("END-A", a.get("stop").get(0).asString(), "agent-a's own stop: " + a);

        assertEquals(0.9, b.get("temperature").asDouble(), 1e-9, "agent-b's own temperature: " + b);
        assertTrue(!b.has("max_tokens") || b.get("max_tokens").asInt() != 111,
                "agent-a's max-tokens must not reach agent-b: " + b);
        assertFalse(b.has("top_p"), "agent-a's top-p must not reach agent-b: " + b);
        assertFalse(b.has("stop"), "agent-a's stop must not reach agent-b: " + b);

        var cTemperature = c.has("temperature") ? c.get("temperature").asDouble() : null;
        assertTrue(cTemperature == null || (cTemperature != 0.9 && cTemperature != 0.2),
                "neither workspace's temperature may reach settings built afterwards: " + c);
        assertTrue(!c.has("max_tokens") || c.get("max_tokens").asInt() != 111,
                "agent-a's max-tokens must not reach settings built afterwards: " + c);
        assertFalse(c.has("top_p"), "agent-a's top-p must not reach settings built afterwards: " + c);

        for (var property : List.of(AiConfig.TEMPERATURE_PROPERTY, AiConfig.MAX_TOKENS_PROPERTY,
                AiConfig.TOP_P_PROPERTY, AiConfig.STOP_PROPERTY)) {
            assertNull(System.getProperty(property),
                    "RUNTIME.md must not write the JVM system property " + property);
        }
    }

    @Test
    void theFirstWorkspaceStillSeedsTheProcessDefaultWithItsGenerationValues(@TempDir Path rootA)
            throws IOException {
        workspace(rootA, "model-a", "temperature: 0.3");

        assertTrue(WorkspaceExtensions.applyRuntime(WorkspaceExtensions.load(rootA.toString()).orElseThrow(),
                "agent-a"));

        assertEquals(0.3, AiConfig.get().generation().temperature(),
                "the process default the first workspace seeds carries its temperature");
        assertEquals(List.of(), AiConfig.reportKnobsNotInEffect(),
                "a RUNTIME.md value is not a host knob the settings fail to apply");
    }

    @Test
    void aMalformedRuntimeMdValueIsIgnoredAndTheKnobStillApplies(@TempDir Path rootA)
            throws IOException {
        System.setProperty(AiConfig.TEMPERATURE_PROPERTY, "0.4");
        workspace(rootA, "model-a", "temperature: warm", "max-tokens: 64");

        assertTrue(WorkspaceExtensions.applyRuntime(WorkspaceExtensions.load(rootA.toString()).orElseThrow(),
                "agent-a"));

        var generation = AiConfig.forAgent("agent-a").generation();
        assertEquals(0.4, generation.temperature(),
                "a malformed RUNTIME.md temperature is dropped, so the operator's knob applies");
        assertEquals(64, generation.maxTokens());
        assertEquals("0.4", System.getProperty(AiConfig.TEMPERATURE_PROPERTY),
                "the operator's system property is left as it was");
    }

    private void workspace(Path root, String model, String... generation) throws IOException {
        Files.writeString(root.resolve("AGENTS.md"), "Operate carefully.", StandardCharsets.UTF_8);
        var lines = new java.util.ArrayList<>(List.of(
                "mode: remote", "model: " + model, "api-key: k", "base-url: " + baseUrl));
        lines.addAll(List.of(generation));
        Files.writeString(root.resolve("RUNTIME.md"), String.join("\n", lines), StandardCharsets.UTF_8);
    }

    private JsonNode dispatch(String agentId, String message) {
        var runtime = new BuiltInAgentRuntime();
        var session = new CollectingSession();
        runtime.execute(new AgentExecutionContext(
                message, "You are helpful", null,
                agentId, "session-" + agentId, "user-1", "conv-" + agentId,
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null), session);
        assertTrue(session.await(Duration.ofSeconds(10)), "the dispatch must settle");
        var body = bodiesByMessage.get(message);
        assertNotNull(body, "the dispatch for " + agentId + " must reach the endpoint");
        return MAPPER.readTree(body);
    }
}
