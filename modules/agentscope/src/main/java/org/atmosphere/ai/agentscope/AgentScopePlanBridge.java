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

import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.model.Plan;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.plan.AgentPlan;
import org.atmosphere.ai.plan.AgentPlanStore;
import org.atmosphere.ai.plan.FileSystemAgentPlanStore;

/**
 * Provisions the per-request AgentScope {@link PlanNotebook} that delegates
 * the harness PLANNING primitive to AgentScope's native plan surface
 * ({@code create_plan} / {@code update_subtask_state} / {@code finish_subtask}
 * / ... model-facing tools plus per-step hint injection):
 *
 * <ul>
 *   <li><b>Persistence</b> — the notebook's storage is an
 *       {@link AtmospherePlanStorage} keyed {@code agentId × conversationId},
 *       so Atmosphere's {@link AgentPlanStore} owns the plan state regardless
 *       of which surface maintains it.</li>
 *   <li><b>Observability</b> — a change hook mirrors every live-plan mutation
 *       into the store and emits {@link AiEvent.PlanUpdate} on the request's
 *       {@link StreamingSession}, so consoles render the plan exactly as they
 *       do for the built-in {@code write_todos} floor.</li>
 *   <li><b>Continuity</b> — when the store holds a plan with open steps from
 *       a previous turn, provisioning re-hydrates it through
 *       {@code recover_historical_plan} against the deterministic scope id,
 *       so the per-request notebook rebuild never loses the conversation's
 *       plan. Terminal snapshots are not resurrected.</li>
 * </ul>
 *
 * <p><b>Ownership / terminal paths:</b> the notebook is created per request
 * and referenced only by the rebuilt {@link io.agentscope.core.ReActAgent}
 * dispatched for that request — it holds no global registrations, threads, or
 * native resources, so it is unreachable (and collectable) as soon as the
 * request's stream terminates on any path. The change hook lives and dies
 * with the notebook; no uninstall step is required.</p>
 *
 * <p><b>Bounds (Correctness Invariant #3):</b> {@code maxSubtasks} mirrors
 * {@link FileSystemAgentPlanStore#MAX_STEPS} so an over-limit plan is
 * rejected at the model-tool boundary with AgentScope's clear message before
 * it can ever reach (and be rejected by) the store's own step bound.</p>
 */
public final class AgentScopePlanBridge {

    /** Registration id of the change hook installed on each notebook. */
    static final String CHANGE_HOOK_ID = "atmosphere-plan-update";

    private AgentScopePlanBridge() {
    }

    /**
     * Build the request-scoped {@link PlanNotebook} bridged to Atmosphere's
     * plan surface, re-hydrated from the store when the conversation already
     * has a live plan.
     *
     * <p>{@code needUserConfirm} is disabled for parity with the built-in
     * {@code write_todos} floor (Correctness Invariant #7 — the portable
     * surface never gates plan execution on an extra confirmation turn, so
     * the native surface must not either).</p>
     *
     * @param store          the Atmosphere plan store resolved from the
     *                       endpoint's injectables (never {@code null})
     * @param agentId        the plan owner scope (never {@code null} or blank)
     * @param conversationId the conversation scope (never {@code null} or blank)
     * @param session        the live streaming session receiving
     *                       {@link AiEvent.PlanUpdate} (never {@code null})
     * @return the bridged notebook, never {@code null}
     */
    public static PlanNotebook provision(AgentPlanStore store, String agentId,
                                         String conversationId, StreamingSession session) {
        if (session == null) {
            throw new IllegalArgumentException("session is required");
        }
        var storage = new AtmospherePlanStorage(store, agentId, conversationId);
        var notebook = PlanNotebook.builder()
                .storage(storage)
                .maxSubtasks(FileSystemAgentPlanStore.MAX_STEPS)
                .needUserConfirm(false)
                .build();
        notebook.addChangeHook(CHANGE_HOOK_ID,
                (source, plan) -> onPlanChanged(storage, session, plan));
        // Re-hydrate the previous turn's live plan (the notebook is rebuilt
        // per request, so continuity comes from the store, not the instance).
        // recover_historical_plan resolves synchronously against the store,
        // fires the change hook (console sees the current plan at turn
        // start), and no-ops when the storage has nothing live to recover.
        storage.livePlanId().ifPresent(planId ->
                notebook.recoverHistoricalPlan(planId).block());
        return notebook;
    }

    /**
     * Change-hook body. A non-null plan is a live mutation: mirror it into
     * the store, then emit the update. A null plan means the notebook just
     * closed its plan ({@code finish_plan} persisted the terminal snapshot
     * through {@link AtmospherePlanStorage#addPlan} before this hook fired)
     * — emit the persisted terminal snapshot so the console renders the
     * plan's final state instead of a silent disappearance.
     */
    private static void onPlanChanged(AtmospherePlanStorage storage,
                                      StreamingSession session, Plan plan) {
        AgentPlan snapshot;
        if (plan != null) {
            snapshot = AtmospherePlanStorage.toAgentPlan(plan);
            storage.mirror(snapshot);
        } else {
            snapshot = storage.storedPlan().orElseGet(AgentPlan::empty);
        }
        session.emit(new AiEvent.PlanUpdate(snapshot.toWireSteps(), snapshot.goal()));
    }
}
