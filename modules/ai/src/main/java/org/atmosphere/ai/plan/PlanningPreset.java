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

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.preset.HarnessPreset;
import org.atmosphere.ai.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;

/**
 * The single attach engine for the harness PLANNING primitive, shared by the
 * {@code @AiEndpoint} / {@code @Agent} / {@code @Coordinator} processors'
 * package-private {@code registerPresetPlanning} seams (Correctness
 * Invariant #7, Mode Parity — one decision path, three callers).
 *
 * <p>When the feature resolves for a path, the {@link AgentPlanStore} always
 * enters the injectables (user {@code @AiTool} methods and native bridges
 * key plan state through it), and the surface is picked by
 * {@link PlanningMode} against the resolved runtime's <em>declared</em>
 * capabilities — {@link AiCapability#PLANNING} is itself a runtime-truth
 * contract, so the registration-time check reflects genuine native wiring,
 * not classpath guessing. Native wins in AUTO; the built-in
 * {@code write_todos} floor registers otherwise. Never both (no duplicate
 * plan tools). Runtime-state flips only on a genuine attach
 * (Invariant #5).</p>
 */
public final class PlanningPreset {

    private static final Logger logger = LoggerFactory.getLogger(PlanningPreset.class);

    private PlanningPreset() {
    }

    /**
     * Attach the PLANNING primitive for one endpoint when the feature is on.
     *
     * @param toolRegistry the endpoint's tool registry
     * @param preset       the installed harness preset (runtime-state sink)
     * @param featureOn    whether {@code Harness.PLANNING} resolved for the path
     * @param runtime      the resolved {@link AgentRuntime} the endpoint will
     *                     dispatch through (may be {@code null} on degraded paths)
     * @param injectables  the endpoint's injectables map — receives the
     *                     {@link AgentPlanStore}
     * @param agentRoot    the owner's workspace root (plans persist under
     *                     {@code {agentRoot}/plans/})
     * @param ownerName    the agent / endpoint name (default plan key + logs)
     */
    public static void register(ToolRegistry toolRegistry, HarnessPreset preset,
                                boolean featureOn, AgentRuntime runtime,
                                Map<Class<?>, Object> injectables,
                                Path agentRoot, String ownerName) {
        if (!featureOn) {
            return;
        }
        var store = new FileSystemAgentPlanStore(agentRoot);
        injectables.put(AgentPlanStore.class, store);
        // Publish the same instance for control-plane resolution (the admin
        // REST plan endpoint, the console Workspace tab) — never a twin.
        preset.registerPlanStore(ownerName, store);

        var mode = AiConfig.resolvePlanningMode();
        var nativeCapable = runtime != null
                && runtime.capabilities().contains(AiCapability.PLANNING);
        switch (mode) {
            case NATIVE -> {
                if (nativeCapable) {
                    activateNative(preset, runtime, ownerName);
                } else {
                    preset.updateRuntimeState(HarnessPreset.PRIMITIVE_PLANNING,
                            "INACTIVE(native-unavailable)");
                    logger.warn("Planning mode NATIVE but runtime {} does not advertise "
                                    + "PLANNING — no plan surface for '{}'",
                            runtime != null ? runtime.name() : "<none>", ownerName);
                }
            }
            case AUTO -> {
                if (nativeCapable) {
                    activateNative(preset, runtime, ownerName);
                } else {
                    registerBuiltin(toolRegistry, preset, ownerName);
                }
            }
            case BUILTIN -> registerBuiltin(toolRegistry, preset, ownerName);
        }
    }

    private static void activateNative(HarnessPreset preset, AgentRuntime runtime,
                                       String ownerName) {
        // Native wins: the runtime's own plan machinery maintains the plan —
        // the built-in floor is NOT registered (no duplicate plan tools).
        preset.updateRuntimeState(HarnessPreset.PRIMITIVE_PLANNING,
                "ACTIVE(native:" + runtime.name() + ")");
        logger.info("Harness planning delegated to native runtime '{}' for '{}'",
                runtime.name(), ownerName);
    }

    private static void registerBuiltin(ToolRegistry toolRegistry, HarnessPreset preset,
                                        String ownerName) {
        if (toolRegistry.getTool(PlanningTools.WRITE_TODOS).isPresent()) {
            // A plan surface exists — the user's own tool. Report it as such
            // (same convention as the coordinator's ACTIVE(user-tool) for a
            // hand-written delegate_task) instead of leaving the INACTIVE seed.
            preset.updateRuntimeState(HarnessPreset.PRIMITIVE_PLANNING, "ACTIVE(user-tool)");
            logger.warn("Harness planning floor skipped for '{}': a '{}' tool is already "
                    + "registered (user tool wins)", ownerName, PlanningTools.WRITE_TODOS);
            return;
        }
        toolRegistry.register(PlanningTools.writeTodosTool(ownerName));
        preset.updateRuntimeState(HarnessPreset.PRIMITIVE_PLANNING, "ACTIVE(builtin)");
        logger.info("Harness registered {} for '{}'", PlanningTools.WRITE_TODOS, ownerName);
    }
}
