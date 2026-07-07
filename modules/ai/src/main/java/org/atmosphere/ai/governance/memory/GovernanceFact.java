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

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Provenance envelope for a governance-derived fact persisted to long-term memory. A
 * governance "lesson" (a prior deny/prefer rendered by
 * {@link org.atmosphere.ai.governance.GovernanceGuidance}) is stored with a machine-readable
 * marker so it can be distinguished from ordinary user facts and gated on read: the
 * originating policy identity, a confidence score, and an optional expiry.
 *
 * <p>This is the mechanism behind the article's "supervision of learned lessons" open
 * problem — a wrong governance lesson written to memory would otherwise compound silently
 * across sessions. Provenance lets {@link GovernanceProvenanceMemory} drop expired or
 * low-confidence lessons before they are re-injected, and lets a human reviewer see exactly
 * which policy authored a fact and when it lapses.</p>
 *
 * <h2>Wire format</h2>
 * <pre>{@code [atmo-gov v=1 policy=<name> conf=<0.00-1.00> exp=<epochSeconds|->] <text>}</pre>
 * The marker is a prefix so the trailing {@code text} is the human-readable guidance line.
 * {@link #parse(String)} is tolerant: any string not matching the exact envelope is treated
 * as a non-governance fact ({@link Optional#empty()}), so ordinary user facts pass through
 * the gate untouched.
 *
 * @param policy      originating policy name (identity for audit / review)
 * @param confidence  0.0–1.0 confidence the lesson is correct
 * @param expiresAt   instant the lesson lapses, or {@code null} for no expiry
 * @param text        the human-readable guidance line
 */
public record GovernanceFact(String policy, double confidence, Instant expiresAt, String text) {

    /** Envelope prefix marking a fact as governance-derived. */
    public static final String MARKER = "atmo-gov";

    private static final String OPEN = "[" + MARKER + " ";

    public GovernanceFact {
        if (policy == null || policy.isBlank()) {
            throw new IllegalArgumentException("policy must not be blank");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0.0, 1.0], got: " + confidence);
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }

    /** True once {@code now} is at or past {@link #expiresAt} (never expires when null). */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** Encode this fact into its wire-format string. */
    public String encode() {
        var exp = expiresAt == null ? "-" : Long.toString(expiresAt.getEpochSecond());
        return OPEN
                + "v=1 policy=" + sanitize(policy)
                + " conf=" + String.format(Locale.ROOT, "%.2f", confidence)
                + " exp=" + exp
                + "] " + text;
    }

    /** Convenience encoder — see {@link #GovernanceFact}. */
    public static String encode(String policy, double confidence, Instant expiresAt, String text) {
        return new GovernanceFact(policy, confidence, expiresAt, text).encode();
    }

    /** True when {@code fact} carries the governance provenance marker. */
    public static boolean isGovernanceFact(String fact) {
        return fact != null && fact.startsWith(OPEN);
    }

    /**
     * Parse a stored fact. Returns empty for any string that is not a well-formed
     * governance envelope — ordinary user facts, or a marker that fails to parse (treated
     * conservatively as a plain fact rather than dropped).
     */
    public static Optional<GovernanceFact> parse(String fact) {
        if (!isGovernanceFact(fact)) {
            return Optional.empty();
        }
        var close = fact.indexOf("] ");
        if (close < 0) {
            return Optional.empty();
        }
        var header = fact.substring(1, close);          // atmo-gov v=1 policy=… conf=… exp=…
        var text = fact.substring(close + 2);
        if (text.isBlank()) {
            return Optional.empty();
        }
        String policy = null;
        double confidence = 1.0;
        Instant expiresAt = null;
        for (var token : header.split(" ")) {
            var eq = token.indexOf('=');
            if (eq < 0) {
                continue;
            }
            var key = token.substring(0, eq);
            var value = token.substring(eq + 1);
            try {
                switch (key) {
                    case "policy" -> policy = value;
                    case "conf" -> confidence = Double.parseDouble(value);
                    case "exp" -> expiresAt = "-".equals(value)
                            ? null : Instant.ofEpochSecond(Long.parseLong(value));
                    default -> { /* forward-compatible: ignore unknown keys */ }
                }
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        if (policy == null || policy.isBlank() || confidence < 0.0 || confidence > 1.0) {
            return Optional.empty();
        }
        return Optional.of(new GovernanceFact(policy, confidence, expiresAt, text));
    }

    /** Strip whitespace and the reserved {@code ]} so a value cannot break the envelope. */
    private static String sanitize(String value) {
        return value.replace(']', ' ').replaceAll("\\s+", "_");
    }
}
