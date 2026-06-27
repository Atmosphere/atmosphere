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
package org.atmosphere.ai.governance.memory;

import org.atmosphere.ai.governance.rag.InjectionClassifier;
import org.atmosphere.ai.governance.rag.InjectionClassifierResolver;
import org.atmosphere.ai.governance.rag.SafetyContextProvider.Breach;
import org.atmosphere.ai.memory.LongTermMemory;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Framework-scoped policy for long-term-memory injection safety. The symmetric
 * write-path counterpart to
 * {@link org.atmosphere.ai.governance.rag.RagSafetyConfig}: it wraps a
 * {@link LongTermMemory} with a {@link ScreenedLongTermMemory} so every fact is
 * screened for indirect prompt injection (OWASP Agentic A03 — Memory Poisoning)
 * before it is persisted and later re-injected into the system prompt.
 *
 * <h2>Default posture (fail-closed)</h2>
 * The defaults — {@code enabled=true}, {@link InjectionClassifier.Tier#RULE_BASED},
 * {@link Breach#DROP}, {@code failOpen=false} — protect every memory-backed
 * endpoint out of the box with zero dependencies and no opt-in. Operators relax
 * the posture explicitly:
 * <pre>
 * atmosphere.ai.memory.safety.enabled   = false        # turn the screen off
 * atmosphere.ai.memory.safety.tier      = EMBEDDING_SIMILARITY | LLM_CLASSIFIER
 * atmosphere.ai.memory.safety.on-breach = DROP | FLAG | SANITIZE
 * atmosphere.ai.memory.safety.fail-open = true          # admit on classifier error
 * </pre>
 *
 * <h2>Default holder</h2>
 * {@link LongTermMemoryInterceptor} constructs its store before any
 * {@link AtmosphereConfig} handle is available, so the Spring / Quarkus bridge
 * resolves the policy at startup and publishes it via {@link #installDefault}.
 * The interceptor (and any other framework write path) reads
 * {@link #installedDefault()} — which begins life as the fail-closed
 * {@link #defaults()} so protection is on even before the bridge runs.
 */
public record MemorySafetyConfig(
        boolean enabled,
        InjectionClassifier.Tier tier,
        Breach onBreach,
        boolean failOpen) {

    private static final Logger logger = LoggerFactory.getLogger(MemorySafetyConfig.class);

    /** Init-param: master switch. Default {@code true} (protected out of the box). */
    public static final String ENABLED_KEY = "org.atmosphere.ai.memory.safety.enabled";

    /** Init-param: classifier tier name. Default {@code RULE_BASED}. */
    public static final String TIER_KEY = "org.atmosphere.ai.memory.safety.tier";

    /** Init-param: breach policy name. Default {@code DROP}. */
    public static final String ON_BREACH_KEY = "org.atmosphere.ai.memory.safety.on-breach";

    /** Init-param: fail-open on classifier error. Default {@code false} (fail-closed). */
    public static final String FAIL_OPEN_KEY = "org.atmosphere.ai.memory.safety.fail-open";

    /** Property-bag key under which the resolved {@link MemorySafetyRuntimeState} is published. */
    public static final String RUNTIME_STATE_PROPERTY = "org.atmosphere.ai.memory.safety.runtime-state";

    private static volatile MemorySafetyConfig installedDefault = defaults();

    /** The default fail-closed posture: ON, rule-based, drop-on-injection, fail-closed. */
    public static MemorySafetyConfig defaults() {
        return new MemorySafetyConfig(true, InjectionClassifier.Tier.RULE_BASED, Breach.DROP, false);
    }

    /**
     * The framework-wide default policy used by write paths that have no
     * {@link AtmosphereConfig} handle (e.g. {@link LongTermMemoryInterceptor}).
     * Starts as {@link #defaults()} (fail-closed on) so memory is screened even
     * before the Spring / Quarkus bridge runs {@link #installDefault}.
     */
    public static MemorySafetyConfig installedDefault() {
        return installedDefault;
    }

    /** Install the resolved framework-wide default (null restores {@link #defaults()}). */
    public static void installDefault(MemorySafetyConfig cfg) {
        installedDefault = cfg != null ? cfg : defaults();
    }

    /** Reset the default holder to {@link #defaults()} (test hygiene). */
    public static void resetDefault() {
        installedDefault = defaults();
    }

    /**
     * Resolve the policy from a framework's init-parameters, falling back to
     * {@link #defaults()} for any value that is absent or unparseable.
     */
    public static MemorySafetyConfig from(AtmosphereConfig cfg) {
        var d = defaults();
        if (cfg == null) {
            return d;
        }
        return new MemorySafetyConfig(
                cfg.getInitParameter(ENABLED_KEY, d.enabled()),
                parseTier(cfg.getInitParameter(TIER_KEY, d.tier().name()), d.tier()),
                parseBreach(cfg.getInitParameter(ON_BREACH_KEY, d.onBreach().name()), d.onBreach()),
                cfg.getInitParameter(FAIL_OPEN_KEY, d.failOpen()));
    }

    /**
     * Wrap {@code memory} with a {@link ScreenedLongTermMemory} per this policy.
     * Returns the argument unchanged when disabled, null, or already screened
     * (never double-wraps).
     */
    public LongTermMemory wrap(LongTermMemory memory) {
        if (!enabled || memory == null || memory instanceof ScreenedLongTermMemory) {
            return memory;
        }
        return new ScreenedLongTermMemory(
                memory, InjectionClassifierResolver.resolve(tier), onBreach, failOpen);
    }

    /**
     * Resolve the policy from the framework's init-parameters and install it as
     * the framework-wide default holder, so every {@link LongTermMemoryInterceptor}
     * constructed afterwards screens with the operator's posture. Does NOT publish
     * runtime-state — that happens only once a real {@link LongTermMemory} store is
     * actually wrapped (see {@link #publishActive}), so the console never
     * advertises a screen that nothing exercises (Correctness Invariant #5 —
     * confirmed runtime state, not configured intent).
     */
    public static MemorySafetyConfig installedFrom(AtmosphereFramework framework) {
        var cfg = from(framework != null ? framework.getAtmosphereConfig() : null);
        installDefault(cfg);
        return cfg;
    }

    /**
     * Publish the active runtime-state once (idempotent). Call only when a
     * {@link LongTermMemory} store has genuinely been wrapped with the screen, so
     * the console reports confirmed runtime state — symmetric to {@code RagSafetyConfig},
     * which publishes only once a {@code ContextProvider} is actually wrapped. The
     * effective tier reflects any runtime-absent downgrade performed by
     * {@link InjectionClassifierResolver}. No-op when disabled.
     */
    public void publishActive(AtmosphereFramework framework) {
        if (!enabled || framework == null) {
            return;
        }
        var cfg = framework.getAtmosphereConfig();
        if (cfg == null) {
            return;
        }
        var effectiveTier = InjectionClassifierResolver.resolve(tier).tier();
        var state = new MemorySafetyRuntimeState(true, effectiveTier.name(), onBreach.name());
        if (cfg.properties().putIfAbsent(RUNTIME_STATE_PROPERTY, state) == null) {
            logger.info("Long-term-memory injection-safety active: tier={}, onBreach={}, failOpen={}",
                    effectiveTier, onBreach, failOpen);
        }
    }

    private static InjectionClassifier.Tier parseTier(String value, InjectionClassifier.Tier fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return InjectionClassifier.Tier.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown {} value '{}' — using {}", TIER_KEY, value, fallback);
            return fallback;
        }
    }

    private static Breach parseBreach(String value, Breach fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Breach.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown {} value '{}' — using {}", ON_BREACH_KEY, value, fallback);
            return fallback;
        }
    }

    /**
     * Confirmed-runtime snapshot of the long-term-memory injection-safety screen,
     * published to the framework property bag when the screen is active.
     *
     * @param active true once the default-on screen is installed
     * @param tier   effective {@link InjectionClassifier.Tier} name after any downgrade
     * @param breach {@link Breach} policy name
     */
    public record MemorySafetyRuntimeState(boolean active, String tier, String breach) {
    }
}
