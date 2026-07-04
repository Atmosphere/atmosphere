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
package org.atmosphere.ai.agentscope;

import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.PlanState;
import io.agentscope.core.plan.model.SubTask;
import io.agentscope.core.plan.model.SubTaskState;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.plan.AgentPlan;
import org.atmosphere.ai.plan.AgentPlanStore;
import org.atmosphere.ai.plan.FileSystemAgentPlanStore;
import org.atmosphere.ai.plan.PlanStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * Pins the native planning delegation for AgentScope: the
 * {@link AtmospherePlanStorage} round trip through Atmosphere's
 * {@link AgentPlanStore}, the change hook emitting
 * {@link AiEvent.PlanUpdate} on every plan mutation, cross-request
 * re-hydration of a live plan, and the runtime's provisioning gate
 * (store bound + {@code atmosphere.ai.planning} mode).
 */
class AgentScopePlanBridgeTest {

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

    private static SubTask subTask(String name, SubTaskState state) {
        var subtask = new SubTask(name, "", "");
        subtask.setState(state);
        return subtask;
    }

    private static List<String> statuses(AiEvent.PlanUpdate update) {
        return update.steps().stream()
                .map(step -> (String) step.get("status"))
                .toList();
    }

    @Test
    void planStorageRoundTripsThroughAgentPlanStore() {
        var store = new FileSystemAgentPlanStore(root);
        var storage = new AtmospherePlanStorage(store, "agent-1", "conv-1");
        var plan = new Plan("Build the site", "desc", "outcome", List.of(
                subTask("Scaffold pages", SubTaskState.DONE),
                subTask("Write content", SubTaskState.IN_PROGRESS),
                subTask("Deploy", SubTaskState.TODO)));

        storage.addPlan(plan).block();

        var stored = store.get("agent-1", "conv-1").orElseThrow();
        assertEquals("Build the site", stored.goal());
        assertEquals(List.of("Scaffold pages", "Write content", "Deploy"),
                stored.steps().stream().map(AgentPlan.Step::content).toList());
        assertEquals(List.of(PlanStatus.COMPLETED, PlanStatus.IN_PROGRESS, PlanStatus.PENDING),
                stored.steps().stream().map(AgentPlan.Step::status).toList());

        var recovered = storage.getPlan(storage.currentPlanId()).block();
        assertNotNull(recovered);
        assertEquals(storage.currentPlanId(), recovered.getId());
        assertEquals("Build the site", recovered.getName());
        assertEquals(PlanState.IN_PROGRESS, recovered.getState());
        assertEquals(List.of(SubTaskState.DONE, SubTaskState.IN_PROGRESS, SubTaskState.TODO),
                recovered.getSubtasks().stream().map(SubTask::getState).toList());

        assertNull(storage.getPlan("some-other-id").block());
        assertEquals(1, storage.getPlans().block().size());
    }

    @Test
    void terminalPlanMirrorCoercesOpenSteps() {
        var store = new FileSystemAgentPlanStore(root);
        var storage = new AtmospherePlanStorage(store, "agent-1", "conv-done");
        var done = new Plan("Finish it", "", "", List.of(
                subTask("Step one", SubTaskState.DONE),
                subTask("Step two", SubTaskState.IN_PROGRESS),
                subTask("Step three", SubTaskState.TODO)));
        done.finish(PlanState.DONE, "shipped");
        storage.addPlan(done).block();
        assertEquals(List.of(PlanStatus.COMPLETED, PlanStatus.COMPLETED, PlanStatus.COMPLETED),
                store.get("agent-1", "conv-done").orElseThrow().steps().stream()
                        .map(AgentPlan.Step::status).toList());

        var abandonedStorage = new AtmospherePlanStorage(store, "agent-1", "conv-abandoned");
        var abandoned = new Plan("Drop it", "", "", List.of(
                subTask("Step one", SubTaskState.DONE),
                subTask("Step two", SubTaskState.IN_PROGRESS)));
        abandoned.finish(PlanState.ABANDONED, "not needed");
        abandonedStorage.addPlan(abandoned).block();
        assertEquals(List.of(PlanStatus.COMPLETED, PlanStatus.ABANDONED),
                store.get("agent-1", "conv-abandoned").orElseThrow().steps().stream()
                        .map(AgentPlan.Step::status).toList());
    }

