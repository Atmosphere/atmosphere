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

    /**
     * Candidate credit-card numbers: 13 to 19 digits, optionally grouped by
     * single spaces or hyphens ({@code 4111 1111 1111 1111},
     * {@code 4111-1111-1111-1111}, {@code 3782 822463 10005},
     * {@code 4111111111111111}), standing on their own — not after a letter, a
     * digit, an underscore, a {@code +}, a {@code -} or the {@code d.} of a
     * decimal, and not before another digit or a {@code .} followed by one.
     *
     * <p>A regex cannot compute a checksum, so a match is only a candidate:
     * {@link #find(Pattern, String)} and {@link #replaceAll(Pattern, String, String)}
     * keep it only when it passes the Luhn check, cutting it back to its longest
     * group-aligned prefix that does when the whole run does not
     * ({@code 4111 1111 1111 1111 12/27} is the card plus an expiry). Use those
     * methods, not this pattern's own matcher, to detect or redact cards. A
     * power of two, a factorial or another large integer in an answer
     * ({@code 2^50 = 1125899906842624}, {@code 1234567890123456}) fails Luhn and
     * is not a card. The trade-off: a random 13–19 digit number passes Luhn one
     * time in ten, so about one large integer in ten that stands on its own is
     * still taken for a card.</p>
     */
    public static final Pattern CREDIT_CARD =
            Pattern.compile("(?<![\\w+-])(?<!\\d\\.)\\d(?:[ -]?\\d){12,18}(?!\\d)(?!\\.\\d)");

    /**
     * Whether {@code pattern} finds PII in {@code text}. For
     * {@link #CREDIT_CARD} a candidate counts only when it passes the Luhn
     * check; any other pattern is used as is.
     */
    public static boolean find(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        if (pattern != CREDIT_CARD) {
            return matcher.find();
        }
        while (matcher.find()) {
            if (cardEnd(text, matcher.start(), matcher.end()) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replace every PII match of {@code pattern} in {@code text} with
     * {@code replacement}. For {@link #CREDIT_CARD} only the candidates that
     * pass the Luhn check are replaced, each up to its last card digit; any
     * other pattern behaves as {@link java.util.regex.Matcher#replaceAll(String)}.
     */
    public static String replaceAll(Pattern pattern, String text, String replacement) {
        if (pattern != CREDIT_CARD) {
            return pattern.matcher(text).replaceAll(replacement);
        }
        var matcher = CREDIT_CARD.matcher(text);
        StringBuilder out = null;
        var last = 0;
        while (matcher.find()) {
            var end = cardEnd(text, matcher.start(), matcher.end());
            if (end < 0) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length());
            }
            out.append(text, last, matcher.start()).append(replacement);
            last = end;
        }
        if (out == null) {
            return text;
        }
        return out.append(text, last, text.length()).toString();
    }

    /**
     * Where the card in the candidate {@code text[start, end)} ends: {@code end}
     * when the whole candidate passes Luhn, else the end of its longest prefix
     * that stops before a separator, holds 13 to 19 digits and passes Luhn, or
     * {@code -1} when there is none.
     */
    private static int cardEnd(String text, int start, int end) {
        for (var cut = end; cut > start; cut--) {
            if (cut < end && !isSeparator(text.charAt(cut))) {
                continue;
            }
            var digits = 0;
            for (var i = start; i < cut; i++) {
                if (Character.isDigit(text.charAt(i))) {
                    digits++;
                }
            }
            if (digits < 13) {
                return -1;
            }
            if (digits <= 19 && passesLuhn(text, start, cut)) {
                return cut;
            }
        }
        return -1;
    }

    private static boolean isSeparator(char c) {
        return c == ' ' || c == '-';
    }

    /** The Luhn (mod 10) checksum over the digits of {@code text[start, end)}. */
    private static boolean passesLuhn(String text, int start, int end) {
        var sum = 0;
        var doubled = false;
        for (var i = end - 1; i >= start; i--) {
            var c = text.charAt(i);
            if (c < '0' || c > '9') {
                continue;
            }
            var digit = c - '0';
            if (doubled) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }

    private PiiPatterns() {
    }
}
