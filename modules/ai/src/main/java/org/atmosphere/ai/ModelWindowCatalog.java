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

import org.atmosphere.cpr.AtmosphereConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Per-model context-window registry: maps a model name/id to its maximum
 * context size in (estimated) tokens, so compaction can trigger relative to the
 * <em>actual</em> model's window instead of a flat one-size-fits-all budget.
 *
 * <p>Before this catalog, {@link TokenWindowStrategy} compacted every model at a
 * flat {@value TokenWindowStrategy#DEFAULT_MAX_TOKENS}-token budget — far too
 * small for a 128k / 200k / 1M model. This class lets the compaction path
 * ({@link CompactionConfig} → {@link TokenWindowCompaction}) size the budget to
 * the resolved model.</p>
 *
 * <h3>Overridable defaults (Boundary Safety)</h3>
 * <p>The built-in window sizes below are widely-known, round, <em>current</em>
 * defaults for common families — <strong>not guarantees</strong>. Each is
 * individually overridable via {@code org.atmosphere.ai.window.<model>} — read
 * first from the framework config (an {@link AtmosphereConfig} init-param), then
 * from a JVM system property — so a model whose window changes is a config edit,
 * not a code release. Example:
 * {@code -Dorg.atmosphere.ai.window.gpt-4o=131072}.</p>
 *
 * <h3>Never throws (Runtime Truth / Correctness Invariant #5)</h3>
 * <p>An unknown model — including {@code null}, blank, or a raw id absent from
 * the table — resolves to {@link #DEFAULT_CONTEXT_WINDOW_TOKENS}, which is
 * deliberately the historical flat budget so an unknown model is never compacted
 * more loosely than before the catalog existed (behavior never worse than
 * today). Lookups always return a value and never throw.</p>
 */
public final class ModelWindowCatalog {

    private static final Logger logger = LoggerFactory.getLogger(ModelWindowCatalog.class);

    /**
     * Config / system-property prefix for a per-model window override. The full
     * key is {@code org.atmosphere.ai.window.<model-id>} and the value is the
     * window size in tokens (a positive integer).
     */
    public static final String OVERRIDE_PREFIX = "org.atmosphere.ai.window.";

    /**
     * Conservative fallback context window (tokens) for a model that is neither
     * in the built-in table nor overridden. Pinned to
     * {@link TokenWindowStrategy#DEFAULT_MAX_TOKENS} so an unknown model behaves
     * exactly as it did before the catalog — the "never worse than today"
     * invariant. Intentionally small (not a real modern window) precisely so an
     * unknown model does not silently retain an unbounded amount of history.
     */
    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = TokenWindowStrategy.DEFAULT_MAX_TOKENS;

    // Widely-known, round context-window sizes (tokens). Overridable defaults,
    // NOT guarantees — see class Javadoc.
    private static final int W_128K = 128_000;
    private static final int W_200K = 200_000;
    private static final int W_1M = 1_000_000;

    /**
     * Exact model ids that already appear in {@link ModelTier} / {@link AiConfig}
     * defaults, mapped to their widely-known context windows. Fixed-size,
     * unmodifiable (no config-fed growth — Correctness Invariant #3).
     */
    private static final Map<String, Integer> EXACT = Map.of(
            "gpt-4o", W_128K,
            "gpt-4o-mini", W_128K,
            "o3", W_200K,
            "gemini-2.5-flash", W_1M,
            "gemini-2.5-pro", W_1M,
            "llama3.1", W_128K,
            "llama3.2", W_128K);

    private ModelWindowCatalog() {
    }

    /**
     * Context window for {@code model}, consulting only built-in defaults and
     * JVM system-property overrides (no framework config).
     *
     * @param model the model id (may be {@code null} or blank)
     * @return the window in tokens; {@link #DEFAULT_CONTEXT_WINDOW_TOKENS} when
     *         the model is unknown. Never throws.
     */
    public static int contextWindow(String model) {
        return contextWindow(null, model);
    }

    /**
     * Context window for {@code model}, consulting (in order) a
     * {@code org.atmosphere.ai.window.<model>} override — from {@code cfg} first,
     * then a JVM system property — then the built-in exact and family tables,
     * then {@link #DEFAULT_CONTEXT_WINDOW_TOKENS}.
     *
     * @param cfg   the framework config for init-param overrides (may be
     *              {@code null})
     * @param model the model id (may be {@code null} or blank)
     * @return the window in tokens; {@link #DEFAULT_CONTEXT_WINDOW_TOKENS} when
     *         the model is unknown. Never throws.
     */
    public static int contextWindow(AtmosphereConfig cfg, String model) {
        if (model == null || model.isBlank()) {
            return DEFAULT_CONTEXT_WINDOW_TOKENS;
        }
        var key = model.trim().toLowerCase(Locale.ROOT);

        var override = override(cfg, key);
        if (override.isPresent()) {
            return override.get();
        }
        var exact = EXACT.get(key);
        if (exact != null) {
            return exact;
        }
        var family = byFamily(key);
        if (family.isPresent()) {
            return family.get();
        }
        logger.debug("No context window known for model '{}' — using default {} tokens",
                model, DEFAULT_CONTEXT_WINDOW_TOKENS);
        return DEFAULT_CONTEXT_WINDOW_TOKENS;
    }

    /**
     * @param model the model id (may be {@code null} or blank)
     * @return the context window if {@code model} is a known/overridden model,
     *         otherwise empty (i.e. it would resolve to the default)
     */
    public static Optional<Integer> knownWindow(String model) {
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        var key = model.trim().toLowerCase(Locale.ROOT);
        var override = override(null, key);
        if (override.isPresent()) {
            return override;
        }
        var exact = EXACT.get(key);
        if (exact != null) {
            return Optional.of(exact);
        }
        return byFamily(key);
    }

    private static Optional<Integer> byFamily(String key) {
        // Prefix match so versioned ids (gpt-4o-2024-…, claude-sonnet-4-6,
        // gemini-2.5-flash-lite, llama3.3, …) resolve without an exact entry.
        if (key.startsWith("gpt-4.1")) {
            return Optional.of(W_1M);
        }
        if (key.startsWith("gpt-4o")) {
            return Optional.of(W_128K);
        }
        if (key.startsWith("o1") || key.startsWith("o3")) {
            return Optional.of(W_200K);
        }
        if (key.startsWith("claude")) {
            return Optional.of(W_200K);
        }
        if (key.startsWith("gemini")) {
            return Optional.of(W_1M);
        }
        if (key.startsWith("llama")) {
            return Optional.of(W_128K);
        }
        return Optional.empty();
    }

    private static Optional<Integer> override(AtmosphereConfig cfg, String key) {
        var propName = OVERRIDE_PREFIX + key;
        String raw = cfg != null ? cfg.getInitParameter(propName) : null;
        if (raw == null || raw.isBlank()) {
            raw = System.getProperty(propName);
        }
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        // Boundary Safety (Invariant #4): validate the override at the config
        // boundary — reject malformed / non-positive values instead of throwing.
        try {
            var tokens = Integer.parseInt(raw.trim());
            if (tokens > 0) {
                return Optional.of(tokens);
            }
            logger.warn("Ignoring non-positive context window override {}={}", propName, raw);
        } catch (NumberFormatException e) {
            logger.warn("Ignoring malformed context window override {}={}", propName, raw);
        }
        return Optional.empty();
    }
}
