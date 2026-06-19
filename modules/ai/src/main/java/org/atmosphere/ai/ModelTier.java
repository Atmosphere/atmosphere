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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves a provider-neutral model <em>tier alias</em> to a concrete provider
 * model name based on the active provider (detected from the runtime-resolved
 * base URL).
 *
 * <p>Three aliases are recognized (case-insensitive, surrounding whitespace
 * trimmed):</p>
 * <ul>
 *   <li>{@code "fast"} — the cheapest/lowest-latency model</li>
 *   <li>{@code "frontier"} — the most capable general-purpose model</li>
 *   <li>{@code "reasoning"} — the best reasoning/thinking model</li>
 * </ul>
 *
 * <p>This is purely <strong>additive and opt-in</strong>. Any value that is not
 * one of those three tokens — including raw model strings ({@code "gpt-4o"},
 * {@code "claude-sonnet-4-6"}), the empty string, and {@code null} — passes
 * through {@linkplain #resolve(String, String, String) byte-for-byte unchanged}.
 * That is the backward-compatibility invariant: callers that never use a tier
 * alias see exactly the behavior they had before.</p>
 *
 * <h3>Provider detection (Runtime Truth)</h3>
 * <p>The provider is detected <em>only</em> from the runtime-resolved base URL
 * (e.g. {@link AiConfig.LlmSettings#baseUrl()}), never from classpath presence.
 * Detection mirrors the host substrings already used by
 * {@code AiConfig.resolveBaseUrl} and {@code OpenAiCompatibleClient}.</p>
 *
 * <h3>Overridable defaults (Boundary Safety)</h3>
 * <p>The concrete (provider, tier) names below are sensible <em>current</em>
 * defaults, <strong>not guarantees</strong>. Each is individually overridable
 * via the system property
 * {@code org.atmosphere.ai.tier.<provider>.<tier>} (e.g.
 * {@code -Dorg.atmosphere.ai.tier.openai.frontier=gpt-4.1}), so a model id that
 * goes stale is a config edit, not a code release.</p>
 *
 * <p>If a tier is requested but the (provider, tier) pair cannot be mapped (for
 * example an unknown provider), the supplied {@code defaultModel} is returned
 * and the event is logged — this method <strong>never throws</strong>, so a bad
 * alias yields a working chat, not a 500.</p>
 */
public final class ModelTier {

    private static final Logger logger = LoggerFactory.getLogger(ModelTier.class);

    /** Recognized tier alias: cheapest / lowest-latency. */
    public static final String FAST = "fast";
    /** Recognized tier alias: most capable general-purpose. */
    public static final String FRONTIER = "frontier";
    /** Recognized tier alias: best reasoning / thinking. */
    public static final String REASONING = "reasoning";

    /** System-property prefix for per-(provider,tier) overrides. */
    public static final String OVERRIDE_PREFIX = "org.atmosphere.ai.tier.";

    // Provider keys used both for the built-in table and for override property names.
    private static final String OPENAI = "openai";
    private static final String AZURE = "azure";
    private static final String GEMINI = "gemini";
    private static final String OLLAMA = "ollama";

    /**
     * Built-in default model per (provider, tier). Each concrete name already
     * appears elsewhere in the codebase (gpt-4o-mini, gpt-4o, o3,
     * gemini-2.5-flash, gemini-2.5-pro) or is a well-known current model id.
     * These are overridable defaults, not guarantees — see class Javadoc.
     */
    private static final Map<String, Map<String, String>> DEFAULTS = Map.of(
            OPENAI, Map.of(
                    FAST, "gpt-4o-mini",
                    FRONTIER, "gpt-4o",
                    REASONING, "o3"),
            AZURE, Map.of(
                    FAST, "gpt-4o-mini",
                    FRONTIER, "gpt-4o",
                    REASONING, "o3"),
            GEMINI, Map.of(
                    FAST, "gemini-2.5-flash",
                    FRONTIER, "gemini-2.5-pro",
                    REASONING, "gemini-2.5-pro"),
            OLLAMA, Map.of(
                    FAST, "llama3.2",
                    FRONTIER, "llama3.1",
                    REASONING, "llama3.1"));

    private ModelTier() {
    }

    /**
     * Resolve a requested model value to an effective model name.
     *
     * <p>Resolution rules, in order:</p>
     * <ol>
     *   <li>{@code requested} is {@code null} or blank → returned unchanged
     *       (passthrough).</li>
     *   <li>{@code requested} (trimmed, case-insensitive) is not one of
     *       {@link #FAST}/{@link #FRONTIER}/{@link #REASONING} → returned
     *       <strong>unchanged</strong> (raw passthrough — the backward-compat
     *       invariant).</li>
     *   <li>{@code requested} is a tier token → the provider is detected from
     *       {@code baseUrl} and the concrete model for (provider, tier) is
     *       returned, honoring any {@code org.atmosphere.ai.tier.<provider>.<tier>}
     *       system-property override over the built-in table.</li>
     *   <li>The (provider, tier) pair is unmapped (e.g. unknown provider) →
     *       {@code defaultModel} is returned and the event is logged. This method
     *       never throws.</li>
     * </ol>
     *
     * @param requested    the requested model value (a tier alias, a raw model
     *                     id, blank, or {@code null})
     * @param baseUrl      the runtime-resolved provider base URL (used only for
     *                     provider detection; may be {@code null})
     * @param defaultModel the fallback returned when a tier alias cannot be
     *                     mapped to a concrete model
     * @return the effective model name; {@code requested} unchanged for any
     *         non-alias value
     */
    public static String resolve(String requested, String baseUrl, String defaultModel) {
        // Rule 1: null/blank passes through untouched.
        if (requested == null || requested.isBlank()) {
            return requested;
        }

        var token = requested.trim().toLowerCase(Locale.ROOT);
        if (!isTier(token)) {
            // Rule 2: any non-alias value (raw model id) passes through
            // byte-for-byte. This is the critical backward-compat invariant.
            return requested;
        }

        // Rule 3: it is a tier alias — detect provider from runtime base URL only.
        var provider = detectProvider(baseUrl);
        if (provider != null) {
            // System-property override wins over the built-in table.
            var override = System.getProperty(OVERRIDE_PREFIX + provider + "." + token);
            if (override != null && !override.isBlank()) {
                logger.debug("Resolved model tier '{}' for provider '{}' to override '{}'",
                        token, provider, override);
                return override;
            }
            var byProvider = DEFAULTS.get(provider);
            if (byProvider != null) {
                var concrete = byProvider.get(token);
                if (concrete != null) {
                    logger.debug("Resolved model tier '{}' for provider '{}' to '{}'",
                            token, provider, concrete);
                    return concrete;
                }
            }
        }

        // Rule 4: unmapped (provider, tier) — fall back, never throw.
        logger.info("Model tier '{}' could not be mapped for base URL '{}' "
                + "(provider={}); falling back to '{}'", token, baseUrl, provider, defaultModel);
        return defaultModel;
    }

    /**
     * @return {@code true} if {@code requested} (trimmed, case-insensitive) is
     *         one of the recognized tier aliases.
     */
    public static boolean isTier(String requested) {
        if (requested == null) {
            return false;
        }
        var token = requested.trim().toLowerCase(Locale.ROOT);
        return FAST.equals(token) || FRONTIER.equals(token) || REASONING.equals(token);
    }

    /**
     * Detect the provider key from the runtime-resolved base URL. Mirrors the
     * host substrings used by {@code AiConfig.resolveBaseUrl} and
     * {@code OpenAiCompatibleClient}. Returns {@code null} for an unknown or
     * absent base URL (Runtime Truth: detection reads only the URL, never the
     * classpath).
     */
    private static String detectProvider(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        var url = baseUrl.toLowerCase(Locale.ROOT);
        if (url.contains(".openai.azure.com")) {
            return AZURE;
        }
        if (url.contains("api.openai.com")) {
            return OPENAI;
        }
        if (url.contains("generativelanguage.googleapis.com")) {
            return GEMINI;
        }
        // Ollama's OpenAI-compatible gateway: localhost / 127.0.0.1 on :11434.
        if (url.contains("localhost:11434") || url.contains("127.0.0.1:11434")) {
            return OLLAMA;
        }
        return null;
    }
}
