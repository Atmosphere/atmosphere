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
package org.atmosphere.ai.preset;

import org.atmosphere.ai.CompactionConfig;
import org.atmosphere.ai.llm.CacheHint;
import org.atmosphere.ai.llm.PromptCacheDefaults;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The deep-agent preset: one framework-level switch that turns on Atmosphere's
 * existing deep-agent primitives for every {@code @AiEndpoint} /
 * {@code @Coordinator} instead of requiring per-endpoint wiring.
 *
 * <p>Enable with the framework init-param
 * {@code org.atmosphere.ai.deep-agent.enabled=true} (default {@code false}).
 * Individual endpoints opt out via
 * {@code org.atmosphere.ai.deep-agent.exclude-paths}, a comma-separated list
 * of exact endpoint paths.</p>
 *
 * <p>Semantics per primitive when the preset is enabled for a path:</p>
 * <ul>
 *   <li><b>conversation-memory</b> — {@code AiEndpointProcessor} resolves a
 *       conversation memory even when the annotation's
 *       {@code conversationMemory()} is {@code false}. An annotation value of
 *       {@code true} always wins (the preset never turns memory off).</li>
 *   <li><b>long-term-memory</b> — a framework-built
 *       {@link org.atmosphere.ai.memory.LongTermMemoryInterceptor} is appended
 *       after the user's interceptors, backed by the store resolved through
 *       {@link org.atmosphere.ai.memory.LongTermMemories}. Skipped when the
 *       user already declared a {@code LongTermMemoryInterceptor}.</li>
 *   <li><b>prompt-cache-default</b> — endpoints whose annotation keeps
 *       {@code promptCache = NONE} are seeded with
 *       {@link CacheHint.CachePolicy#CONSERVATIVE} unless the independent
 *       {@link PromptCacheDefaults#DEFAULT_KEY} init-param overrides it.</li>
 *   <li><b>delegation</b> — {@code CoordinatorProcessor} registers the
 *       built-in {@code delegate_task} tool and wraps the fleet with the
 *       installed governance policies.</li>
 *   <li><b>compaction</b> — reported from the independent
 *       {@link CompactionConfig} seam (the preset keeps the sliding-window
 *       default; it does not force summarizing).</li>
 *   <li><b>skills</b> — reported as {@code CONVENTION}: skills remain
 *       per-agent convention-discovered ({@code META-INF/skills/<name>/SKILL.md}),
 *       there is no global switch.</li>
 *   <li><b>durable-runs</b> — reported as {@code CONTAINER-MANAGED}: the
 *       journal spine is installed by the Spring / Quarkus bridge, never by
 *       this core class.</li>
 * </ul>
 *
 * <p>Runtime truth (Correctness Invariant #5): {@link #install} publishes a
 * live, concurrent primitive-to-state map under
 * {@link #RUNTIME_STATE_PROPERTY} so consoles report the confirmed state —
 * processors update entries (e.g. the resolved long-term-memory store class)
 * as primitives genuinely attach, never at configuration intent.</p>
 */
public final class DeepAgentPreset {

    private static final Logger logger = LoggerFactory.getLogger(DeepAgentPreset.class);

    /** Init-param: master switch. Default {@code false}. */
    public static final String ENABLED_KEY = "org.atmosphere.ai.deep-agent.enabled";

    /** Init-param: comma-separated exact endpoint paths the preset skips. */
    public static final String EXCLUDE_PATHS_KEY = "org.atmosphere.ai.deep-agent.exclude-paths";

    /** Property-bag key under which the installed preset instance is stashed (one-shot marker). */
    public static final String PRESET_PROPERTY = "org.atmosphere.ai.deep-agent.preset";

    /** Property-bag key under which the live primitive-to-state map is published. */
    public static final String RUNTIME_STATE_PROPERTY = "org.atmosphere.ai.deep-agent.runtime-state";

    /** Runtime-state primitive keys. */
    public static final String PRIMITIVE_CONVERSATION_MEMORY = "conversation-memory";
    public static final String PRIMITIVE_COMPACTION = "compaction";
    public static final String PRIMITIVE_LONG_TERM_MEMORY = "long-term-memory";
    public static final String PRIMITIVE_PROMPT_CACHE_DEFAULT = "prompt-cache-default";
    public static final String PRIMITIVE_DELEGATION = "delegation";
    public static final String PRIMITIVE_SKILLS = "skills";
    public static final String PRIMITIVE_DURABLE_RUNS = "durable-runs";

    private final boolean enabled;
    private final Set<String> excludePaths;
    private final ConcurrentHashMap<String, String> runtimeState = new ConcurrentHashMap<>();

    private DeepAgentPreset(boolean enabled, Set<String> excludePaths) {
        this.enabled = enabled;
        this.excludePaths = excludePaths;
    }

    /**
     * Resolve and install the preset exactly once per framework, mirroring
     * the {@code MemorySafetyConfig.installedFrom} one-shot pattern: the
     * first caller parses the init-params, publishes the runtime-state map,
     * and stashes the instance in the property bag; every later call returns
     * the already-installed instance. A framework without an
     * {@link AtmosphereConfig} (bare test mocks) yields a disabled,
     * unpublished preset so callers never need a null check.
     *
     * @param framework the framework being configured
     * @return the installed (or disabled fallback) preset — never {@code null}
     */
    public static DeepAgentPreset install(AtmosphereFramework framework) {
        var cfg = framework != null ? framework.getAtmosphereConfig() : null;
        if (cfg == null) {
            return new DeepAgentPreset(false, Set.of());
        }
        if (cfg.properties().get(PRESET_PROPERTY) instanceof DeepAgentPreset installed) {
            return installed;
        }
        var built = fromConfig(cfg);
        if (cfg.properties().putIfAbsent(PRESET_PROPERTY, built) instanceof DeepAgentPreset raced) {
            return raced;
        }
        built.seedRuntimeState(cfg);
        cfg.properties().putIfAbsent(RUNTIME_STATE_PROPERTY, built.runtimeState);
        if (built.enabled) {
            logger.info("Deep-agent preset enabled (exclude-paths: {})",
                    built.excludePaths.isEmpty() ? "none" : built.excludePaths);
        }
        return built;
    }

    private static DeepAgentPreset fromConfig(AtmosphereConfig cfg) {
        var enabled = cfg.getInitParameter(ENABLED_KEY, false);
        var raw = cfg.getInitParameter(EXCLUDE_PATHS_KEY);
        var excludes = new java.util.LinkedHashSet<String>();
        if (raw != null && !raw.isBlank()) {
            for (var path : raw.split(",")) {
                var trimmed = path.trim();
                if (!trimmed.isEmpty()) {
                    excludes.add(trimmed);
                }
            }
        }
        return new DeepAgentPreset(enabled, Set.copyOf(excludes));
    }

    /**
     * Seed the published map with the state each primitive genuinely has at
     * install time. Attach-time truths (the resolved long-term-memory store,
     * a registered delegate_task tool) are upgraded later by the processors
     * via {@link #updateRuntimeState}.
     */
    private void seedRuntimeState(AtmosphereConfig cfg) {
        var disabledState = "INACTIVE(disabled)";
        runtimeState.put(PRIMITIVE_CONVERSATION_MEMORY, enabled ? "ACTIVE" : disabledState);
        runtimeState.put(PRIMITIVE_LONG_TERM_MEMORY,
                enabled ? "INACTIVE(no-endpoint)" : disabledState);
        runtimeState.put(PRIMITIVE_DELEGATION,
                enabled ? "INACTIVE(no-coordinator)" : disabledState);
        runtimeState.put(PRIMITIVE_COMPACTION, CompactionConfig.resolve(cfg).name());
        runtimeState.put(PRIMITIVE_PROMPT_CACHE_DEFAULT,
                PromptCacheDefaults.effective(CacheHint.CachePolicy.NONE, cfg, enabled)
                        .name().toLowerCase(Locale.ROOT));
        runtimeState.put(PRIMITIVE_SKILLS, "CONVENTION");
        runtimeState.put(PRIMITIVE_DURABLE_RUNS, "CONTAINER-MANAGED");
    }

    /** Whether the master switch is on for this framework. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Whether the preset applies to the given endpoint path: the master
     * switch is on and the path is not listed in
     * {@link #EXCLUDE_PATHS_KEY} (exact match).
     *
     * @param path the endpoint path as declared on the annotation
     * @return {@code true} when the preset's primitives should attach
     */
    public boolean enabledFor(String path) {
        return enabled && path != null && !excludePaths.contains(path);
    }

    /**
     * Update a primitive's entry in the published runtime-state map. The map
     * instance is shared with the property bag, so consoles observe the
     * change without re-resolution.
     *
     * @param primitive one of the {@code PRIMITIVE_*} keys
     * @param state     the confirmed state, e.g. {@code ACTIVE(<store class>)}
     */
    public void updateRuntimeState(String primitive, String state) {
        if (primitive != null && state != null) {
            runtimeState.put(primitive, state);
        }
    }

    /** Immutable snapshot of the published runtime-state map (test / console use). */
    public Map<String, String> runtimeState() {
        return Map.copyOf(runtimeState);
    }
}
