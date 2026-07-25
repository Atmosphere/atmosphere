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
package org.atmosphere.ai.tape;

import org.atmosphere.ai.filter.PiiPatterns;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bundled {@link TapeRedactor}: masks email addresses, US phone numbers,
 * SSNs, and credit-card numbers in every string value of a tape step payload
 * — recursively through nested maps and lists, so PII inside tool arguments
 * ({@code {"arguments":{"to":"alice@example.com"}}}) and the {@code input}
 * step's message list is caught, not just top-level text.
 *
 * <p>Patterns come from {@link PiiPatterns} — the same definitions the
 * stream-level {@code PiiRedactionFilter} enforces, so "what counts as PII"
 * has one source. Install via {@code TapeRecorder.Config} (a Spring
 * {@code TapeRedactor} bean or Quarkus CDI bean is picked up by the tape
 * installers).</p>
 */
public final class PiiTapeRedactor implements TapeRedactor {

    /** Replacement written over each match. */
    public static final String REPLACEMENT = "[REDACTED]";

    private static final List<Pattern> PATTERNS = List.of(
            PiiPatterns.EMAIL, PiiPatterns.US_PHONE, PiiPatterns.SSN, PiiPatterns.CREDIT_CARD);

    @Override
    public Map<String, Object> redact(String kind, Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return payload;
        }
        // Copy-on-write: most steps carry no PII, so scan first and only
        // allocate when a value actually changes.
        Map<String, Object> out = null;
        for (var entry : payload.entrySet()) {
            var redacted = redactValue(entry.getValue());
            if (redacted != entry.getValue()) {
                if (out == null) {
                    out = new LinkedHashMap<>(payload);
                }
                out.put(entry.getKey(), redacted);
            }
        }
        return out != null ? out : payload;
    }

    /** Redact one value: strings scanned, maps/lists recursed, others verbatim. */
    private Object redactValue(Object value) {
        if (value instanceof String s) {
            var redacted = redactText(s);
            return redacted.equals(s) ? value : redacted;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = null;
            for (var entry : map.entrySet()) {
                var redacted = redactValue(entry.getValue());
                if (redacted != entry.getValue()) {
                    if (out == null) {
                        out = new LinkedHashMap<>(map);
                    }
                    out.put(entry.getKey(), redacted);
                }
            }
            return out != null ? out : value;
        }
        if (value instanceof List<?> list) {
            List<Object> out = null;
            for (int i = 0; i < list.size(); i++) {
                var redacted = redactValue(list.get(i));
                if (redacted != list.get(i)) {
                    if (out == null) {
                        out = new java.util.ArrayList<>(list);
                    }
                    out.set(i, redacted);
                }
            }
            return out != null ? out : value;
        }
        return value;
    }

    private static String redactText(String text) {
        var result = text;
        for (var pattern : PATTERNS) {
            result = pattern.matcher(result).replaceAll(REPLACEMENT);
        }
        return result;
    }
}
