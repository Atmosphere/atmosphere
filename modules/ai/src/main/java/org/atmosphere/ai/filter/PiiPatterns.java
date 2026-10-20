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

    /**
     * A domain reserved for documentation and testing (RFC 2606, RFC 6761):
     * {@code example.com}, {@code example.net}, {@code example.org} and their
     * subdomains, and any name under the {@code example}, {@code test},
     * {@code invalid} and {@code localhost} top-level domains — matched
     * case-insensitively and only when it ends the address, so
     * {@code example.com.evil.io} or {@code notexample.com} is not one.
     */
    private static final String RESERVED_DOMAIN =
            "(?i:(?:[a-z0-9\\-]+\\.)*(?:example\\.(?:com|net|org)"
                    + "|[a-z0-9\\-]+\\.(?:example|test|invalid|localhost)))"
                    + "(?![a-zA-Z0-9\\-]|\\.[a-zA-Z0-9\\-])";

    /** Email addresses. The negative lookbehind rejects mid-run start
     *  positions and the possessive local-part quantifier skips pointless
     *  backtracking, keeping the scan linear on long runs of local-part
     *  characters (CodeQL java/polynomial-redos). Matches are unchanged:
     *  leftmost-first semantics already began every match at a run start.
     *  An address at a reserved documentation domain ({@code alice@example.com}
     *  in a code sample) belongs to no one and is not matched. */
    public static final Pattern EMAIL =
            Pattern.compile("(?<![a-zA-Z0-9._%+\\-])[a-zA-Z0-9._%+\\-]++@(?!" + RESERVED_DOMAIN
                    + ")[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

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
