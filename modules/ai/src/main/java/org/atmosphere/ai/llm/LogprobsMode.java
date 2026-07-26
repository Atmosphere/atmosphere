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
 * Tri-state knob controlling whether the Built-in {@link OpenAiCompatibleClient}
 * emits the OpenAI chat-completions {@code logprobs: true} field when a
 * confidence elicitation is active for the request. Mirrors
 * {@link PromptCacheKeyMode} — the established shape for optional
 * OpenAI-compatible wire fields whose tolerance varies across compat proxies.
 *
 * <ul>
 *   <li>{@link #ENABLED} — always request logprobs (when elicitation is active),
 *       regardless of host.</li>
 *   <li>{@link #DISABLED} — never request logprobs, regardless of host.</li>
 *   <li>{@link #AUTO} — defer to the shared known-tolerant-endpoint allow-list
 *       ({@link CacheHint#endpointAcceptsPromptCacheKey(String)}: OpenAI, Azure
 *       OpenAI, loopback). This is the default.</li>
 * </ul>
 *
 * <p>{@code logprobs} is a standard chat-completions parameter, but several
 * OpenAI-compat layers reject or mishandle it (Groq documents it as
 * unsupported), so AUTO reuses the same default-deny host allow-list the
 * {@code prompt_cache_key} emission uses — one converged list of endpoints
 * empirically known to honor or gracefully ignore optional OpenAI fields.
 * Suppression only costs the richer {@link org.atmosphere.ai.AiConfidence.Source#LOGPROBS_NATIVE}
 * signal; the pipeline's {@code ConfidenceCapturingSession} model-reported-field
 * fallback still fires, while speculative emission risks a hard request
 * failure on a strict proxy.</p>
 */
public enum LogprobsMode {

    /** Defer to the shared endpoint allow-list (default). */
    AUTO,

    /** Always request logprobs when elicitation is active, regardless of host. */
    ENABLED,

    /** Never request logprobs, regardless of host. */
    DISABLED;

    /**
     * Resolve a final emit/suppress decision by combining this mode with the
     * AUTO host-allow-list result — identical contract to
     * {@link PromptCacheKeyMode#resolve(boolean)}.
     *
     * @param autoHeuristicResult the host-based decision, consulted only under AUTO
     * @return {@code true} to request logprobs, {@code false} to suppress
     */
    public boolean resolve(boolean autoHeuristicResult) {
        return switch (this) {
            case ENABLED -> true;
            case DISABLED -> false;
            case AUTO -> autoHeuristicResult;
        };
    }

    /**
     * Parse a tri-state value from a raw string, mirroring
     * {@link PromptCacheKeyMode#parse(String)}: case-insensitive, never throws,
     * unset/blank/unknown collapse to {@link #AUTO}.
     *
     * @param raw the raw configured value (may be {@code null})
     * @return the parsed mode, never {@code null}
     */
    public static LogprobsMode parse(String raw) {
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
