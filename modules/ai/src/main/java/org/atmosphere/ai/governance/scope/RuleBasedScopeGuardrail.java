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
package org.atmosphere.ai.governance.scope;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.annotation.AgentScope;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sub-millisecond keyword / regex scope classifier. Good for coarse, clearly
 * delineated scopes (customer support that should never answer legal
 * questions, math tutor that should never discuss medical topics). Brittle
 * on creative phrasings — which is why {@link AgentScope.Tier#EMBEDDING_SIMILARITY}
 * is the default tier.
 *
 * <h2>Algorithm</h2>
 * A request is OUT_OF_SCOPE iff its lowercased message contains any
 * {@link ScopeConfig#forbiddenTopics()} as a word-boundaried substring, or
 * matches any of the canned "clearly off-topic" probes the guardrail holds
 * for common hijacking patterns (code-writing, medical diagnosis, legal
 * advice, financial recommendations). The full hijacking-probe list is
 * bundled so operators get defense-in-depth without having to enumerate
 * every topic themselves — the {@link ScopeConfig#forbiddenTopics()} field
 * is additive, not a full-list substitute.
 */
public final class RuleBasedScopeGuardrail implements ScopeGuardrail {

    /**
     * Built-in off-topic probes — compiled once, checked on every request
     * in addition to the config's explicit {@link ScopeConfig#forbiddenTopics()}.
     * These cover the canonical "McDonald's bot writes Python" patterns
     * regardless of whether the operator remembered to list them.
     */
    private static final Pattern[] HIJACK_PROBES = new Pattern[] {
            Pattern.compile("\\b(write|generate|give me|show me).{0,20}(code|function|script|program|algorithm)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpython\\b|\\bjavascript\\b|\\btypescript\\b|\\bjava\\b|\\bruby\\b|\\brust\\b|\\bgolang\\b|\\bc\\+\\+\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(linked\\s*list|binary\\s*tree|hash\\s*map|sorting\\s*algorithm)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(diagnose|diagnosis|prescribe|prescription|dosage|symptom)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(sue|lawsuit|attorney|legal\\s*action|class\\s*action)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(invest|stock\\s*pick|financial\\s*advice|buy\\s*bitcoin|crypto\\s*recommendation)\\b",
                    Pattern.CASE_INSENSITIVE),
    };

    @Override
    public AgentScope.Tier tier() {
        return AgentScope.Tier.RULE_BASED;
    }

    @Override
    public Decision evaluate(AiRequest request, ScopeConfig config) {
        if (config.unrestricted()) {
            return Decision.inScope(Double.NaN);
        }
        if (request == null || request.message() == null) {
            return Decision.inScope(Double.NaN);
        }
        var lowered = request.message().toLowerCase(Locale.ROOT);

        // 1. Operator-declared forbidden topics (word-boundaried substring match)
        for (var topic : config.forbiddenTopics()) {
            if (topic == null || topic.isBlank()) continue;
            if (containsWordBoundary(lowered, topic.toLowerCase(Locale.ROOT))) {
                return Decision.outOfScope(
                        "message references forbidden topic: '" + topic + "'",
                        Double.NaN);
            }
        }

        // 2. Built-in hijacking probes (code, medical, legal, financial)
        for (var probe : HIJACK_PROBES) {
            var matcher = probe.matcher(lowered);
            if (matcher.find()) {
                return Decision.outOfScope(
                        "message matched built-in hijacking probe: '" + matcher.group() + "'",
                        Double.NaN);
            }
        }

        return Decision.inScope(Double.NaN);
    }

    /** Substring match with word boundaries on both sides (non-word char or string edge). */
    private static boolean containsWordBoundary(String haystack, String needle) {
        if (needle.isEmpty()) return false;
        var idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            var before = idx == 0 || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
            var afterIdx = idx + needle.length();
            var after = afterIdx == haystack.length()
                    || !Character.isLetterOrDigit(haystack.charAt(afterIdx));
            if (before && after) return true;
            idx = afterIdx;
        }
        return false;
    }
}
