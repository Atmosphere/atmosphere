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
package org.atmosphere.ai.plan;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the built-in {@code write_todos} tool: full-list replace persisted
 * through the {@link AgentPlanStore} from the injectables seam, the Markdown
 * checklist result, the {@link AiEvent.PlanUpdate} emission, and the clear
 * degraded-mode messages (no store bound, malformed arguments, bounds
 * rejection).
 */
public class PlanningToolsTest {

    @TempDir
    Path root;

    /** A minimal session that records emitted events. */
    static final class EventRecordingSession implements StreamingSession {
        final List<AiEvent> events = new ArrayList<>();
        private final String sessionId;
        private boolean closed;

        EventRecordingSession(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public String sessionId() {
            return sessionId;
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
            closed = true;
        }

        @Override
        public void complete(String summary) {
            closed = true;
        }

        @Override
        public void error(Throwable t) {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void emit(AiEvent event) {
            events.add(event);
        }
    }

    private static Map<String, Object> todo(String content, String status, String activeForm) {
        var todo = new LinkedHashMap<String, Object>();
        todo.put("content", content);
        todo.put("status", status);
        if (activeForm != null) {
            todo.put("activeForm", activeForm);
        }
        return todo;
    }

    @Test
    public void toolShapeIsPinned() {
        var tool = PlanningTools.writeTodosTool("agent-x");

        assertEquals("write_todos", tool.name());
        assertEquals(ToolKind.EDIT, tool.kind());
        assertEquals(2, tool.parameters().size());
        assertEquals("todos", tool.parameters().get(0).name());
        assertEquals("array", tool.parameters().get(0).type());
        assertTrue(tool.parameters().get(0).required());
        assertEquals("goal", tool.parameters().get(1).name());
        assertTrue(!tool.parameters().get(1).required());
    }

    @Test
    public void persistsEmitsAndReturnsMarkdown() throws Exception {
        var store = new FileSystemAgentPlanStore(root);
        var session = new EventRecordingSession("conv-42");
        var scope = new LinkedHashMap<Class<?>, Object>();
        scope.put(AgentPlanStore.class, store);
        scope.put(StreamingSession.class, session);

        var tool = PlanningTools.writeTodosTool("agent-x");
        var result = tool.executor().execute(Map.of(
                "goal", "Ship the feature",
                "todos", List.of(
                        todo("Write tests", "completed", null),
                        todo("Fix bug", "in_progress", "Fixing bug"),
                        todo("Docs", "pending", null))), scope);

        // 1. Markdown checklist returned to the model.
        assertEquals("""
                Goal: Ship the feature
                - [x] Write tests
                - [~] Fixing bug
                - [ ] Docs""", result);

        // 2. Plan persisted per agentId + conversationId (session id here).
        var persisted = store.get("agent-x", "conv-42").orElseThrow();
        assertEquals(3, persisted.steps().size());
        assertEquals(PlanStatus.IN_PROGRESS, persisted.steps().get(1).status());
        assertEquals("Ship the feature", persisted.goal());

        // 3. PlanUpdate emitted for the console.
        assertEquals(1, session.events.size());
        var update = assertInstanceOf(AiEvent.PlanUpdate.class, session.events.get(0));
        assertEquals("Ship the feature", update.goal());
        assertEquals(3, update.steps().size());
        assertEquals("in_progress", update.steps().get(1).get("status"));
    }

    @Test
    public void fullListReplaceDropsOldSteps() throws Exception {
        var store = new FileSystemAgentPlanStore(root);
        var session = new EventRecordingSession("conv");
        var scope = Map.<Class<?>, Object>of(
                AgentPlanStore.class, store, StreamingSession.class, session);
        var tool = PlanningTools.writeTodosTool("a");

        tool.executor().execute(Map.of("todos",
                List.of(todo("old", "pending", null))), scope);
        tool.executor().execute(Map.of("todos",
                List.of(todo("new", "in_progress", null))), scope);

        var persisted = store.get("a", "conv").orElseThrow();
        assertEquals(1, persisted.steps().size());
        assertEquals("new", persisted.steps().get(0).content());
        assertEquals(2, session.events.size(), "every change must emit a PlanUpdate");
    }

    @Test
    public void missingStoreYieldsClearMessage() throws Exception {
        var tool = PlanningTools.writeTodosTool("a");
        var result = tool.executor().execute(
                Map.of("todos", List.of(todo("x", "pending", null))), Map.of());
        assertTrue(result.toString().contains("no plan store"), result.toString());
    }

    @Test
    public void malformedArgumentsYieldClearMessages() throws Exception {
        var store = new FileSystemAgentPlanStore(root);
        var scope = Map.<Class<?>, Object>of(AgentPlanStore.class, store);
        var tool = PlanningTools.writeTodosTool("a");

        var noTodos = tool.executor().execute(Map.of(), scope);
        assertTrue(noTodos.toString().contains("'todos' is required"), noTodos.toString());

        var notAList = tool.executor().execute(Map.of("todos", "nope"), scope);
        assertTrue(notAList.toString().contains("'todos' is required"), notAList.toString());

        var noContent = tool.executor().execute(
                Map.of("todos", List.of(Map.of("status", "pending"))), scope);
        assertTrue(noContent.toString().contains("non-empty 'content'"), noContent.toString());

        assertTrue(store.get("a", "default").isEmpty(),
                "malformed input must not persist anything");
    }

    @Test
    public void boundsRejectionSurfacesTheMessage() throws Exception {
        var store = new FileSystemAgentPlanStore(root);
        var scope = Map.<Class<?>, Object>of(AgentPlanStore.class, store);
        var tool = PlanningTools.writeTodosTool("a");

        var tooMany = new ArrayList<Map<String, Object>>();
        for (int i = 0; i <= FileSystemAgentPlanStore.MAX_STEPS; i++) {
            tooMany.add(todo("step " + i, "pending", null));
        }
        var result = tool.executor().execute(Map.of("todos", tooMany), scope);
        assertTrue(result.toString().startsWith("write_todos failed:"), result.toString());
        assertTrue(result.toString().contains("limit"), result.toString());
    }

    @Test
    public void statusStringsParseLeniently() throws Exception {
        var store = new FileSystemAgentPlanStore(root);
        var scope = Map.<Class<?>, Object>of(AgentPlanStore.class, store);
        var tool = PlanningTools.writeTodosTool("a");

        tool.executor().execute(Map.of("todos", List.of(
                todo("a", "done", null),
                todo("b", "garbage", null),
                todo("c", null, null))), scope);

        var persisted = store.get("a", "default").orElseThrow();
        assertEquals(PlanStatus.COMPLETED, persisted.steps().get(0).status());
        assertEquals(PlanStatus.PENDING, persisted.steps().get(1).status());
        assertEquals(PlanStatus.PENDING, persisted.steps().get(2).status());
    }

    @Test
    public void stringEncodedTodosParse() throws Exception {
        // Battle-tested regression: Gemini through the built-in runtime
        // delivered 'todos' as a JSON-encoded STRING (and omitted the
        // optional activeForm) — the tool must accept both, not reject the
        // model's legitimate call.
        var store = new FileSystemAgentPlanStore(root);
        var scope = Map.<Class<?>, Object>of(AgentPlanStore.class, store);
        var tool = PlanningTools.writeTodosTool("a");

        tool.executor().execute(Map.of("todos",
                "[{\"content\":\"Research WebTransport browser support\",\"status\":\"pending\"},"
                        + "{\"content\":\"Draft a short note to the ops team\",\"status\":\"pending\"}]"),
                scope);

        var persisted = store.get("a", "default").orElseThrow();
        assertEquals(2, persisted.steps().size());
        assertEquals("Research WebTransport browser support", persisted.steps().get(0).content());
        assertEquals(PlanStatus.PENDING, persisted.steps().get(1).status());
    }

    @Test
    public void unparseableTodosStringStillFailsClearly() throws Exception {
        var store = new FileSystemAgentPlanStore(root);
        var scope = Map.<Class<?>, Object>of(AgentPlanStore.class, store);
        var tool = PlanningTools.writeTodosTool("a");

        var result = tool.executor().execute(Map.of("todos", "not json at all"), scope);

        assertTrue(result.toString().contains("todos"),
                "garbage must still produce the clear structure error, got: " + result);
    }
}
