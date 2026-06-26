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
package org.atmosphere.ai.governance.rag;

import org.atmosphere.ai.ContextProvider;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Framework-scoped policy for RAG injection-safety. Reads the four
 * {@code atmosphere.ai.rag.safety.*} init-parameters (bridged from Spring Boot
 * / Quarkus config or set directly on a bare {@link AtmosphereConfig}) and
 * decorates every user-declared {@link ContextProvider} with a
 * {@link SafetyContextProvider} so retrieved documents are screened for
 * indirect prompt injection (OWASP Agentic Top-10 A04) before they reach the
 * LLM.
 *
 * <h2>Default posture (fail-closed)</h2>
 * The defaults — {@code enabled=true}, {@link InjectionClassifier.Tier#RULE_BASED},
 * {@link SafetyContextProvider.Breach#DROP}, {@code failOpen=false} — protect
 * every RAG endpoint out of the box with zero dependencies and no opt-in.
 * Operators relax the posture explicitly:
 * <pre>
 * atmosphere.ai.rag.safety.enabled   = false        # turn the screen off
 * atmosphere.ai.rag.safety.tier      = EMBEDDING_SIMILARITY | LLM_CLASSIFIER
 * atmosphere.ai.rag.safety.on-breach = DROP | FLAG | SANITIZE
 * atmosphere.ai.rag.safety.fail-open = true         # admit on classifier error
 * </pre>
 *
 * <h2>Runtime truth</h2>
 * {@link #apply} publishes a {@link RagSafetyRuntimeState} into the framework
 * property bag <em>only</em> when at least one provider is actually wrapped, and
 * records the <em>effective</em> classifier tier (which reflects any
 * runtime-absent downgrade performed by {@link InjectionClassifierResolver}).
 * Info/discovery surfaces report from that record, never from the configured
 * intent (Correctness Invariant #5).
 */
public record RagSafetyConfig(
        boolean enabled,
        InjectionClassifier.Tier tier,
        SafetyContextProvider.Breach onBreach,
        boolean failOpen) {

    private static final Logger logger = LoggerFactory.getLogger(RagSafetyConfig.class);

    /** Init-param: master switch. Default {@code true} (protected out of the box). */
    public static final String ENABLED_KEY = "org.atmosphere.ai.rag.safety.enabled";

    /** Init-param: classifier tier name. Default {@code RULE_BASED}. */
    public static final String TIER_KEY = "org.atmosphere.ai.rag.safety.tier";

    /** Init-param: breach policy name. Default {@code DROP}. */
    public static final String ON_BREACH_KEY = "org.atmosphere.ai.rag.safety.on-breach";

    /** Init-param: fail-open on classifier error. Default {@code false} (fail-closed). */
    public static final String FAIL_OPEN_KEY = "org.atmosphere.ai.rag.safety.fail-open";

    /**
     * Property-bag key under which {@link #apply} publishes the resolved
     * {@link RagSafetyRuntimeState} for info/discovery surfaces.
     */
    public static final String RUNTIME_STATE_PROPERTY = "org.atmosphere.ai.rag.safety.runtime-state";

    /** The default fail-closed posture: ON, rule-based, drop-on-injection, fail-closed. */
    public static RagSafetyConfig defaults() {
        return new RagSafetyConfig(true, InjectionClassifier.Tier.RULE_BASED,
                SafetyContextProvider.Breach.DROP, false);
    }

    /**
     * Resolve the policy from a framework's init-parameters, falling back to
     * {@link #defaults()} for any value that is absent or unparseable.
     */
    public static RagSafetyConfig from(AtmosphereConfig cfg) {
        var d = defaults();
        if (cfg == null) {
            return d;
        }
        return new RagSafetyConfig(
                cfg.getInitParameter(ENABLED_KEY, d.enabled()),
                parseTier(cfg.getInitParameter(TIER_KEY, d.tier().name()), d.tier()),
                parseBreach(cfg.getInitParameter(ON_BREACH_KEY, d.onBreach().name()), d.onBreach()),
                cfg.getInitParameter(FAIL_OPEN_KEY, d.failOpen()));
    }

    /**
     * Decorate {@code providers} with a {@link SafetyContextProvider} per the
     * configured policy. Returns the list unchanged when disabled or empty, and
     * never double-wraps a provider that is already a {@link SafetyContextProvider}.
     * Publishes runtime truth into the framework property bag when it wraps.
     *
     * @param providers    the resolved context providers for one endpoint
     * @param framework    the owning framework (for the runtime-state property bag)
     * @param endpointPath the endpoint path, for the activation log line
     * @return the (possibly decorated) provider list
     */
    public List<ContextProvider> apply(List<ContextProvider> providers,
                                       AtmosphereFramework framework,
                                       String endpointPath) {
        if (providers == null || providers.isEmpty()) {
            return providers == null ? List.of() : providers;
        }
        if (!enabled) {
            return providers;
        }
        var wrapped = new ArrayList<ContextProvider>(providers.size());
        var effectiveTier = tier;
        var newlyWrapped = 0;
        for (var p : providers) {
            if (p instanceof SafetyContextProvider) {
                wrapped.add(p);             // already screened — never double-wrap
                continue;
            }
            var safe = SafetyContextProvider.wrapping(p)
                    .tier(tier)
                    .onBreach(onBreach)
                    .failOpen(failOpen)
                    .build();
            effectiveTier = safe.effectiveTier();   // reflects any runtime-absent downgrade
            wrapped.add(safe);
            newlyWrapped++;
        }
        if (newlyWrapped > 0) {
            logger.info("AI endpoint {} — RAG injection-safety active: wrapped {} context provider(s) "
                            + "(tier={}, onBreach={}, failOpen={})",
                    endpointPath, newlyWrapped, effectiveTier, onBreach, failOpen);
            recordRuntimeState(framework, effectiveTier, newlyWrapped);
        }
        return List.copyOf(wrapped);
    }

    private void recordRuntimeState(AtmosphereFramework framework,
                                    InjectionClassifier.Tier effectiveTier, int count) {
        if (framework == null) {
            return;
        }
        var cfg = framework.getAtmosphereConfig();
        if (cfg == null) {
            return;
        }
        // Annotation processing is single-threaded during init(), so this
        // read-modify-write of the shared property bag is not contended.
        var props = cfg.properties();
        var prior = props.get(RUNTIME_STATE_PROPERTY) instanceof RagSafetyRuntimeState s
                ? s.wrappedProviders() : 0;
        props.put(RUNTIME_STATE_PROPERTY, new RagSafetyRuntimeState(
                true, effectiveTier.name(), onBreach.name(), prior + count));
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

    private static SafetyContextProvider.Breach parseBreach(String value,
                                                            SafetyContextProvider.Breach fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return SafetyContextProvider.Breach.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown {} value '{}' — using {}", ON_BREACH_KEY, value, fallback);
            return fallback;
        }
    }

    /**
     * Confirmed-runtime snapshot of the RAG injection-safety screen, published
     * to the framework property bag by {@link #apply} when (and only when) it
     * actually wraps a provider. {@code tier} is the effective classifier tier
     * after any runtime-absent downgrade.
     *
     * @param active           true once at least one provider is screened
     * @param tier             effective {@link InjectionClassifier.Tier} name
     * @param breach           {@link SafetyContextProvider.Breach} policy name
     * @param wrappedProviders count of providers wrapped across all endpoints
     */
    public record RagSafetyRuntimeState(boolean active, String tier, String breach, int wrappedProviders) {
    }
}
