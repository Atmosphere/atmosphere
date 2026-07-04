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
package org.atmosphere.ai.spring.alibaba;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.plan.AgentPlan;
import org.atmosphere.ai.plan.AgentPlanStore;
import org.atmosphere.ai.plan.FileSystemAgentPlanStore;
import org.atmosphere.ai.plan.PlanStatus;
import org.atmosphere.ai.plan.PlanningTools;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the native planning delegation for Spring AI Alibaba: real
 * {@code write_todos} calls through the framework's own
 * {@code TodoListInterceptor} tool surface land in Atmosphere's
 * {@link AgentPlanStore} and emit {@link AiEvent.PlanUpdate}, the store's
 * bounds reject over-limit plans with a clear tool-error reply,
 * cross-request re-hydration restores a live plan (and only a live plan),
 * and the runtime's provisioning gate honors the store binding, the
 * {@code atmosphere.ai.planning} mode, and user-tool name precedence.
 */
class SpringAiAlibabaPlanBridgeTest {

    /** The graph-state key Alibaba's WriteTodosTool writes todos into. */
    private static final String AGENT_STATE_KEY = "_AGENT_STATE_FOR_UPDATE_";

    @TempDir
    Path root;

    /** Minimal session recording emitted events, with mutable injectables. */
    static final class EventRecordingSession implements StreamingSession {
        final List<AiEvent> events = new ArrayList<>();
        final Map<Class<?>, Object> injectables = new HashMap<>();
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
        public Map<Class<?>, Object> injectables() {
            return injectables;
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

        List<AiEvent.PlanUpdate> planUpdates() {
            return events.stream()
                    .filter(AiEvent.PlanUpdate.class::isInstance)
                    .map(AiEvent.PlanUpdate.class::cast)
                    .toList();
        }
    }

