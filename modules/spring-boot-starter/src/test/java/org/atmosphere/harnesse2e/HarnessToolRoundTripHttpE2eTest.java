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
package org.atmosphere.harnesse2e;

import org.atmosphere.agent.processor.AgentHandler;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.fs.AgentFileSystem;
import org.atmosphere.ai.fs.AgentFileSystemProvider;
import org.atmosphere.ai.plan.AgentPlanStore;
import org.atmosphere.ai.plan.PlanStatus;
import org.atmosphere.ai.preset.HarnessPreset;
import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct tool round-trip through a fully booted Spring stack — no LLM. The
 * runtime-truth boots ({@link HarnessRuntimeTruthHttpE2eTest}) pin what the
 * console <em>reports</em>; this boot pins what the registered tools
 * <em>do</em>: it resolves the exact {@code ToolRegistry} the annotation scan
 * built for {@link DefaultOnHarnessAgent} and executes the harness floors the
 * way production dispatch does ({@code executor().execute(args, injectables)},
 * the same call {@code ToolExecutionHelper} makes inside the tool-call loop).
 *
 * <p>Pinned behaviors: the {@code write_todos} full-list-replace semantics
 * with plan persistence across executions through the production
 * {@link AgentPlanStore} (the same instance the admin REST endpoint reads —
 * asserted over HTTP), the {@link AiEvent.PlanUpdate} emission on every plan
 * change, the file-tool write/read/ls round trip against the
 * conversation-scoped workspace store, and the hard write bounds — an
 * over-limit write must come back as a clear rejection message and leave no
 * partial file behind (Correctness Invariant #3).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = HarnessToolRoundTripHttpE2eTest.TestApp.class,
        properties = {
                "atmosphere.packages=org.atmosphere.harnesse2e"
        })
class HarnessToolRoundTripHttpE2eTest {

    private static final String AGENT = "harness-truth-agent";
    private static final String AGENT_PATH = "/atmosphere/agent/" + AGENT;

