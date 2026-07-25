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
package org.atmosphere.ai.prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * Deterministic percentage rollout between prompt versions.
 *
 * <p>Weights are configured per prompt name via the system property
 * {@code atmosphere.ai.prompt.rollout.<name>}, e.g.
 * {@code atmosphere.ai.prompt.rollout.support-agent=v1:90,v2:10}. Weights are
 * relative (they need not sum to 100). Selection hashes the rollout unit id
 * (a user id, or the endpoint identity at the annotation seam) with SHA-256 —
 * never {@code Math.random()} — so the same unit always lands on the same
 * version, across restarts and JVMs.</p>
 *
 * <p>A malformed rollout spec fails <em>closed</em> with an
 * {@link IllegalStateException} naming the offending property instead of
 * silently falling back to some version (Correctness Invariant #6).</p>
 */
public final class PromptRollout {

    /** System-property prefix for per-prompt rollout weights. */
    public static final String ROLLOUT_PROPERTY_PREFIX = "atmosphere.ai.prompt.rollout.";

    private PromptRollout() {
    }

    /**
     * Returns the configured rollout weights for a prompt name, if any.
     *
     * @param name the prompt name
     * @return version-to-weight in declaration order, or empty when no rollout
     *         is configured
     * @throws IllegalStateException if the configured spec is malformed
     */
    public static Optional<SequencedMap<String, Integer>> configuredWeights(String name) {
        var spec = System.getProperty(ROLLOUT_PROPERTY_PREFIX + name);
        if (spec == null || spec.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(parseWeights(name, spec));
    }

    /**
     * Selects a version for the given unit id against the weights,
     * deterministically: identical inputs always yield the same version.
     *
     * @param name    the prompt name (part of the hash so different prompts
     *                split independently for the same unit)
     * @param weights version-to-weight in declaration order; must be non-empty
     * @param unitId  the rollout unit (user id, or endpoint identity)
     * @return the selected version
     */
    public static String select(String name, SequencedMap<String, Integer> weights, String unitId) {
        if (weights.isEmpty()) {
            throw new IllegalArgumentException("Rollout weights for prompt '" + name + "' are empty");
        }
        long total = 0;
        for (var weight : weights.values()) {
            total += weight;
        }
        var bucket = bucket(name + ":" + unitId, total);
        long cumulative = 0;
        for (var entry : weights.entrySet()) {
            cumulative += entry.getValue();
            if (bucket < cumulative) {
                return entry.getKey();
            }
        }
        // Unreachable: bucket < total == cumulative after the last entry.
        return weights.lastEntry().getKey();
    }

    /**
     * Parses a {@code v1:90,v2:10} weight spec.
     *
     * @param name the prompt name (for error messages)
     * @param spec the raw property value
     * @return version-to-weight in declaration order
     * @throws IllegalStateException on any malformed entry (fail closed)
     */
    static SequencedMap<String, Integer> parseWeights(String name, String spec) {
        var weights = new LinkedHashMap<String, Integer>();
        for (var part : spec.split(",")) {
            var entry = part.trim();
            var colon = entry.indexOf(':');
            if (colon <= 0 || colon == entry.length() - 1) {
                throw malformed(name, spec, "entry '" + entry + "' is not <version>:<weight>");
            }
            var version = entry.substring(0, colon).trim();
            if (!version.matches("v\\d{1,9}")) {
                throw malformed(name, spec, "version '" + version + "' is not v<digits>");
            }
            int weight;
            try {
                weight = Integer.parseInt(entry.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                throw malformed(name, spec, "weight in '" + entry + "' is not an integer");
            }
            if (weight <= 0) {
                throw malformed(name, spec, "weight in '" + entry + "' must be positive");
            }
            if (weights.putIfAbsent(version, weight) != null) {
                throw malformed(name, spec, "duplicate version '" + version + "'");
            }
        }
        if (weights.isEmpty()) {
            throw malformed(name, spec, "no entries");
        }
        return weights;
    }

    private static IllegalStateException malformed(String name, String spec, String reason) {
        return new IllegalStateException(
                "Malformed rollout spec for prompt '" + name + "' in "
                        + ROLLOUT_PROPERTY_PREFIX + name + "='" + spec + "': " + reason
                        + ". Expected e.g. 'v1:90,v2:10'. Refusing to guess a version.");
    }

    /**
     * Maps a key to a stable bucket in {@code [0, total)} using the first eight
     * bytes of its SHA-256 digest. SHA-256 is stable across JVMs and platforms,
     * so a rollout decision survives restarts and horizontal scale-out.
     */
    private static long bucket(String key, long total) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (var i = 0; i < 8; i++) {
                value = (value << 8) | (digest[i] & 0xFF);
            }
            return Math.floorMod(value, total);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA spec on every conforming JRE.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Convenience accessor combining {@link #configuredWeights(String)} and
     * {@link #select(String, SequencedMap, String)}.
     *
     * @param name   the prompt name
     * @param unitId the rollout unit
     * @return the selected version, or empty when no rollout is configured
     * @throws IllegalStateException if the configured spec is malformed
     */
    public static Optional<String> selectConfigured(String name, String unitId) {
        return configuredWeights(name).map(weights -> select(name, weights, unitId));
    }
}
