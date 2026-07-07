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

import org.atmosphere.cpr.AtmosphereConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Framework-scoped policy for the <em>durable</em> governance feedback path — the opt-in
 * counterpart to the always-on ephemeral loop. When enabled, a {@link GovernanceMemorySink}
 * persists deny/prefer guidance to long-term memory and a {@link GovernanceProvenanceMemory}
 * is published via {@link #installStore} so {@code GovernanceFeedbackInterceptor} recalls it
 * across sessions and restarts.
 *
 * <h2>Default posture (off)</h2>
 * The default is {@code enabled=false}: the loop stays ephemeral (ring-buffer-only, zero
 * persistence), which side-steps the memory-poisoning hazard entirely. Operators opt in:
 * <pre>
 * atmosphere.ai.governance.memory.enabled        = true
 * atmosphere.ai.governance.memory.ttl-seconds    = 0      # 0 = no expiry; &gt;0 = lessons lapse
 * atmosphere.ai.governance.memory.confidence     = 1.0    # stamped on each persisted lesson
 * atmosphere.ai.governance.memory.min-confidence = 0.0    # read gate: drop lessons below this
 * </pre>
 *
 * <h2>Installed store holder</h2>
 * {@code GovernanceFeedbackInterceptor} instances declared via {@code @AiEndpoint(interceptors=…)}
 * use the no-arg (ephemeral) constructor. Rather than thread a store through the annotation,
 * the wiring publishes the resolved provenance store here via {@link #installStore}; the
 * interceptor reads {@link #installedStore()} when it has no explicit store — mirroring
 * {@link MemorySafetyConfig#installedDefault()}. Default is {@code null} (ephemeral).
 *
 * @param enabled       master switch for durable recall (default {@code false})
 * @param ttl           how long a persisted lesson stays valid, or {@code null} for no expiry
 * @param confidence    0.0–1.0 confidence stamped on each persisted lesson
 * @param minConfidence 0.0–1.0 read-gate floor below which lessons are dropped
 */
public record GovernanceMemoryConfig(boolean enabled, Duration ttl,
                                     double confidence, double minConfidence) {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceMemoryConfig.class);

    /** Init-param: master switch. Default {@code false} (ephemeral-only). */
    public static final String ENABLED_KEY = "org.atmosphere.ai.governance.memory.enabled";

    /** Init-param: lesson TTL in seconds. Default {@code 0} (no expiry). */
    public static final String TTL_SECONDS_KEY = "org.atmosphere.ai.governance.memory.ttl-seconds";

    /** Init-param: confidence stamped on each lesson. Default {@code 1.0}. */
    public static final String CONFIDENCE_KEY = "org.atmosphere.ai.governance.memory.confidence";

    /** Init-param: read-gate confidence floor. Default {@code 0.0}. */
    public static final String MIN_CONFIDENCE_KEY =
            "org.atmosphere.ai.governance.memory.min-confidence";

    private static volatile GovernanceProvenanceMemory installedStore;

    public GovernanceMemoryConfig {
        if (ttl != null && (ttl.isZero() || ttl.isNegative())) {
            throw new IllegalArgumentException("ttl must be positive or null, got: " + ttl);
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0.0, 1.0], got: " + confidence);
        }
        if (minConfidence < 0.0 || minConfidence > 1.0) {
            throw new IllegalArgumentException(
                    "minConfidence must be in [0.0, 1.0], got: " + minConfidence);
        }
    }

    /** The default posture: durable recall off. */
    public static GovernanceMemoryConfig defaults() {
        return new GovernanceMemoryConfig(false, null, 1.0, 0.0);
    }

    /** Resolve from framework init-parameters, falling back to {@link #defaults()} per value. */
    public static GovernanceMemoryConfig from(AtmosphereConfig cfg) {
        var d = defaults();
        if (cfg == null) {
            return d;
        }
        var ttlSeconds = (long) cfg.getInitParameter(TTL_SECONDS_KEY, 0);
        return new GovernanceMemoryConfig(
                cfg.getInitParameter(ENABLED_KEY, d.enabled()),
                ttlSeconds > 0 ? Duration.ofSeconds(ttlSeconds) : null,
                parseUnit(cfg.getInitParameter(CONFIDENCE_KEY, String.valueOf(d.confidence())),
                        d.confidence()),
                parseUnit(cfg.getInitParameter(MIN_CONFIDENCE_KEY, String.valueOf(d.minConfidence())),
                        d.minConfidence()));
    }

    /** The durable store the interceptor recalls from, or {@code null} when ephemeral-only. */
    public static GovernanceProvenanceMemory installedStore() {
        return installedStore;
    }

    /** Publish the resolved provenance store (null clears it, restoring ephemeral-only). */
    public static void installStore(GovernanceProvenanceMemory store) {
        installedStore = store;
    }

    /** Clear the installed store (test hygiene). */
    public static void resetStore() {
        installedStore = null;
    }

    private static double parseUnit(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            var parsed = Double.parseDouble(value.trim());
            if (parsed < 0.0 || parsed > 1.0) {
                logger.warn("Governance memory confidence '{}' out of [0,1] — using {}", value, fallback);
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException e) {
            logger.warn("Unparseable governance memory confidence '{}' — using {}", value, fallback);
            return fallback;
        }
    }
}
