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

/**
 * Config seam for the conversation-memory compaction strategy. Before this
 * seam existed, {@link InMemoryConversationMemory} hardwired
 * {@link SlidingWindowCompaction} — {@link SummarizingCompaction} compiled but
 * had no production caller. All three memory construction sites
 * ({@code AiEndpointProcessor}, {@code AgentProcessor},
 * {@code CoordinatorProcessor}) resolve through this class so the strategy is
 * identical across invocation modes (Correctness Invariant #7 — Mode Parity).
 *
 * <pre>
 * org.atmosphere.ai.compaction               = sliding-window (default) | summarizing | token-window
 * org.atmosphere.ai.compaction.recent-window = &lt;int&gt;   # summarizing only; messages kept verbatim
 * </pre>
 *
 * <p>{@code token-window} sizes the eviction budget to the resolved model's
 * actual context window via {@link ModelWindowCatalog} (128k / 200k / 1M …)
 * instead of a flat message count, so a large-window model is not needlessly
 * compacted. The model is the one passed to {@link #resolve(AtmosphereConfig,
 * String)}, falling back to the configured {@link AiConfig#LLM_MODEL} and then a
 * documented default — an unknown model degrades to the historical flat budget,
 * never worse than before.</p>
 */
public final class CompactionConfig {

    private static final Logger logger = LoggerFactory.getLogger(CompactionConfig.class);

    /** Init-param: strategy name. Default {@code sliding-window}. */
    public static final String STRATEGY_KEY = "org.atmosphere.ai.compaction";

    /** Init-param: recent-message window preserved verbatim by {@code summarizing}. */
    public static final String RECENT_WINDOW_KEY = "org.atmosphere.ai.compaction.recent-window";

    /** Strategy value selecting model-aware {@link TokenWindowCompaction}. */
    public static final String TOKEN_WINDOW = "token-window";

    private CompactionConfig() {
    }

    /**
     * Resolve the configured compaction strategy, falling back to
     * {@link SlidingWindowCompaction} when the config is absent, the value is
     * unset, or the value is unknown (logged at WARN — never fail startup over
     * a typo in an eviction knob).
     *
     * @param cfg the framework config (may be {@code null} in tests)
     * @return the strategy to hand to {@link InMemoryConversationMemory}
     */
    public static AiCompactionStrategy resolve(AtmosphereConfig cfg) {
        return resolve(cfg, null);
    }

    /**
     * Model-aware variant of {@link #resolve(AtmosphereConfig)}. Identical for
     * {@code sliding-window} / {@code summarizing}; for {@code token-window} the
     * eviction budget is {@code model}'s context window from
     * {@link ModelWindowCatalog}. When {@code model} is {@code null}/blank the
     * configured {@link AiConfig#LLM_MODEL} is used, then a documented default —
     * so an unknown model is never compacted more loosely than before.
     *
     * @param cfg   the framework config (may be {@code null} in tests)
     * @param model the resolved model id (may be {@code null}/blank)
     * @return the strategy to hand to {@link InMemoryConversationMemory}
     */
    public static AiCompactionStrategy resolve(AtmosphereConfig cfg, String model) {
        if (cfg == null) {
            return new SlidingWindowCompaction();
        }
        var value = cfg.getInitParameter(STRATEGY_KEY);
        if (value == null || value.isBlank()
                || "sliding-window".equalsIgnoreCase(value.trim())) {
            return new SlidingWindowCompaction();
        }
        var strategy = value.trim();
        if ("summarizing".equalsIgnoreCase(strategy)) {
            var window = cfg.getInitParameter(RECENT_WINDOW_KEY, 0);
            return window > 0
                    ? new LlmSummarizingCompaction(window)
                    : new LlmSummarizingCompaction();
        }
        if (TOKEN_WINDOW.equalsIgnoreCase(strategy)) {
            var effective = effectiveModel(cfg, model);
            var budget = ModelWindowCatalog.contextWindow(cfg, effective);
            logger.debug("Compaction '{}' sized to model '{}' context window: {} tokens",
                    TOKEN_WINDOW, effective, budget);
            return new TokenWindowCompaction(budget);
        }
        logger.warn("Unknown {} value '{}' — using sliding-window", STRATEGY_KEY, value);
        return new SlidingWindowCompaction();
    }

    /**
     * Resolve the model to size a {@code token-window} budget against: the
     * explicit {@code model} if present, else the configured
     * {@link AiConfig#LLM_MODEL}, else {@link AiConfig#DEFAULT_MODEL}.
     */
    private static String effectiveModel(AtmosphereConfig cfg, String model) {
        if (model != null && !model.isBlank()) {
            return model;
        }
        var configured = cfg.getInitParameter(AiConfig.LLM_MODEL);
        return configured != null && !configured.isBlank() ? configured : AiConfig.DEFAULT_MODEL;
    }

    /**
     * Runtime truth for the persistence path: {@link PersistentConversationMemory}
     * hard-wires sliding-window eviction, so a non-default {@link #STRATEGY_KEY}
     * is silently ignored there. Warn once per construction site so operators
     * are not left believing summarizing compaction is active.
     *
     * @param cfg  the framework config (may be {@code null})
     * @param site the construction site name (logging only)
     */
    public static void warnIfIgnoredByPersistence(AtmosphereConfig cfg, String site) {
        var value = cfg != null ? cfg.getInitParameter(STRATEGY_KEY) : null;
        if (value != null && !value.isBlank()
                && !"sliding-window".equalsIgnoreCase(value.trim())) {
            logger.warn("{}: {}='{}' has no effect — PersistentConversationMemory "
                    + "hard-wires sliding-window eviction", site, STRATEGY_KEY, value);
        }
    }
}
