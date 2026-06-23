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
package org.atmosphere.ai;

/**
 * Tri-state control of provider-native structured-output enforcement — whether
 * the pipeline threads the JSON Schema generated for a request's
 * {@code responseType} into the resolved runtime's native structured-output API
 * (see {@link AiCapability#NATIVE_STRUCTURED_OUTPUT}).
 *
 * <p>Mirrors the {@link org.atmosphere.ai.llm.PromptCacheKeyMode} tri-state
 * convention so operators get one consistent knob shape across the AI module:</p>
 * <ul>
 *   <li>{@link #AUTO} (default) — enable native enforcement, but <em>fall back
 *       gracefully</em>: if a provider rejects the schema (e.g. an HTTP 400 on an
 *       unsupported JSON-Schema construct), re-dispatch once <em>without</em> the
 *       native option so the request still succeeds via the prompt-injection +
 *       parse path. Nothing that worked before the feature can regress.</li>
 *   <li>{@link #ENABLED} — enable native enforcement and <em>fail fast</em>: a
 *       provider schema rejection surfaces as an error rather than silently
 *       degrading. Use when a hard provider-level guarantee is required and a
 *       rejection should be loud.</li>
 *   <li>{@link #DISABLED} — never apply the native option; structured output is
 *       handled purely by the pipeline's prompt-injection + parse path
 *       ({@link AiCapability#STRUCTURED_OUTPUT}). This is the exact pre-feature
 *       behavior.</li>
 * </ul>
 *
 * <p>Resolved once per request from the {@code atmosphere.ai.native-structured-output}
 * system property (falling back to the {@code LLM_NATIVE_STRUCTURED_OUTPUT}
 * environment variable) via
 * {@link AiConfig#resolveNativeStructuredOutputMode()}. Parsing is lenient — an
 * unrecognized value collapses to {@link #AUTO} rather than throwing
 * (Correctness Invariant #4: fail safe at the boundary).</p>
 */
public enum NativeStructuredOutputMode {

    /** Apply the native option; on provider schema rejection, fall back to prompt-injection. */
    AUTO,

    /** Apply the native option; on provider schema rejection, surface the error. */
    ENABLED,

    /** Never apply the native option; prompt-injection + parse only (pre-feature behavior). */
    DISABLED;

    /**
     * Lenient parse of a tri-state value. {@code null}, blank, or unrecognized
     * input resolves to {@link #AUTO}; matching is case-insensitive and trims
     * surrounding whitespace.
     *
     * @param raw the configured value (may be {@code null})
     * @return the resolved mode, never {@code null}
     */
    public static NativeStructuredOutputMode parse(String raw) {
        if (raw == null) {
            return AUTO;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "enabled", "enable", "on", "true", "strict" -> ENABLED;
            case "disabled", "disable", "off", "false", "none" -> DISABLED;
            default -> AUTO;
        };
    }
}
