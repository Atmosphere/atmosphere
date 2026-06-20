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
package org.atmosphere.ai.llm;

import java.util.Locale;

/**
 * Tri-state knob controlling whether a realization site emits the OpenAI
 * {@code prompt_cache_key} field on the wire.
 *
 * <p>Historically each realization site (the Built-in
 * {@link OpenAiCompatibleClient}, plus the LangChain4j / Spring AI adapters)
 * sniffed the configured base URL to decide whether the endpoint tolerates the
 * field. That heuristic silently flips its answer when a dependency or endpoint
 * swap changes the resolved host — a prompt-cache hint that emitted yesterday
 * can vanish tomorrow with no config change. This flag makes the decision
 * explicit and deterministic:</p>
 *
 * <ul>
 *   <li>{@link #ENABLED} — always emit {@code prompt_cache_key} regardless of host.</li>
 *   <li>{@link #DISABLED} — never emit {@code prompt_cache_key} regardless of host.</li>
 *   <li>{@link #AUTO} — defer to the realization site's host heuristic (the
 *       legacy behavior). This is the default and is byte-for-byte identical
 *       to the pre-flag wire output on every path.</li>
 * </ul>
 *
 * <p><strong>Note on AUTO divergence:</strong> the AUTO heuristics are NOT
 * shared across realization sites. The Built-in client uses an allow-list
 * (default-DENY: only {@code api.openai.com}, Azure OpenAI, and localhost emit),
 * while the LangChain4j and Spring AI adapters use a deny-list (default-ALLOW:
 * only Gemini's OpenAI-compat surface suppresses). This flag preserves that
 * pre-existing divergence under AUTO; converging the two heuristics is a
 * separate, flagged follow-up.</p>
 */
public enum PromptCacheKeyMode {

    /** Defer to the realization site's host heuristic (legacy behavior, default). */
    AUTO,

    /** Always emit {@code prompt_cache_key}, regardless of the configured host. */
    ENABLED,

    /** Never emit {@code prompt_cache_key}, regardless of the configured host. */
    DISABLED;

    /**
     * Resolve a final emit/suppress decision by combining this mode with the
     * realization site's AUTO host-heuristic result.
     *
     * <ul>
     *   <li>{@link #ENABLED} forces {@code true} (emit).</li>
     *   <li>{@link #DISABLED} forces {@code false} (suppress).</li>
     *   <li>{@link #AUTO} returns {@code autoHeuristicResult} unchanged.</li>
     * </ul>
     *
     * @param autoHeuristicResult the site's legacy host-based decision, consulted only under AUTO
     * @return {@code true} to emit {@code prompt_cache_key}, {@code false} to suppress
     */
    public boolean resolve(boolean autoHeuristicResult) {
        return switch (this) {
            case ENABLED -> true;
            case DISABLED -> false;
            case AUTO -> autoHeuristicResult;
        };
    }

    /**
     * Parse a tri-state value from a raw string, mirroring AiConfig's lenient
     * knob-parsing style. The mapping is case-insensitive and never throws:
     *
     * <ul>
     *   <li>{@code true} / {@code 1} / {@code yes} / {@code on} / {@code enabled} &rarr; {@link #ENABLED}</li>
     *   <li>{@code false} / {@code 0} / {@code no} / {@code off} / {@code disabled} &rarr; {@link #DISABLED}</li>
     *   <li>{@code auto} &rarr; {@link #AUTO}</li>
     *   <li>unset, blank, or any other token &rarr; {@link #AUTO}</li>
     * </ul>
     *
     * @param raw the raw configured value (may be {@code null})
     * @return the parsed mode, never {@code null}; malformed input collapses to {@link #AUTO}
     */
    public static PromptCacheKeyMode parse(String raw) {
        if (raw == null) {
            return AUTO;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on", "enabled" -> ENABLED;
            case "false", "0", "no", "off", "disabled" -> DISABLED;
            default -> AUTO;
        };
    }
}
