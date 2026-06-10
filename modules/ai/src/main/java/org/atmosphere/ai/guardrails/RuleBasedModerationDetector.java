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

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Zero-dependency, deterministic moderation detector that matches a configurable
 * set of high-signal phrases against the inspected text.
 *
 * <p>The built-in seed lists are deliberately <strong>conservative</strong>:
 * they target unambiguous intent phrasings ("how to build a bomb", "ways to kill
 * myself") rather than ambiguous keywords or slur lists. This keeps false
 * positives low and avoids hardcoding offensive vocabulary into the framework.
 * Treat this tier as a cheap pre-filter that runs on every streamed chunk — for
 * nuanced, context-aware moderation install {@link LlmModerationDetector} or a
 * provider moderation endpoint, both of which satisfy the same
 * {@link ModerationDetector} contract.</p>
 *
 * <p>Applications tune the lists per category via
 * {@link #withPhrases(ModerationCategory, List)} or by supplying a complete map
 * to the constructor.</p>
 *
 * <p>Thread-safe: the phrase map is immutable after construction.</p>
 */
public final class RuleBasedModerationDetector implements ModerationDetector {

    private static final Map<ModerationCategory, List<String>> DEFAULT_PHRASES =
            defaultPhrases();

    private final Map<ModerationCategory, List<String>> phrases;

    /** Build with the conservative built-in seed lists. */
    public RuleBasedModerationDetector() {
        this(DEFAULT_PHRASES);
    }

    /**
     * Build with an explicit phrase map. Categories absent from the map are
     * never flagged by this detector.
     *
     * @param phrases per-category trigger phrases (matched case-insensitively
     *                as substrings)
     */
    public RuleBasedModerationDetector(Map<ModerationCategory, List<String>> phrases) {
        var copy = new EnumMap<ModerationCategory, List<String>>(ModerationCategory.class);
        if (phrases != null) {
            phrases.forEach((category, list) -> {
                if (category != null && list != null && !list.isEmpty()) {
                    copy.put(category, list.stream()
                            .filter(p -> p != null && !p.isBlank())
                            .map(p -> p.toLowerCase(Locale.ROOT))
                            .toList());
                }
            });
        }
        this.phrases = Map.copyOf(copy);
    }

    /**
     * Return a new detector with {@code additional} phrases appended to
     * {@code category}'s existing list. Other categories are untouched.
     */
    public RuleBasedModerationDetector withPhrases(ModerationCategory category,
                                                   List<String> additional) {
        var merged = new EnumMap<ModerationCategory, List<String>>(ModerationCategory.class);
        merged.putAll(phrases);
        var combined = new java.util.ArrayList<>(phrases.getOrDefault(category, List.of()));
        combined.addAll(additional);
        merged.put(category, List.copyOf(combined));
        return new RuleBasedModerationDetector(merged);
    }

    @Override
    public ModerationResult detect(String text) {
        if (text == null || text.isBlank()) {
            return ModerationResult.clean();
        }
        var haystack = text.toLowerCase(Locale.ROOT);
        var matched = new java.util.LinkedHashSet<ModerationCategory>();
        var detail = new LinkedHashMap<ModerationCategory, String>();
        phrases.forEach((category, list) -> {
            for (var phrase : list) {
                if (haystack.contains(phrase)) {
                    matched.add(category);
                    detail.putIfAbsent(category, phrase);
                    break;
                }
            }
        });
        if (matched.isEmpty()) {
            return ModerationResult.clean();
        }
        // Rule-based matches are boolean, not graded — report a flat 1.0 so
        // downstream score-threshold consumers see a deterministic signal.
        var scores = new EnumMap<ModerationCategory, Double>(ModerationCategory.class);
        matched.forEach(c -> scores.put(c, 1.0));
        return ModerationResult.flagged(matched, scores,
                "rule-based match: " + detail);
    }

    private static Map<ModerationCategory, List<String>> defaultPhrases() {
        var map = new EnumMap<ModerationCategory, List<String>>(ModerationCategory.class);
        map.put(ModerationCategory.SELF_HARM, List.of(
                "how to kill myself", "ways to kill myself", "how to commit suicide",
                "ways to commit suicide", "how to hurt myself", "how to end my life",
                "best way to overdose"));
        map.put(ModerationCategory.VIOLENCE, List.of(
                "how to make a bomb", "how to build a bomb", "how to make a pipe bomb",
                "how to build a weapon", "how to kill someone", "how to murder someone",
                "how to make a gun", "how to poison someone"));
        map.put(ModerationCategory.ILLICIT, List.of(
                "how to make meth", "how to cook meth", "how to make methamphetamine",
                "how to launder money", "how to hack into", "how to steal a car",
                "how to make counterfeit", "how to pick a lock to break in"));
        return Map.copyOf(map);
    }
}