    @Test
    void changeHookEmitsPlanUpdateAndMirrorsEveryMutation() {
        var store = new FileSystemAgentPlanStore(root);
        var session = new EventRecordingSession("conv-9");
        var notebook = AgentScopePlanBridge.provision(store, "agent-1", "conv-9", session);

        notebook.createPlanWithSubTasks("Ship feature", "d", "o", List.of(
                new SubTask("Write code", "", ""),
                new SubTask("Run tests", "", ""))).block();
        var updates = session.planUpdates();
        assertEquals(1, updates.size());
        assertEquals("Ship feature", updates.get(0).goal());
        assertEquals(List.of("pending", "pending"), statuses(updates.get(0)));
        assertEquals("Ship feature", store.get("agent-1", "conv-9").orElseThrow().goal());

        notebook.updateSubtaskState(0, "in_progress").block();
        updates = session.planUpdates();
        assertEquals(2, updates.size());
        assertEquals(List.of("in_progress", "pending"), statuses(updates.get(1)));
        assertEquals(PlanStatus.IN_PROGRESS,
                store.get("agent-1", "conv-9").orElseThrow().steps().get(0).status());

        // finish_subtask auto-activates the next subtask.
        notebook.finishSubtask(0, "code written").block();
        updates = session.planUpdates();
        assertEquals(3, updates.size());
        assertEquals(List.of("completed", "in_progress"), statuses(updates.get(2)));

        // finish_plan persists the terminal snapshot, then the hook fires
        // with a null plan — the emitted update carries the stored snapshot
        // with open steps coerced to the plan's terminal state.
        notebook.finishPlan("done", "shipped").block();
        updates = session.planUpdates();
        assertEquals(4, updates.size());
        assertEquals(List.of("completed", "completed"), statuses(updates.get(3)));
        assertEquals(List.of(PlanStatus.COMPLETED, PlanStatus.COMPLETED),
                store.get("agent-1", "conv-9").orElseThrow().steps().stream()
                        .map(AgentPlan.Step::status).toList());
    }

    @Test
    void provisionRehydratesLivePlanAcrossRequests() {
        var store = new FileSystemAgentPlanStore(root);
        var firstSession = new EventRecordingSession("conv-9");
        var firstNotebook = AgentScopePlanBridge.provision(store, "agent-1", "conv-9", firstSession);
        firstNotebook.createPlanWithSubTasks("Ship feature", "d", "o", List.of(
                new SubTask("Write code", "", ""),
                new SubTask("Run tests", "", ""))).block();
        firstNotebook.updateSubtaskState(0, "in_progress").block();

        // The notebook is rebuilt per request — a fresh provision for the
        // same scope must recover the live plan from Atmosphere's store.
        var secondSession = new EventRecordingSession("conv-9");
        var secondNotebook = AgentScopePlanBridge.provision(store, "agent-1", "conv-9", secondSession);
        var current = secondNotebook.getCurrentPlan();
        assertNotNull(current);
        assertEquals("Ship feature", current.getName());
        assertEquals(SubTaskState.IN_PROGRESS, current.getSubtasks().get(0).getState());
        assertEquals(SubTaskState.TODO, current.getSubtasks().get(1).getState());
        // Re-hydration announces the current plan to the console.
        assertEquals(1, secondSession.planUpdates().size());
        assertEquals(List.of("in_progress", "pending"),
                statuses(secondSession.planUpdates().get(0)));
    }

    @Test
    void provisionDoesNotResurrectFinishedPlan() {
        var store = new FileSystemAgentPlanStore(root);
        var session = new EventRecordingSession("conv-9");
        var notebook = AgentScopePlanBridge.provision(store, "agent-1", "conv-9", session);
        notebook.createPlanWithSubTasks("Ship feature", "d", "o", List.of(
                new SubTask("Write code", "", ""))).block();
        notebook.finishPlan("done", "shipped").block();

        var nextSession = new EventRecordingSession("conv-9");
        var nextNotebook = AgentScopePlanBridge.provision(store, "agent-1", "conv-9", nextSession);
        assertNull(nextNotebook.getCurrentPlan());
        assertTrue(nextSession.planUpdates().isEmpty());
    }

    @Test
    void runtimeProvisionsNotebookOnlyWhenStoreBoundAndModeAllows() {
        var store = new FileSystemAgentPlanStore(root);
        var context = new AgentExecutionContext(
                "Hello", "You are helpful", "qwen-plus",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);

        // No AgentPlanStore in the injectables (Harness.PLANNING not
        // resolved for the endpoint): no native plan surface.
        var bare = new EventRecordingSession("conv-1");
        assertNull(AgentScopeAgentRuntime.provisionPlanNotebook(context, bare));

        // Store bound + default AUTO mode: native surface provisions.
        var bound = new EventRecordingSession("conv-1");
        bound.injectables.put(AgentPlanStore.class, store);
        assertNotNull(AgentScopeAgentRuntime.provisionPlanNotebook(context, bound));

        // BUILTIN mode: the write_todos floor governs — provisioning the
        // notebook too would register duplicate plan surfaces.
        System.setProperty(AiConfig.PLANNING_PROPERTY, "builtin");
        try {
            assertNull(AgentScopeAgentRuntime.provisionPlanNotebook(context, bound));
        } finally {
            System.clearProperty(AiConfig.PLANNING_PROPERTY);
        }
    }
}