    private static AgentExecutionContext context(List<ToolDefinition> tools) {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "qwen-plus",
                "agent-1", "session-1", "user-1", "conv-1",
                tools, null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    /**
     * The write_todos ToolCallback exactly as the ReactAgent registers it:
     * built by the framework's TodoListInterceptor around the bridged
     * event handler.
     */
    private static ToolCallback writeTodosCallback(
            SpringAiAlibabaPlanBridge.Provision provision) {
        var tools = provision.interceptor().getTools();
        assertEquals(1, tools.size());
        return tools.get(0);
    }

    /** Dispatch a write_todos call the way the ReAct graph would. */
    private static String callWriteTodos(ToolCallback callback, String todosJson,
                                         Map<String, Object> graphState) {
        return callback.call(todosJson,
                new ToolContext(Map.of(AGENT_STATE_KEY, graphState)));
    }

    private static List<String> statuses(AiEvent.PlanUpdate update) {
        return update.steps().stream()
                .map(step -> (String) step.get("status"))
                .toList();
    }

    @Test
    void todoWritesRoundTripThroughAgentPlanStore() {
        var store = new FileSystemAgentPlanStore(root);
        var session = new EventRecordingSession("conv-9");
        var provision = SpringAiAlibabaPlanBridge.provision(store, "agent-1", "conv-9", session);
        assertNull(provision.rehydrationBlock(), "no stored plan -> nothing to re-hydrate");

        var graphState = new HashMap<String, Object>();
        var reply = callWriteTodos(writeTodosCallback(provision), """
                {"todos":[
                  {"content":"Write code","status":"in_progress","activeForm":"Writing code"},
                  {"content":"Run tests","status":"pending"}
                ]}""", graphState);

        // The framework's own success path ran: todos landed in graph state.
        assertNotNull(reply);
        assertTrue(graphState.containsKey("todos"), "WriteTodosTool must update graph state");

        // ...and the bridge mirrored the write into Atmosphere's store.
        var stored = store.get("agent-1", "conv-9").orElseThrow();
        assertEquals(List.of("Write code", "Run tests"),
                stored.steps().stream().map(AgentPlan.Step::content).toList());
        assertEquals(List.of(PlanStatus.IN_PROGRESS, PlanStatus.PENDING),
                stored.steps().stream().map(AgentPlan.Step::status).toList());
        assertEquals("Writing code", stored.steps().get(0).activeForm());

        // ...and the console saw the update, carrying the exact store scope
        // so the Workspace tab correlates it without manual session entry.
        var updates = session.planUpdates();
        assertEquals(1, updates.size());
        assertEquals(List.of("in_progress", "pending"), statuses(updates.get(0)));
        assertEquals("conv-9", updates.get(0).conversationId());
        assertEquals("agent-1", updates.get(0).agentId());

        // Second write replaces the full list (deepagents parity).
        callWriteTodos(writeTodosCallback(provision), """
                {"todos":[
                  {"content":"Write code","status":"completed"},
                  {"content":"Run tests","status":"in_progress","activeForm":"Running tests"}
                ]}""", graphState);
        assertEquals(List.of(PlanStatus.COMPLETED, PlanStatus.IN_PROGRESS),
                store.get("agent-1", "conv-9").orElseThrow().steps().stream()
                        .map(AgentPlan.Step::status).toList());
        assertEquals(2, session.planUpdates().size());
    }

    @Test
    void goalCarriedForwardAcrossTodoWrites() {
        var store = new FileSystemAgentPlanStore(root);
        store.put("agent-1", "conv-goal", new AgentPlan("Ship the feature", List.of(
                new AgentPlan.Step("Write code", PlanStatus.IN_PROGRESS, null))));
        var session = new EventRecordingSession("conv-goal");
        var provision = SpringAiAlibabaPlanBridge.provision(store, "agent-1", "conv-goal", session);

        callWriteTodos(writeTodosCallback(provision),
                "{\"todos\":[{\"content\":\"Write code\",\"status\":\"completed\"}]}",
                new HashMap<>());

        // Alibaba's todo surface has no goal concept - the stored goal survives
        // the full-list replace.
        var stored = store.get("agent-1", "conv-goal").orElseThrow();
        assertEquals("Ship the feature", stored.goal());
        var writeUpdate = session.planUpdates().get(session.planUpdates().size() - 1);
        assertEquals("Ship the feature", writeUpdate.goal());
    }

    @Test
    void overLimitPlanRejectedWithClearMessage() {
        var store = new FileSystemAgentPlanStore(root);
        store.put("agent-1", "conv-big", new AgentPlan(null, List.of(
                new AgentPlan.Step("Keep me", PlanStatus.IN_PROGRESS, null))));
        var session = new EventRecordingSession("conv-big");
        var provision = SpringAiAlibabaPlanBridge.provision(store, "agent-1", "conv-big", session);
        var updatesAfterProvision = session.planUpdates().size();

        var todos = new StringBuilder("{\"todos\":[");
        for (int i = 0; i <= FileSystemAgentPlanStore.MAX_STEPS; i++) {
            if (i > 0) {
                todos.append(',');
            }
            todos.append("{\"content\":\"Step ").append(i).append("\",\"status\":\"pending\"}");
        }
        todos.append("]}");

        var reply = callWriteTodos(writeTodosCallback(provision), todos.toString(),
                new HashMap<>());

        // The store's bounds rejection rides WriteTodosTool's error reply back
        // to the model as a clear message (Correctness Invariant #3).
        assertNotNull(reply);
        assertTrue(reply.contains("-step limit"),
                "tool reply must carry the bounds message, got: " + reply);
        // The over-limit plan never persisted and the console saw no update.
        assertEquals(List.of("Keep me"),
                store.get("agent-1", "conv-big").orElseThrow().steps().stream()
                        .map(AgentPlan.Step::content).toList());
        assertEquals(updatesAfterProvision, session.planUpdates().size());
    }

    @Test
    void provisionRehydratesLivePlanAcrossRequests() {
        var store = new FileSystemAgentPlanStore(root);
        var firstSession = new EventRecordingSession("conv-9");
        var first = SpringAiAlibabaPlanBridge.provision(store, "agent-1", "conv-9", firstSession);
        callWriteTodos(writeTodosCallback(first), """
                {"todos":[
                  {"content":"Write code","status":"in_progress","activeForm":"Writing code"},
                  {"content":"Run tests","status":"pending"}
                ]}""", new HashMap<>());

        // The agent (and its graph state) is rebuilt per request - a fresh
        // provision for the same scope must restore the live plan from
        // Atmosphere's store: a system-prompt block for the model and a
        // turn-start PlanUpdate for the console.
        var secondSession = new EventRecordingSession("conv-9");
        var second = SpringAiAlibabaPlanBridge.provision(store, "agent-1", "conv-9", secondSession);
        var block = second.rehydrationBlock();
        assertNotNull(block);
        assertTrue(block.contains("write_todos"));
        assertTrue(block.contains("[~] Writing code"));
        assertTrue(block.contains("[ ] Run tests"));
        assertEquals(1, secondSession.planUpdates().size());
        assertEquals(List.of("in_progress", "pending"),
                statuses(secondSession.planUpdates().get(0)));
        // The turn-start announcement carries the store scope too.
        assertEquals("conv-9", secondSession.planUpdates().get(0).conversationId());
        assertEquals("agent-1", secondSession.planUpdates().get(0).agentId());
    }

    @Test
    void provisionDoesNotResurrectTerminalPlan() {
        var store = new FileSystemAgentPlanStore(root);
        store.put("agent-1", "conv-done", new AgentPlan("Done deal", List.of(
                new AgentPlan.Step("Write code", PlanStatus.COMPLETED, null),
                new AgentPlan.Step("Old idea", PlanStatus.ABANDONED, null))));

        var session = new EventRecordingSession("conv-done");
        var provision = SpringAiAlibabaPlanBridge.provision(store, "agent-1", "conv-done", session);
        assertNull(provision.rehydrationBlock());
        assertTrue(session.planUpdates().isEmpty());
    }

    @Test
    void runtimeProvisionsInterceptorOnlyWhenStoreBoundAndModeAllows() {
        var store = new FileSystemAgentPlanStore(root);
        var context = context(List.of());

        // No AgentPlanStore in the injectables (Harness.PLANNING not
        // resolved for the endpoint): no native plan surface.
        var bare = new EventRecordingSession("conv-1");
        assertNull(SpringAiAlibabaAgentRuntime.provisionPlanSurface(context, bare));

        // Store bound + default AUTO mode: native surface provisions.
        var bound = new EventRecordingSession("conv-1");
        bound.injectables.put(AgentPlanStore.class, store);
        assertNotNull(SpringAiAlibabaAgentRuntime.provisionPlanSurface(context, bound));

        // BUILTIN mode: the write_todos floor governs - provisioning the
        // interceptor too would register duplicate plan tools.
        System.setProperty(AiConfig.PLANNING_PROPERTY, "builtin");
        try {
            assertNull(SpringAiAlibabaAgentRuntime.provisionPlanSurface(context, bound));
        } finally {
            System.clearProperty(AiConfig.PLANNING_PROPERTY);
        }

        // A user tool already claims the write_todos name: user tool wins,
        // same posture as the built-in floor.
        var userToolContext = context(List.of(PlanningTools.writeTodosTool("agent-1")));
        assertNull(SpringAiAlibabaAgentRuntime.provisionPlanSurface(userToolContext, bound));
    }
}
