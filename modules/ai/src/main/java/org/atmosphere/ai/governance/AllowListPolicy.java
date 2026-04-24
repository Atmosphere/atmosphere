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

/**
 * Admits a request only when its message matches at least one configured
 * phrase or regex. Complement to {@link DenyListPolicy}:
 *
 * <ul>
 *   <li>{@code DenyListPolicy}: known-bad blocking — default-allow with
 *       surgical blocks</li>
 *   <li>{@code AllowListPolicy}: locked-down endpoints — default-deny with
 *       explicit admits</li>
 * </ul>
 *
 * <p>Use cases: a customer-support endpoint that must only engage on
 * orders/billing/shipping keywords; a compliance endpoint that refuses
 * anything not mentioning a recognized policy term. For fuzzier "is this
 * on-topic?" scopes, prefer {@code @AgentScope} — the scope guardrail uses
 * embeddings and tolerates paraphrase. Allow-list is the appropriate shape
 * when the allowed vocabulary is finite and auditable.</p>
 *
 * <p>Post-response phase always admits — response filtering is a
 * {@link DenyListPolicy} concern (known bad outputs), not an allow-list one.</p>
 */
public final class AllowListPolicy implements GovernancePolicy {

    /** Conventional deny reason — admin surfaces key off this for "off-topic" UX. */
    public static final String DENY_REASON_OFF_ALLOW_LIST = "allow-list: message matched no configured phrase";

    private final String name;
    private final String source;
    private final String version;
    private final List<Pattern> patterns;

    /** Create from literal case-insensitive substrings — any match admits. */
    public AllowListPolicy(String name, String... phrases) {
        this(name, "code:" + AllowListPolicy.class.getName(), "1", toLiteralPatterns(phrases));
    }

    public AllowListPolicy(String name, String source, String version, List<Pattern> patterns) {
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
            throw new IllegalArgumentException("allow-list requires at least one phrase or pattern");
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

    /** Regex builder — each pattern compiled with {@link Pattern#CASE_INSENSITIVE}. */
    public static AllowListPolicy fromRegex(String name, String first, String... rest) {
        var all = new ArrayList<Pattern>(rest.length + 1);
        all.add(Pattern.compile(first, Pattern.CASE_INSENSITIVE));
        for (var regex : rest) {
            all.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        }
        return new AllowListPolicy(name, "code:" + AllowListPolicy.class.getName(), "1", all);
    }

    @Override public String name() { return name; }
    @Override public String source() { return source; }
    @Override public String version() { return version; }

    public List<String> patternStrings() {
        var out = new ArrayList<String>(patterns.size());
        for (var p : patterns) out.add(p.pattern());
        return List.copyOf(out);
    }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        if (context.phase() == PolicyContext.Phase.POST_RESPONSE) {
            return PolicyDecision.admit();
        }
        var request = context.request();
        if (request == null || request.message() == null || request.message().isBlank()) {
            // An empty message can't match anything — deny, since the whole
            // point of an allow-list is "must be on-topic to engage".
            return PolicyDecision.deny(DENY_REASON_OFF_ALLOW_LIST);
        }
        var text = request.message();
        for (var pattern : patterns) {
            if (pattern.matcher(text).find()) {
                return PolicyDecision.admit();
            }
        }
        return PolicyDecision.deny(DENY_REASON_OFF_ALLOW_LIST);
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
