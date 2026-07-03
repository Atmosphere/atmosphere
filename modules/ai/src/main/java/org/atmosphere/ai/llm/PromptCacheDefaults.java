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

import org.atmosphere.cpr.AtmosphereConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Framework-wide default for the per-endpoint prompt-cache hint. Before this
 * seam existed, {@code @AiEndpoint.promptCache} was the only way to seed a
 * {@link CacheHint} — there was no global opt-in.
 *
 * <p>Effective-policy precedence (most specific wins):</p>
 * <ol>
 *   <li>the annotation's {@code promptCache()} when not
 *       {@link CacheHint.CachePolicy#NONE};</li>
 *   <li>the {@link #DEFAULT_KEY} init-param when set — including an explicit
 *       {@code none}, which suppresses the preset default below;</li>
 *   <li>{@link CacheHint.CachePolicy#CONSERVATIVE} when the harness CACHE
 *       feature applies to the endpoint;</li>
 *   <li>{@link CacheHint.CachePolicy#NONE}.</li>
 * </ol>
 *
 * <p>This seeds the hint only. Wire emission stays gated by the tri-state
 * {@code atmosphere.ai.prompt-cache-key} mode and its default-deny host
 * allow-list ({@link CacheHint#endpointAcceptsPromptCacheKey}) — unchanged.</p>
 */
public final class PromptCacheDefaults {

    private static final Logger logger = LoggerFactory.getLogger(PromptCacheDefaults.class);

    /** Init-param: {@code none} | {@code conservative} | {@code aggressive}. Independent of the preset. */
    public static final String DEFAULT_KEY = "org.atmosphere.ai.prompt-cache.default";

    private PromptCacheDefaults() {
    }

    /**
     * Resolve the effective cache policy for one endpoint.
     *
     * @param annotationPolicy the endpoint's {@code promptCache()} value
     * @param cfg              the framework config (may be {@code null})
     * @param presetEnabled    whether the harness CACHE feature applies to the endpoint
     * @return the effective policy — never {@code null}
     */
    public static CacheHint.CachePolicy effective(CacheHint.CachePolicy annotationPolicy,
                                                  AtmosphereConfig cfg,
                                                  boolean presetEnabled) {
        if (annotationPolicy != null && annotationPolicy != CacheHint.CachePolicy.NONE) {
            return annotationPolicy;
        }
        var configured = parse(cfg != null ? cfg.getInitParameter(DEFAULT_KEY) : null);
        if (configured != null) {
            return configured;
        }
        return presetEnabled ? CacheHint.CachePolicy.CONSERVATIVE : CacheHint.CachePolicy.NONE;
    }

    /** Lenient parse: unset or unknown returns {@code null} (unknown logged at WARN). */
    private static CacheHint.CachePolicy parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "none" -> CacheHint.CachePolicy.NONE;
            case "conservative" -> CacheHint.CachePolicy.CONSERVATIVE;
            case "aggressive" -> CacheHint.CachePolicy.AGGRESSIVE;
            default -> {
                logger.warn("Unknown {} value '{}' — ignoring", DEFAULT_KEY, value);
                yield null;
            }
        };
    }
}
