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

    /**
     * Where a phone number ends: not before another digit, and not before a
     * {@code .} or {@code -} that carries the number on ({@code 514-555-1234.5},
     * {@code 514-555-1234-99}). A sentence-ending period still ends it.
     */
    private static final String PHONE_END = "(?!\\d)(?![.-]\\d)";

    /**
     * A North American number written as one: three groups of 3, 3 and 4
     * digits, the area code in parentheses or followed by a separator, and a
     * separator between the last two groups — a space, a dot or a hyphen
     * ({@code (514) 555-1234}, {@code 514-555-1234}, {@code 514.555.1234},
     * {@code 514 555 1234}, {@code 1-800-555-1234}). It does not start right
     * after a letter, a digit, an underscore, a {@code +} or a {@code -}.
     */
    private static final String NA_FORMATTED = "(?<![\\w+-])(?:1[ .-])?(?:\\(\\d{3}\\) ?|\\d{3}[ .-])"
            + "\\d{3}[ .-]\\d{4}" + PHONE_END;

    /** A North American number after {@code +1}; the separators are optional
     *  ({@code +15145551234}, {@code +1 (514) 555-1234}). */
    private static final String NA_PLUS_ONE = "(?<![\\w+)])\\+1[ .-]?(?:\\(\\d{3}\\) ?|\\d{3}[ .-]?)"
            + "\\d{3}[ .-]?\\d{4}" + PHONE_END;

    /**
     * An international number: a {@code +}, a country code and groups of
     * digits, at least 8 digits in all and never a run of 16 or more (E.164
     * numbers have at most 15), each group optionally after a
     * space, a dot or a hyphen and optionally in parentheses
     * ({@code +44 20 7946 0958}, {@code +33 1 23 45 67 89},
     * {@code +491701234567}). A {@code +} after a letter, a digit or a
     * closing parenthesis is an operator, not a phone ({@code 3+15145551234}).
     */
    private static final String INTERNATIONAL = "(?<![\\w+)])\\+(?=(?:[ .()-]{0,2}\\d){8})(?!\\d{16})"
            + "\\d{1,3}(?:[ .-]?\\(?\\d{1,4}\\)?){1,5}" + PHONE_END;

    /**
     * North American phone numbers ({@code (514) 555-1234}, {@code 514-555-1234},
     * {@code 514.555.1234}, {@code 1-800-555-1234}, {@code +15145551234},
     * {@code +1 (514) 555-1234}).
     *
     * <p>A number is a phone when it is written as one: with {@code +1}, or with
     * separators or parentheses between its groups. A bare run of digits is
     * not — {@code 5145551234}, a math result ({@code 2^31 = 2147483648}), an
     * order number, the digits of a decimal ({@code 3.14159265358}) — and
     * neither is a formatted number that runs on into more digits
     * ({@code 514-555-12345}). The trade-off: an unformatted 10-digit phone
     * number with no {@code +} is no longer detected. A newline or a tab is not
     * a separator.</p>
     */
    public static final Pattern US_PHONE = Pattern.compile(NA_PLUS_ONE + "|" + NA_FORMATTED);

    /**
     * Phone numbers, North American and international: {@link #US_PHONE}'s
     * forms plus any {@code +}-prefixed number of at least 8 digits, in groups
     * ({@code +44 20 7946 0958}, {@code +33 1 23 45 67 89}). The same rule
     * applies: a bare run of digits with no {@code +} and no separators is not a
     * phone, nor is a {@code +} that follows a letter, a digit or a closing
     * parenthesis (an operator), nor one followed by 16 or more digits.
     */
    public static final Pattern PHONE = Pattern.compile(INTERNATIONAL + "|" + NA_FORMATTED);

    /** US Social Security numbers ({@code ddd-dd-dddd}). */
    public static final Pattern SSN =
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

    /** Credit-card numbers (13-19 digits, optional separators). */
    public static final Pattern CREDIT_CARD =
            Pattern.compile("\\b(?:\\d[\\s-]?){13,19}\\b");

    private PiiPatterns() {
    }
}
