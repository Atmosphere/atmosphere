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
package org.atmosphere.samples.springboot.msgovernance;

import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.cpr.AtmosphereResource;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lightweight ticket classifier that tags every incoming
 * support message with {@code ticket.category} and {@code ticket.priority}
 * metadata. Downstream MS-schema YAML rules + the governance audit log
 * see the tags without any extra plumbing.
 *
 * <p>Classification is rule-based keyword scoring — the sample stays
 * dependency-free and CI-deterministic. Production deployments typically
 * swap in an LLM-backed classifier by implementing the same
 * {@link AiInterceptor} contract.</p>
 *
 * <h2>Internationalization</h2>
 * The default keyword sets ship with English patterns only. Operators
 * deploying in non-English locales construct the interceptor with a
 * {@link Rules} bundle sourced from their own resource file / service.
 * The constructor contract is stable; swapping the {@link Rules}
 * instance is the i18n seam so this sample can demonstrate the pattern
 * without a full ResourceBundle dance.
 *
 * <h2>Categories (English defaults)</h2>
 * {@code order}, {@code billing}, {@code account}, {@code shipping},
 * {@code refund}, {@code product}, {@code general} (fallback).
 *
 * <h2>Priorities (English defaults)</h2>
 * {@code urgent} (angry / refund / broken), {@code high} (specific
 * complaint keywords), {@code normal} (default).
 */
public final class TicketClassifierInterceptor implements AiInterceptor {

    /** Metadata key for the classifier's category verdict. */
    public static final String CATEGORY_KEY = "ticket.category";

    /** Metadata key for the classifier's priority verdict. */
    public static final String PRIORITY_KEY = "ticket.priority";

    private final Rules rules;

    /** Default — English keyword set. */
    public TicketClassifierInterceptor() {
        this(Rules.english());
    }

    public TicketClassifierInterceptor(Rules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules must not be null");
        }
        this.rules = rules;
    }

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        if (request == null || request.message() == null) {
            return request;
        }
        var message = request.message();
        var category = classifyCategory(message);
        var priority = classifyPriority(message);

        var metadata = new LinkedHashMap<String, Object>(
                request.metadata() == null ? Map.of() : request.metadata());
        metadata.put(CATEGORY_KEY, category);
        metadata.put(PRIORITY_KEY, priority);
        return request.withMetadata(Map.copyOf(metadata));
    }

    /** Package-private so tests + samples can reuse the scoring without routing through the interceptor. */
    String classifyCategory(String message) {
        var lowered = message.toLowerCase(Locale.ROOT);
        for (var entry : rules.categoryPatterns().entrySet()) {
            if (entry.getValue().matcher(lowered).find()) {
                return entry.getKey();
            }
        }
        return rules.fallbackCategory();
    }

    String classifyPriority(String message) {
        if (rules.urgent().matcher(message).find()) return "urgent";
        if (rules.high().matcher(message).find()) return "high";
        return "normal";
    }

    /**
     * I18n-ready pattern bundle. Operators with non-English deployments
     * build one of these from a locale-specific resource file (Java
     * {@code ResourceBundle}, Spring MessageSource, or a database-backed
     * keyword table). The category keys themselves are stable labels —
     * only the regexes that decide the match are locale-specific. Keep
     * the insertion order of {@code categoryPatterns()} deterministic —
     * the first match wins.
     */
    public record Rules(
            Map<String, Pattern> categoryPatterns,
            String fallbackCategory,
            Pattern urgent,
            Pattern high) {

        public Rules {
            if (categoryPatterns == null || categoryPatterns.isEmpty()) {
                throw new IllegalArgumentException("categoryPatterns must be non-empty");
            }
            if (fallbackCategory == null || fallbackCategory.isBlank()) {
                throw new IllegalArgumentException("fallbackCategory must not be blank");
            }
            if (urgent == null || high == null) {
                throw new IllegalArgumentException(
                        "urgent and high priority patterns must not be null");
            }
            // Defensive copy into LinkedHashMap so first-match order stays deterministic.
            var copy = new LinkedHashMap<String, Pattern>(categoryPatterns);
            categoryPatterns = java.util.Collections.unmodifiableMap(copy);
        }

        /**
         * Builder-friendly factory that preserves insertion order so
         * operators declaring non-English rules don't have to worry
         * about map-order ambiguity.
         */
        public static Rules of(Map<String, Pattern> categoryPatterns,
                               String fallbackCategory,
                               Pattern urgent,
                               Pattern high) {
            return new Rules(categoryPatterns, fallbackCategory, urgent, high);
        }

        /** English keyword bundle — the sample's default. */
        public static Rules english() {
            var categories = new LinkedHashMap<String, Pattern>();
            add(categories, "refund", "refund|return|money\\s*back");
            add(categories, "shipping", "ship|delivery|tracking|arrived|late");
            add(categories, "billing", "bill|invoice|charge|payment|credit\\s*card");
            add(categories, "account", "account|login|password|signin");
            add(categories, "order", "order|purchase|bought|item");
            add(categories, "product", "product|features?|specs?");
            var urgent = Pattern.compile(
                    "\\b(urgent|asap|immediately|angry|broken|damaged|stolen|"
                            + "fraud\\w*|unauthori[sz]ed)\\b",
                    Pattern.CASE_INSENSITIVE);
            var high = Pattern.compile(
                    "\\b(complaint|unhappy|disappointed|problem|issue|wrong|missing)\\b",
                    Pattern.CASE_INSENSITIVE);
            return new Rules(categories, "general", urgent, high);
        }

        /** Append an alternation pattern under {@code name} with word boundaries. */
        public static void add(Map<String, Pattern> categories, String name, String alternation) {
            categories.put(name, Pattern.compile(
                    "\\b(?:" + alternation + ")\\b", Pattern.CASE_INSENSITIVE));
        }

        /** Merge multiple rule sets — useful when an operator supports multiple locales on one endpoint. */
        public Rules merge(Rules other) {
            var categories = new LinkedHashMap<>(this.categoryPatterns);
            for (var entry : other.categoryPatterns.entrySet()) {
                categories.merge(entry.getKey(), entry.getValue(),
                        (a, b) -> Pattern.compile(
                                a.pattern() + "|" + b.pattern(), Pattern.CASE_INSENSITIVE));
            }
            var urgent = Pattern.compile(
                    this.urgent.pattern() + "|" + other.urgent.pattern(),
                    Pattern.CASE_INSENSITIVE);
            var high = Pattern.compile(
                    this.high.pattern() + "|" + other.high.pattern(),
                    Pattern.CASE_INSENSITIVE);
            return new Rules(categories, this.fallbackCategory, urgent, high);
        }

    }
}
