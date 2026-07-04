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
 * The harness preset: resolves which deep-agent primitives attach to each
 * {@code @Agent} / {@code @AiEndpoint} / {@code @Coordinator} from the
 * annotations' granular {@link Harness} attribute and the app-wide config,
 * instead of requiring per-endpoint wiring.
 *
 * <p>The app-wide init-param {@code org.atmosphere.ai.harness.enabled} is
 * tri-state: unset (the default) leaves the decision to each annotation,
 * {@code true} turns the full harness on for every {@code @AiEndpoint} whose
 * annotation stays bare, and {@code false} is the operational kill switch —
 * harness features stay off everywhere, beating every annotation. Individual
 * endpoints opt out via {@code org.atmosphere.ai.harness.exclude-paths}, a
 * comma-separated list of exact endpoint paths.</p>
 *
 * <p>Semantics per primitive when a harness feature resolves for a path:</p>
 * <ul>
 *   <li><b>conversation-memory</b> ({@link Harness#MEMORY}) —
 *       {@code AiEndpointProcessor} resolves a conversation memory even when
 *       the annotation's {@code conversationMemory()} is {@code false}. An
 *       annotation value of {@code true} always wins (the harness never turns
 *       memory off).</li>
 *   <li><b>long-term-memory</b> ({@link Harness#MEMORY}) — a framework-built
 *       {@link org.atmosphere.ai.memory.LongTermMemoryInterceptor} is appended
 *       after the user's interceptors, backed by the store resolved through
 *       {@link org.atmosphere.ai.memory.LongTermMemories}. Skipped when the
 *       user already declared a {@code LongTermMemoryInterceptor}.</li>
 *   <li><b>prompt-cache-default</b> ({@link Harness#CACHE}) — endpoints whose
 *       annotation keeps {@code promptCache = NONE} are seeded with
 *       {@link CacheHint.CachePolicy#CONSERVATIVE} unless the independent
 *       {@link PromptCacheDefaults#DEFAULT_KEY} init-param overrides it.</li>
 *   <li><b>delegation</b> ({@link Harness#DELEGATION}) —
 *       {@code CoordinatorProcessor} registers the built-in
 *       {@code delegate_task} tool and wraps the fleet with the installed
 *       governance policies.</li>
 *   <li><b>compaction</b> ({@link Harness#MEMORY}) — reported from the
 *       independent {@link CompactionConfig} seam (the harness keeps the
 *       sliding-window default; it does not force summarizing).</li>
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
 * as primitives genuinely attach, never at configuration intent. A feature
 * absent from the resolved set stays {@code INACTIVE(disabled)}.</p>
 */
public final class HarnessPreset {

    private static final Logger logger = LoggerFactory.getLogger(HarnessPreset.class);

    /** Init-param: tri-state app-wide switch — unset, {@code true}, or the {@code false} kill switch. */
    public static final String ENABLED_KEY = "org.atmosphere.ai.harness.enabled";

    /** Init-param: comma-separated exact endpoint paths the harness skips. */
    public static final String EXCLUDE_PATHS_KEY = "org.atmosphere.ai.harness.exclude-paths";

    /** Property-bag key under which the installed preset instance is stashed (one-shot marker). */
    public static final String PRESET_PROPERTY = "org.atmosphere.ai.harness.preset";

    /** Property-bag key under which the live primitive-to-state map is published. */
    public static final String RUNTIME_STATE_PROPERTY = "org.atmosphere.ai.harness.runtime-state";

    /** Runtime-state primitive keys. */
    public static final String PRIMITIVE_CONVERSATION_MEMORY = "conversation-memory";
    public static final String PRIMITIVE_COMPACTION = "compaction";
    public static final String PRIMITIVE_LONG_TERM_MEMORY = "long-term-memory";
    public static final String PRIMITIVE_PROMPT_CACHE_DEFAULT = "prompt-cache-default";
    public static final String PRIMITIVE_DELEGATION = "delegation";
    public static final String PRIMITIVE_SKILLS = "skills";
    public static final String PRIMITIVE_DURABLE_RUNS = "durable-runs";

    /** {@code null} = unset, {@code TRUE} = app-wide on, {@code FALSE} = kill switch. */
    private final Boolean explicitEnabled;
    private final Set<String> excludePaths;
    private final ConcurrentHashMap<String, String> runtimeState = new ConcurrentHashMap<>();

    private HarnessPreset(Boolean explicitEnabled, Set<String> excludePaths) {
        this.explicitEnabled = explicitEnabled;
        this.excludePaths = excludePaths;
    }

    /**
     * Resolve and install the preset exactly once per framework, mirroring
     * the {@code MemorySafetyConfig.installedFrom} one-shot pattern: the
     * first caller parses the init-params, publishes the runtime-state map,
     * and stashes the instance in the property bag; every later call returns
     * the already-installed instance. A framework without an
     * {@link AtmosphereConfig} (bare test mocks) yields an unset,
     * unpublished preset so callers never need a null check.
     *
     * @param framework the framework being configured
     * @return the installed (or unset fallback) preset — never {@code null}
     */
    public static HarnessPreset install(AtmosphereFramework framework) {
        var cfg = framework != null ? framework.getAtmosphereConfig() : null;
        if (cfg == null) {
            return new HarnessPreset(null, Set.of());
        }
        if (cfg.properties().get(PRESET_PROPERTY) instanceof HarnessPreset installed) {
            return installed;
        }
        var built = fromConfig(cfg);
        if (cfg.properties().putIfAbsent(PRESET_PROPERTY, built) instanceof HarnessPreset raced) {
            return raced;
        }
        built.seedRuntimeState(cfg);
        cfg.properties().putIfAbsent(RUNTIME_STATE_PROPERTY, built.runtimeState);
        if (built.killSwitch()) {
            logger.info("Harness kill switch active ({}=false) — harness primitives disabled everywhere",
                    ENABLED_KEY);
        } else if (built.enabled()) {
            logger.info("Harness enabled app-wide (exclude-paths: {})",
                    built.excludePaths.isEmpty() ? "none" : built.excludePaths);
        }
        return built;
    }

    private static HarnessPreset fromConfig(AtmosphereConfig cfg) {
        // Tri-state: an absent/blank init-param stays unset; any present value
        // parses with Boolean semantics, so only a literal "true" turns the
        // app-wide switch on and everything else reads as the kill switch.
        var rawEnabled = cfg.getInitParameter(ENABLED_KEY);
        var explicit = rawEnabled == null || rawEnabled.isBlank()
                ? null : Boolean.valueOf(rawEnabled.trim());
        if (explicit != null && !"true".equalsIgnoreCase(rawEnabled.trim())
                && !"false".equalsIgnoreCase(rawEnabled.trim())) {
            logger.warn("Unrecognized value '{}' for {} — treating it as the "
                    + "kill switch (fail-closed); use true or false", rawEnabled.trim(), ENABLED_KEY);
        }
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
        return new HarnessPreset(explicit, Set.copyOf(excludes));
    }

    /**
     * Seed the published map with the state each primitive genuinely has at
     * install time. Attach-time truths (the resolved long-term-memory store,
     * a registered delegate_task tool, an annotation-driven attach under the
     * unset default) are upgraded later by the processors via
     * {@link #updateRuntimeState}.
     */
    private void seedRuntimeState(AtmosphereConfig cfg) {
        var disabledState = "INACTIVE(disabled)";
        // Seed INACTIVE even under the app-wide true switch — ACTIVE is an
        // attach-time truth the processors publish per genuinely attached
        // endpoint (Invariant #5); the switch alone attaches nothing.
        runtimeState.put(PRIMITIVE_CONVERSATION_MEMORY,
                enabled() ? "INACTIVE(no-endpoint)" : disabledState);
        runtimeState.put(PRIMITIVE_LONG_TERM_MEMORY,
                enabled() ? "INACTIVE(no-endpoint)" : disabledState);
        runtimeState.put(PRIMITIVE_DELEGATION,
                enabled() ? "INACTIVE(no-coordinator)" : disabledState);
        runtimeState.put(PRIMITIVE_COMPACTION, CompactionConfig.resolve(cfg).name());
        runtimeState.put(PRIMITIVE_PROMPT_CACHE_DEFAULT,
                PromptCacheDefaults.effective(CacheHint.CachePolicy.NONE, cfg, enabled())
                        .name().toLowerCase(Locale.ROOT));
        runtimeState.put(PRIMITIVE_SKILLS, "CONVENTION");
        runtimeState.put(PRIMITIVE_DURABLE_RUNS, "CONTAINER-MANAGED");
    }

    /** Whether the app-wide switch is explicitly {@code true} for this framework. */
    public boolean enabled() {
        return Boolean.TRUE.equals(explicitEnabled);
    }

    /** Whether the app-wide switch is explicitly {@code false} — the kill switch. */
    public boolean killSwitch() {
        return Boolean.FALSE.equals(explicitEnabled);
    }

    /**
     * The single resolution seam shared by the {@code @Agent},
     * {@code @AiEndpoint} and {@code @Coordinator} processors: resolve the
     * harness features that genuinely apply to one endpoint path.
     *
     * <p>Precedence, strongest first:</p>
     * <ol>
     *   <li><b>kill switch</b> — {@code org.atmosphere.ai.harness.enabled=false}
     *       yields the empty set everywhere, beating every annotation
     *       (operational / compliance switch);</li>
     *   <li><b>exclude-paths</b> — an excluded (or {@code null}) path yields
     *       the empty set;</li>
     *   <li><b>the annotation's {@code harness()}</b> — a non-empty value
     *       (including {@code @Agent}'s batteries-included {@code {ALL}}
     *       default) resolves via {@link Harness#expand};</li>
     *   <li><b>app-wide {@code true}</b> — an empty {@code @AiEndpoint}
     *       annotation gets the full harness; an empty {@code @Agent}
     *       annotation is an explicit opt-down (its default is non-empty)
     *       and stays bare;</li>
     *   <li><b>off</b> — the unset default leaves an empty annotation
     *       bare.</li>
     * </ol>
     *
     * @param path                    the endpoint path as declared on the annotation
     * @param annotationValue         the annotation's {@code harness()} value
     * @param annotationIsAgentDefault {@code true} when resolving an
     *                                {@code @Agent} or {@code @Coordinator},
     *                                whose annotation default is
     *                                batteries-included — an empty array is then
     *                                a deliberate per-class opt-down that even
     *                                the app-wide {@code true} flag does not
     *                                override; {@code false} when resolving an
     *                                {@code @AiEndpoint}, whose bare default
     *                                falls through to the app-wide flag
     * @return the features that apply to this path — never {@code null}
     */
    public Set<Harness> featuresFor(String path, Harness[] annotationValue,
                                    boolean annotationIsAgentDefault) {
        if (killSwitch() || path == null || excludePaths.contains(path)) {
            return Set.of();
        }
        var declared = Harness.expand(annotationValue);
        if (!declared.isEmpty()) {
            return declared;
        }
        if (!annotationIsAgentDefault && enabled()) {
            return Harness.expand(new Harness[]{Harness.ALL});
        }
        return Set.of();
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
