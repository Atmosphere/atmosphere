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
 *
 * <h2>Purpose-aware probe suppression</h2>
 * Each built-in probe carries a domain marker; a probe is skipped when the
 * declared {@link ScopeConfig#purpose()} sits in that probe's own domain. A
 * probe exists to catch requests <em>outside</em> the purpose — matching a
 * dental assistant's "what dosage of ibuprofen for my toothache?" against the
 * medical probe is a false signal by construction, and blocked exactly the
 * traffic those agents exist to serve. A dental agent still rejects
 * "write me a python script" (the code probes stay active), and a support bot
 * still rejects dosage questions (its purpose is not medical).
 */
public final class RuleBasedScopeGuardrail implements ScopeGuardrail {

    /** A built-in probe plus the purpose-domain in which it must not fire. */
    private record HijackProbe(Pattern probe, Pattern domain) {
    }

    /**
     * Built-in off-topic probes — compiled once, checked on every request
     * in addition to the config's explicit {@link ScopeConfig#forbiddenTopics()}.
     * These cover the canonical "McDonald's bot writes Python" patterns
     * regardless of whether the operator remembered to list them. Each probe is
     * suppressed when the declared purpose is in the probe's own domain (see
     * class Javadoc).
     */
    private static final Pattern CODE_DOMAIN = Pattern.compile(
            "\\b(cod(?:e|ing)|program|software|develop|engineer|computer|technical|debug"
                    + "|repo(?:sitor)?|git\\b|patch|codebase|script|sandbox)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MEDICAL_DOMAIN = Pattern.compile(
            "\\b(medic|dental|dentist|health|clinic|doctor|patient|nurse|pharma|therap|veterinar)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGAL_DOMAIN = Pattern.compile(
            "\\b(legal|law\\b|lawyer|attorney|paralegal|litigat)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FINANCIAL_DOMAIN = Pattern.compile(
            "\\b(financ|invest|bank|trading|wealth|account|insur|brokerage)",
            Pattern.CASE_INSENSITIVE);

    private static final HijackProbe[] HIJACK_PROBES = new HijackProbe[] {
            new HijackProbe(
                    Pattern.compile("\\b(write|generate|give me|show me).{0,20}(code|function|script|program|algorithm)\\b",
                            Pattern.CASE_INSENSITIVE), CODE_DOMAIN),
            new HijackProbe(
                    Pattern.compile("\\bpython\\b|\\bjavascript\\b|\\btypescript\\b|\\bjava\\b|\\bruby\\b|\\brust\\b|\\bgolang\\b|\\bc\\+\\+\\b",
                            Pattern.CASE_INSENSITIVE), CODE_DOMAIN),
            new HijackProbe(
                    Pattern.compile("\\b(linked\\s*list|binary\\s*tree|hash\\s*map|sorting\\s*algorithm)\\b",
                            Pattern.CASE_INSENSITIVE), CODE_DOMAIN),
            new HijackProbe(
                    Pattern.compile("\\b(diagnose|diagnosis|prescribe|prescription|dosage|symptom)\\b",
                            Pattern.CASE_INSENSITIVE), MEDICAL_DOMAIN),
            new HijackProbe(
                    Pattern.compile("\\b(sue|lawsuit|attorney|legal\\s*action|class\\s*action)\\b",
                            Pattern.CASE_INSENSITIVE), LEGAL_DOMAIN),
            new HijackProbe(
                    Pattern.compile("\\b(invest|stock\\s*pick|financial\\s*advice|buy\\s*bitcoin|crypto\\s*recommendation)\\b",
                            Pattern.CASE_INSENSITIVE), FINANCIAL_DOMAIN),
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

        // 2. Built-in hijacking probes (code, medical, legal, financial) — each
        // suppressed when the declared purpose is in the probe's own domain, so
        // a dental agent's dosage questions are not "hijacking" while a support
        // bot's still are.
        var loweredPurpose = config.purpose() == null
                ? "" : config.purpose().toLowerCase(Locale.ROOT);
        for (var hijackProbe : HIJACK_PROBES) {
            if (hijackProbe.domain().matcher(loweredPurpose).find()) {
                continue; // the purpose owns this domain — the probe is a false signal here
            }
            var matcher = hijackProbe.probe().matcher(lowered);
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
