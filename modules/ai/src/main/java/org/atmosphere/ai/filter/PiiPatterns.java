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
package org.atmosphere.ai.filter;

import java.util.regex.Pattern;

/**
 * The canonical PII regexes shared by every redaction surface — the
 * stream-level {@link PiiRedactionFilter} and the capture-time
 * {@code PiiTapeRedactor} — so the definition of "PII" has one source instead
 * of drifting per consumer.
 */
public final class PiiPatterns {

    /** Email addresses. */
    public static final Pattern EMAIL =
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    /** US phone numbers (with optional +1, separators). */
    public static final Pattern US_PHONE =
            Pattern.compile("(?:\\+?1[\\s.-]?)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}");

    /** US Social Security numbers ({@code ddd-dd-dddd}). */
    public static final Pattern SSN =
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

    /** Credit-card numbers (13-19 digits, optional separators). */
    public static final Pattern CREDIT_CARD =
            Pattern.compile("\\b(?:\\d[\\s-]?){13,19}\\b");

    private PiiPatterns() {
    }
}