    static {
        // Isolate the workspace substrate (plans/ + files/ subtrees) from the
        // developer's ~/.atmosphere/workspace. Must be a static initializer:
        // the property is read at annotation-scan time, when the Spring
        // context boots for this class.
        try {
            System.setProperty("atmosphere.workspace.root",
                    Files.createTempDirectory("atmosphere-harness-e2e").toString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not create isolated workspace root", e);
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private AtmosphereFramework framework;

    @Test
    void writeTodosPersistsThePlanAcrossExecutionsAndEmitsPlanUpdates() throws Exception {
        var registry = agentToolRegistry();
        assertTrue(registry.getTool("write_todos").isPresent(),
                "the booted agent's registry must hold the write_todos floor");

        var store = HarnessPreset.install(framework).planStore(AGENT).orElseThrow(
                () -> new AssertionError("the attach must register the agent's plan store"));
        var conversationId = "rt-plan-" + UUID.randomUUID();
        var session = new RecordingSession(conversationId);
        var injectables = injectables(session, Map.of(AgentPlanStore.class, store));

        var executor = registry.getTool("write_todos").orElseThrow().executor();
        var first = executor.execute(Map.of(
                "goal", "Prove the plan round trip",
                "todos", List.of(
                        Map.of("content", "Write the plan", "status", "in_progress",
                                "activeForm", "Writing the plan"),
                        Map.of("content", "Replace the plan", "status", "pending"))),
                injectables).toString();
        assertTrue(first.contains("[~] Writing the plan"),
                "the tool must echo the plan it wrote as a checklist, got: " + first);

        var persisted = store.get(AGENT, conversationId).orElseThrow(
                () -> new AssertionError("the plan must persist through the production store"));
        assertEquals(2, persisted.steps().size(), "both todos must persist");
        assertEquals(PlanStatus.IN_PROGRESS, persisted.steps().get(0).status());

        // Second execution: deepagents parity — the FULL list is replaced,
        // and the update is visible through the same store.
        var second = executor.execute(Map.of(
                "goal", "Prove the plan round trip",
                "todos", List.of(
                        Map.of("content", "Write the plan", "status", "completed"),
                        Map.of("content", "Replace the plan", "status", "completed"))),
                injectables).toString();
        assertTrue(second.contains("[x] Replace the plan"),
                "the second write must reflect the replaced statuses, got: " + second);

        var replaced = store.get(AGENT, conversationId).orElseThrow();
        assertEquals(2, replaced.steps().size(), "full-list replace must not append");
        assertTrue(replaced.steps().stream()
                        .allMatch(step -> step.status() == PlanStatus.COMPLETED),
                "the replacement plan must be fully completed, got: " + replaced.steps());

        assertEquals(2, session.planUpdates.size(),
                "every plan change must emit an AiEvent.PlanUpdate for the console");
        assertEquals("completed", session.planUpdates.get(1).steps().get(0).get("status"),
                "the last PlanUpdate must carry the replaced wire steps");
        // One-click correlation: every PlanUpdate carries the EXACT store
        // scope the admin endpoint below is queried with — the console's
        // Workspace tab loads the stored view from these fields instead of
        // requiring manual session entry.
        for (var update : session.planUpdates) {
            assertEquals(conversationId, update.conversationId(),
                    "PlanUpdate must carry the conversation id the store keyed on");
            assertEquals(AGENT, update.agentId(),
                    "PlanUpdate must carry the owner the store keyed on");
        }

        // The admin REST endpoint must serve the exact state the tool wrote —
        // same store instance, no reconstructed twin (Invariant #5).
        var response = get("/api/admin/agents/" + AGENT + "/plan?sessionId=" + conversationId);
        assertEquals(200, response.statusCode(),
                "the plan endpoint must serve the persisted plan, got: " + response.body());
        assertTrue(response.body().contains("\"content\":\"Replace the plan\""),
                "the endpoint must return the tool-written steps, got: " + response.body());
    }

    @Test
    void fileToolsRoundTripAndRejectOverLimitWrites() throws Exception {
        var registry = agentToolRegistry();
        for (var name : List.of("ls", "read_file", "write_file", "edit_file", "glob", "grep")) {
            assertTrue(registry.getTool(name).isPresent(),
                    "the booted agent's registry must hold the '" + name + "' floor");
        }

        var provider = HarnessPreset.install(framework).fileSystemProvider(AGENT).orElseThrow(
                () -> new AssertionError("the attach must register the agent's fs provider"));
        var conversationId = "rt-fs-" + UUID.randomUUID();
        var session = new RecordingSession(conversationId);
        var injectables = injectables(session,
                Map.of(AgentFileSystemProvider.class, provider));

        var written = registry.getTool("write_file").orElseThrow().executor().execute(
                Map.of("path", "notes/todo.md", "content", "remember the harness"),
                injectables).toString();
        assertEquals("Wrote notes/todo.md", written);

        var read = registry.getTool("read_file").orElseThrow().executor().execute(
                Map.of("path", "notes/todo.md"), injectables).toString();
        assertEquals("remember the harness", read,
                "read_file must return exactly what write_file stored");

        var listed = registry.getTool("ls").orElseThrow().executor().execute(
                Map.of("dir", "notes"), injectables).toString();
        assertTrue(listed.contains("notes/todo.md"),
                "ls must list the written file, got: " + listed);

        // Bounds enforcement (Invariant #3): one byte over the per-file limit
        // must be rejected with the clear message — surfaced as the tool
        // result so the model can correct course, never a stack trace.
        var oversized = "x".repeat(AgentFileSystem.Limits.DEFAULT_MAX_FILE_BYTES + 1);
        var rejected = registry.getTool("write_file").orElseThrow().executor().execute(
                Map.of("path", "notes/too-big.txt", "content", oversized),
                injectables).toString();
        assertTrue(rejected.startsWith("Error: Write rejected:"),
                "an over-limit write must surface the bounds rejection, got: "
                        + rejected.substring(0, Math.min(rejected.length(), 120)));
        assertTrue(rejected.contains("per-file limit"),
                "the rejection must name the violated bound, got: " + rejected);

        // Terminal-path completeness: the rejected write must leave nothing
        // behind — the store still holds only the first file.
        var after = registry.getTool("ls").orElseThrow().executor().execute(
                Map.of("dir", "notes"), injectables).toString();
        assertFalse(after.contains("too-big.txt"),
                "a rejected write must not create a partial file, got: " + after);
    }

    /**
     * Resolve the exact {@code ToolRegistry} the annotation scan built for the
     * fixture agent: the {@code AgentHandler} registered at the agent path
     * wraps the {@code AiEndpointHandler} that owns it. Both members are
     * deliberately non-public (the registry has no public mutation surface),
     * so the test reaches them reflectively — the point is to execute the
     * production instances, not test doubles.
     */
    private ToolRegistry agentToolRegistry() throws Exception {
        // The agent name also appears in sibling protocol mappings (MCP,
        // AG-UI), so select by handler type, not just by path substring.
        var handler = framework.getAtmosphereHandlers().entrySet().stream()
                .filter(e -> e.getKey().contains(AGENT))
                .map(e -> e.getValue().atmosphereHandler())
                .filter(AgentHandler.class::isInstance)
                .map(AgentHandler.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no AgentHandler registered at " + AGENT_PATH + "; got: "
                                + framework.getAtmosphereHandlers().keySet()));
        var delegateField = AgentHandler.class.getDeclaredField("aiDelegate");
        delegateField.setAccessible(true);
        var aiHandler = delegateField.get(handler);
        var accessor = aiHandler.getClass().getDeclaredMethod("toolRegistry");
        accessor.setAccessible(true);
        return (ToolRegistry) accessor.invoke(aiHandler);
    }

    /**
     * The injectables map production dispatch would hand the executor: the
     * live session (conversation scope + event sink) plus the harness surface
     * under test — mirroring what {@code OpenAiCompatibleClient} composes
     * before {@code ToolExecutionHelper.executeWithApproval}.
     */
    private static Map<Class<?>, Object> injectables(RecordingSession session,
                                                     Map<Class<?>, Object> surfaces) {
        var scope = new LinkedHashMap<Class<?>, Object>(surfaces);
        scope.put(StreamingSession.class, session);
        return scope;
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + path)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /**
     * Minimal session stub: supplies the conversation scope the built-in
     * tools key state through ({@code ToolScopes.conversationId} falls back
     * to {@code sessionId()}) and records emitted {@link AiEvent.PlanUpdate}s.
     */
    private static final class RecordingSession implements StreamingSession {
        final List<AiEvent.PlanUpdate> planUpdates = new ArrayList<>();
        private final String sessionId;

        RecordingSession(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public void emit(AiEvent event) {
            if (event instanceof AiEvent.PlanUpdate update) {
                planUpdates.add(update);
            }
        }

        @Override
        public void send(String text) {
        }

        @Override
        public void sendMetadata(String key, Object value) {
        }

        @Override
        public void progress(String message) {
        }

        @Override
        public void complete() {
        }

        @Override
        public void complete(String summary) {
        }

        @Override
        public void error(Throwable t) {
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }

    @SpringBootApplication
    static class TestApp {
    }
}
