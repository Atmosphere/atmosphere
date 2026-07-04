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

import java.util.Locale;

/**
 * Tri-state control of the harness PLANNING primitive's surface — whether the
 * built-in {@code write_todos} tool floor or a runtime's native plan surface
 * (see {@link org.atmosphere.ai.AiCapability#PLANNING}) maintains the agent's
 * {@link AgentPlan}.
 *
 * <p>Mirrors the {@link org.atmosphere.ai.NativeStructuredOutputMode} tri-state
 * convention so operators get one consistent knob shape across the AI module:</p>
 * <ul>
 *   <li>{@link #AUTO} (default) — native wins when the resolved runtime
 *       genuinely advertises {@code AiCapability.PLANNING}; otherwise the
 *       built-in {@code write_todos} floor registers. Never both — no
 *       duplicate plan tools.</li>
 *   <li>{@link #BUILTIN} — always register the built-in floor, even when the
 *       runtime has native machinery. Use when portable, provider-neutral
 *       behavior matters more than native fidelity.</li>
 *   <li>{@link #NATIVE} — never register the built-in floor; the plan surface
 *       exists only when the resolved runtime advertises the capability. A
 *       runtime without it leaves the primitive inactive (loudly logged).</li>
 * </ul>
 *
 * <p>Resolved once per endpoint registration from the
 * {@code atmosphere.ai.planning} system property (falling back to the
 * {@code LLM_PLANNING} environment variable) via
 * {@link org.atmosphere.ai.AiConfig#resolvePlanningMode()}. Parsing is
 * lenient — an unrecognized value collapses to {@link #AUTO} rather than
 * throwing (Correctness Invariant #4: fail safe at the boundary).</p>
 */
public enum PlanningMode {

    /** Native plan surface when the runtime advertises it; built-in floor otherwise. */
    AUTO,

    /** Always the built-in {@code write_todos} floor. */
    BUILTIN,

    /** Only the runtime's native plan surface; no built-in floor. */
    NATIVE;

    /**
     * Lenient parse of a tri-state value. {@code null}, blank, or unrecognized
     * input resolves to {@link #AUTO}; matching is case-insensitive and trims
     * surrounding whitespace.
     *
     * @param raw the configured value (may be {@code null})
     * @return the resolved mode, never {@code null}
     */
    public static PlanningMode parse(String raw) {
        if (raw == null) {
            return AUTO;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "builtin", "built-in" -> BUILTIN;
            case "native" -> NATIVE;
            default -> AUTO;
        };
    }
}
