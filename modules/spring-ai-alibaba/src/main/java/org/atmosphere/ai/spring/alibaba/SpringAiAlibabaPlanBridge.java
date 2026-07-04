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

import com.alibaba.cloud.ai.graph.agent.interceptor.todolist.TodoListInterceptor;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.plan.AgentPlan;
import org.atmosphere.ai.plan.AgentPlanStore;
import org.atmosphere.ai.plan.PlanStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Provisions the per-request Spring AI Alibaba {@link TodoListInterceptor}
 * that delegates the harness PLANNING primitive to the framework's native
 * todo surface (the model-facing {@code write_todos} tool the interceptor
 * registers on the {@code ReactAgent}, plus its todo-usage system-prompt
 * guidance):
 *
 * <ul>
 *   <li><b>Persistence</b> — Alibaba's {@code WriteTodosTool} stores todos in
 *       per-invocation graph state ({@code _AGENT_STATE_FOR_UPDATE_} →
 *       {@code todos}), which the adapter's per-request agent rebuild would
 *       discard. The interceptor's {@code todoEventHandler} therefore mirrors
 *       every todo write into Atmosphere's {@link AgentPlanStore} keyed
 *       {@code agentId × conversationId}, so Atmosphere owns the plan state
 *       across turns.</li>
 *   <li><b>Observability</b> — the same handler emits
 *       {@link AiEvent.PlanUpdate} on the request's {@link StreamingSession},
 *       so consoles render the plan exactly as they do for the built-in
 *       {@code write_todos} floor.</li>
 *   <li><b>Continuity</b> — {@link Provision#rehydrationBlock()} carries the
 *       previous turn's live plan (rendered as a Markdown checklist) for the
 *       runtime to append to the agent's system prompt, and re-hydration
 *       emits the restored plan as a turn-start {@link AiEvent.PlanUpdate}.
 *       Plans whose steps are all terminal are not resurrected.</li>
 * </ul>
 *
 * <p><b>Status mapping.</b> Alibaba's {@code TodoStatus} carries
 * {@code PENDING} / {@code IN_PROGRESS} / {@code COMPLETED}; each maps to the
 * same-named {@link PlanStatus}. The framework has no {@code ABANDONED}
 * state — the model drops abandoned entries from the full-list replace
 * instead, matching deepagents semantics. The plan {@code goal} has no
 * counterpart on the todo surface either, so a goal already stored for the
 * conversation is carried forward on every write.</p>
 *
 * <p><b>Ownership / terminal paths:</b> the interceptor is created per
 * request and referenced only by the rebuilt {@code ReactAgent} dispatched
 * for that request — it holds no global registrations, threads, or native
 * resources, so it is unreachable (and collectable) as soon as the request's
 * stream terminates on any path. The event handler lives and dies with the
 * interceptor; no uninstall step is required.</p>
 *
 * <p><b>Bounds (Correctness Invariant #3):</b> {@link AgentPlanStore#put}
 * enforces the store's step / byte caps and throws
 * {@link IllegalArgumentException} on violation. The exception propagates out
 * of the event handler into Alibaba's {@code WriteTodosTool}, which catches
 * it and returns the message as a tool-error reply — the model sees the clear
 * rejection instead of a stack trace, and the over-limit plan never
 * persists.</p>
 */
public final class SpringAiAlibabaPlanBridge {

    private SpringAiAlibabaPlanBridge() {
    }

    /**
     * The provisioned native plan surface for one dispatch.
     *
     * @param interceptor      the bridged {@link TodoListInterceptor} to
     *                         register via the {@code ReactAgent} builder's
     *                         {@code interceptors(...)} seam — never {@code null}
     * @param rehydrationBlock system-prompt block restoring the previous
     *                         turn's live plan, or {@code null} when the
     *                         conversation has no live plan to restore
     */
    public record Provision(TodoListInterceptor interceptor, String rehydrationBlock) {

        public Provision {
            if (interceptor == null) {
                throw new IllegalArgumentException("interceptor is required");
            }
        }
    }

    /**
     * Build the request-scoped {@link TodoListInterceptor} bridged to
     * Atmosphere's plan surface, re-hydrating the conversation's live plan
     * from the store (the agent is rebuilt per request, so continuity comes
     * from the store, not the instance).
     *
     * @param store          the Atmosphere plan store resolved from the
     *                       endpoint's injectables (never {@code null})
     * @param agentId        the plan owner scope (never {@code null} or blank)
     * @param conversationId the conversation scope (never {@code null} or blank)
     * @param session        the live streaming session receiving
     *                       {@link AiEvent.PlanUpdate} (never {@code null})
     * @return the bridged surface, never {@code null}
     */
    public static Provision provision(AgentPlanStore store, String agentId,
                                      String conversationId, StreamingSession session) {
        if (store == null) {
            throw new IllegalArgumentException("store is required");
        }
        if (session == null) {
            throw new IllegalArgumentException("session is required");
        }
        var interceptor = TodoListInterceptor.builder()
                .todoEventHandler(todos ->
                        onTodosWritten(store, agentId, conversationId, session, todos))
                .build();
        return new Provision(interceptor, rehydrate(store, agentId, conversationId, session));
    }

    /**
     * Restore the conversation's live plan: announce it to the console as a
     * turn-start {@link AiEvent.PlanUpdate} and render the system-prompt
     * block the model needs to keep maintaining it (todos live in
     * per-invocation graph state, so without this the model would start
     * every turn with an empty list). Terminal snapshots — no
     * {@link PlanStatus#PENDING} / {@link PlanStatus#IN_PROGRESS} step left —
     * are not resurrected.
     *
     * @return the system-prompt block, or {@code null} when nothing is live
     */
    static String rehydrate(AgentPlanStore store, String agentId,
                            String conversationId, StreamingSession session) {
        var stored = store.get(agentId, conversationId).orElse(null);
        if (stored == null || stored.steps().isEmpty() || !hasOpenSteps(stored)) {
            return null;
        }
        session.emit(new AiEvent.PlanUpdate(stored.toWireSteps(), stored.goal()));
        return "## Current todo list (restored from the previous turn)\n"
                + stored.toMarkdown()
                + "\nKeep maintaining this list with the write_todos tool — every call "
                + "replaces the FULL list.";
    }

    /** Whether the plan still has steps to work on. */
    static boolean hasOpenSteps(AgentPlan plan) {
        for (var step : plan.steps()) {
            if (step.status() == PlanStatus.PENDING || step.status() == PlanStatus.IN_PROGRESS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Event-handler body — fired by Alibaba's {@code WriteTodosTool} on every
     * {@code write_todos} call with the full replacement list. Mirrors the
     * list into the store (bounds enforced there), then emits the update.
     */
    private static void onTodosWritten(AgentPlanStore store, String agentId,
                                       String conversationId, StreamingSession session,
                                       List<TodoListInterceptor.Todo> todos) {
        var goal = store.get(agentId, conversationId).map(AgentPlan::goal).orElse(null);
        var plan = toAgentPlan(todos, goal);
        store.put(agentId, conversationId, plan);
        session.emit(new AiEvent.PlanUpdate(plan.toWireSteps(), plan.goal()));
    }

    /**
     * Map Alibaba todos onto an {@link AgentPlan}. Strict on content —
     * {@link AgentPlan.Step} rejects blank content with a clear message that
     * rides the {@code WriteTodosTool} error reply back to the model
     * (Correctness Invariant #4).
     */
    static AgentPlan toAgentPlan(List<TodoListInterceptor.Todo> todos, String goal) {
        var source = todos != null ? todos : List.<TodoListInterceptor.Todo>of();
        var steps = new ArrayList<AgentPlan.Step>(source.size());
        for (var todo : source) {
            if (todo == null) {
                throw new IllegalArgumentException("todo entries must not be null");
            }
            steps.add(new AgentPlan.Step(
                    todo.getContent(), toStatus(todo.getStatus()), todo.getActiveForm()));
        }
        return new AgentPlan(goal, steps);
    }

    /** Alibaba {@code TodoStatus} → {@link PlanStatus} (1:1 on the three shared states). */
    static PlanStatus toStatus(TodoListInterceptor.TodoStatus status) {
        if (status == null) {
            return PlanStatus.PENDING;
        }
        return switch (status) {
            case PENDING -> PlanStatus.PENDING;
            case IN_PROGRESS -> PlanStatus.IN_PROGRESS;
            case COMPLETED -> PlanStatus.COMPLETED;
        };
    }
}
