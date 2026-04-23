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
 * Tier 6.3 — lightweight ticket classifier that tags every incoming
 * support message with {@code ticket.category} and {@code ticket.priority}
 * metadata. Downstream MS-schema YAML rules + the governance audit log
 * see the tags without any extra plumbing.
 *
 * <p>Classification is rule-based keyword scoring — the sample stays
 * dependency-free and CI-deterministic. Production deployments typically
 * swap in an LLM-backed classifier by implementing the same
 * {@link AiInterceptor} contract.</p>
 *
 * <h2>Categories</h2>
 * {@code order}, {@code billing}, {@code account}, {@code shipping},
 * {@code refund}, {@code product}, {@code general} (fallback).
 *
 * <h2>Priorities</h2>
 * {@code urgent} (angry / refund / broken), {@code high} (specific
 * complaint keywords), {@code normal} (default).
 */
public final class TicketClassifierInterceptor implements AiInterceptor {

    /** Metadata key for the classifier's category verdict. */
    public static final String CATEGORY_KEY = "ticket.category";

    /** Metadata key for the classifier's priority verdict. */
    public static final String PRIORITY_KEY = "ticket.priority";

    private static final Map<String, Pattern> CATEGORY_PATTERNS = buildCategoryPatterns();

    private static Map<String, Pattern> buildCategoryPatterns() {
        var map = new LinkedHashMap<String, Pattern>();
        map.put("refund", Pattern.compile("\\brefund|return|money\\s*back\\b", Pattern.CASE_INSENSITIVE));
        map.put("shipping", Pattern.compile("\\bship|delivery|tracking|arrived|late\\b", Pattern.CASE_INSENSITIVE));
        map.put("billing", Pattern.compile("\\bbill|invoice|charge|payment|credit\\s*card\\b", Pattern.CASE_INSENSITIVE));
        map.put("account", Pattern.compile("\\baccount|login|password|signin\\b", Pattern.CASE_INSENSITIVE));
        map.put("order", Pattern.compile("\\border|purchase|bought|item\\b", Pattern.CASE_INSENSITIVE));
        map.put("product", Pattern.compile("\\bproduct|features?|specs?\\b", Pattern.CASE_INSENSITIVE));
        return java.util.Collections.unmodifiableMap(map);
    }

    private static final Pattern URGENT = Pattern.compile(
            "\\b(urgent|asap|immediately|angry|broken|damaged|stolen|fraud\\w*|unauthori[sz]ed)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HIGH = Pattern.compile(
            "\\b(complaint|unhappy|disappointed|problem|issue|wrong|missing)\\b",
            Pattern.CASE_INSENSITIVE);

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
    static String classifyCategory(String message) {
        var lowered = message.toLowerCase(Locale.ROOT);
        for (var entry : CATEGORY_PATTERNS.entrySet()) {
            if (entry.getValue().matcher(lowered).find()) {
                return entry.getKey();
            }
        }
        return "general";
    }

    static String classifyPriority(String message) {
        if (URGENT.matcher(message).find()) return "urgent";
        if (HIGH.matcher(message).find()) return "high";
        return "normal";
    }

}
