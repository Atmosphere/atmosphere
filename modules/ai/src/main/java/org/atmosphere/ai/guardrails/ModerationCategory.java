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
package org.atmosphere.ai.guardrails;

import java.util.Locale;
import java.util.Optional;

/**
 * Canonical content-moderation taxonomy used by {@link ModerationGuardrail}
 * and its {@link ModerationDetector} implementations.
 *
 * <p>The set is deliberately small and stable — it mirrors the high-level
 * categories common to provider moderation endpoints (OpenAI omni-moderation,
 * Mastra {@code ModerationProcessor}, LangChain4j moderation) without pinning
 * to any one vendor's sub-category sprawl. A moderation taxonomy that changes
 * shape every quarter is a poor wire contract; these six are the durable
 * intersection.</p>
 */
public enum ModerationCategory {

    /** Hateful content targeting a protected class. */
    HATE("hate"),

    /** Harassing, bullying, or threatening language aimed at an individual. */
    HARASSMENT("harassment"),

    /** Content that encourages or describes self-harm or suicide. */
    SELF_HARM("self-harm"),

    /** Sexually explicit content. */
    SEXUAL("sexual"),

    /** Content that depicts, threatens, or instructs real-world violence. */
    VIOLENCE("violence"),

    /** Instructions or solicitation for illegal / illicit activity. */
    ILLICIT("illicit");

    private final String label;

    ModerationCategory(String label) {
        this.label = label;
    }

    /** Stable lowercase wire label (e.g. {@code "self-harm"}). */
    public String label() {
        return label;
    }

    /**
     * Resolve a category from a free-form token, tolerating the punctuation and
     * casing variations a language model emits ({@code "Self Harm"},
     * {@code "self_harm"}, {@code "SELF-HARM"} all map to {@link #SELF_HARM}).
     *
     * @param token candidate token; may be {@code null}
     * @return the matched category, or empty when the token names no known category
     */
    public static Optional<ModerationCategory> fromToken(String token) {
        if (token == null) {
            return Optional.empty();
        }
        var normalized = token.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-').replace(' ', '-');
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        for (var category : values()) {
            if (category.label.equals(normalized) || category.name()
                    .toLowerCase(Locale.ROOT).replace('_', '-').equals(normalized)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
