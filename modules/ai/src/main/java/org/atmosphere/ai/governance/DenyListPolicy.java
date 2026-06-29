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
package org.atmosphere.ai.governance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deny a request when its message matches any configured phrase or regex.
 * The simplest possible content policy — two-line {@code new
 * DenyListPolicy("no-sql", "DROP TABLE", "rm -rf")} blocks literal phrases;
 * {@link #fromRegex(String, String, String...)} accepts compiled patterns
 * for anything fancier.
 *
 * <p>Phrase matching is <b>case-insensitive substring</b> — operator-friendly
 * and hard to evade without regex. For structural patterns (SSN, credit
 * cards) use {@link #fromRegex} or the dedicated
 * {@link org.atmosphere.ai.guardrails.PiiRedactionGuardrail}.</p>
 *
 * <p>Symmetric with {@link AllowListPolicy} when that exists; deny-list is
 * the more common shape for known-bad blocking, allow-list for known-good
 * gating.</p>
 */
public final class DenyListPolicy implements GovernancePolicy {

    private static final Logger logger = LoggerFactory.getLogger(DenyListPolicy.class);

    private final String name;
    private final String source;
    private final String version;
    private final List<Pattern> patterns;

    /** Create from literal case-insensitive substrings. */
    public DenyListPolicy(String name, String... phrases) {
        this(name, "code:" + DenyListPolicy.class.getName(), "1", toLiteralPatterns(phrases));
    }

    public DenyListPolicy(String name, String source, String version, List<Pattern> patterns) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        Objects.requireNonNull(patterns, "patterns");
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("deny-list requires at least one phrase or pattern");
        }
        for (var pattern : patterns) {
            if (pattern == null) {
                throw new IllegalArgumentException("patterns must not contain null");
            }
        }
        this.name = name;
        this.source = source;
        this.version = version;
        this.patterns = List.copyOf(patterns);
    }

    /** Regex builder — each pattern is compiled with {@link Pattern#CASE_INSENSITIVE}. */
    public static DenyListPolicy fromRegex(String name, String first, String... rest) {
        var all = new ArrayList<Pattern>(rest.length + 1);
        all.add(Pattern.compile(first, Pattern.CASE_INSENSITIVE));
        for (var regex : rest) {
            all.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        }
        return new DenyListPolicy(name, "code:" + DenyListPolicy.class.getName(), "1", all);
    }

    @Override public String name() { return name; }
    @Override public String source() { return source; }
    @Override public String version() { return version; }

    /** Exposed so admin surfaces can render the list (literals shown un-quoted). */
    public List<String> patternStrings() {
        var out = new ArrayList<String>(patterns.size());
        for (var p : patterns) out.add(displayForm(p));
        return List.copyOf(out);
    }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        if (context.phase() == PolicyContext.Phase.POST_RESPONSE) {
            // Also screen the response — if the LLM emits a denied phrase
            // verbatim, we prefer to deny after the fact rather than leak it.
            return screen(context.accumulatedResponse(), "response");
        }
        var request = context.request();
        if (request == null || request.message() == null) {
            return PolicyDecision.admit();
        }
        return screen(request.message(), "request");
    }

    private PolicyDecision screen(String text, String origin) {
        if (text == null || text.isBlank()) {
            return PolicyDecision.admit();
        }
        for (var pattern : patterns) {
            if (pattern.matcher(text).find()) {
                // The user-facing reason deliberately omits the matched
                // phrase/regex: echoing the rule back both leaks the deny-list
                // (aiding evasion) and surfaces Pattern.quote()'s \Q..\E
                // artifacts. Operators get the specific rule on the DEBUG log.
                if (logger.isDebugEnabled()) {
                    logger.debug("deny-list '{}' blocked {} on rule '{}'",
                            name, origin, displayForm(pattern));
                }
                return PolicyDecision.deny("deny-list: " + origin + " matched a denied phrase");
            }
        }
        return PolicyDecision.admit();
    }

    /**
     * Human-readable form of a compiled pattern: literal phrases compiled via
     * {@link Pattern#quote(String)} are unwrapped from their {@code \Q..\E}
     * envelope; genuine regexes are returned verbatim.
     */
    private static String displayForm(Pattern pattern) {
        var raw = pattern.pattern();
        if (raw.startsWith("\\Q") && raw.endsWith("\\E")
                && raw.indexOf("\\E") == raw.length() - 2) {
            return raw.substring(2, raw.length() - 2);
        }
        return raw;
    }

    private static List<Pattern> toLiteralPatterns(String[] phrases) {
        if (phrases == null || phrases.length == 0) {
            return List.of();
        }
        return Arrays.stream(phrases)
                .filter(p -> p != null && !p.isBlank())
                .map(p -> Pattern.compile(Pattern.quote(p), Pattern.CASE_INSENSITIVE))
                .toList();
    }
}
