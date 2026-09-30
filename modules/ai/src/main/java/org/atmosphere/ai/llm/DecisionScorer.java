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
package org.atmosphere.ai.llm;

import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.DecisionDistribution;
import org.atmosphere.ai.TokenLogprob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the model's probability distribution over a {@link DecisionField}'s
 * allowed values from the {@code top_logprobs} at the tokens that carry the
 * field's value, and turns it into a
 * {@link AiConfidence.Source#DECISION_LOGPROBS} confidence.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Concatenate the round's sampled tokens and locate the value of the
 *       top-level property {@code field} in the JSON text (a small
 *       string/depth-aware scan; the first top-level occurrence wins). For an
 *       enum the value starts after the opening quote.</li>
 *   <li>Walk the sampled path from the token that contains the value start.
 *       At each position every top alternative is matched against the allowed
 *       values: an alternative consistent with exactly one value adds its
 *       probability (times the probability of the sampled prefix so far) to
 *       that value; one consistent with none — a formatting or off-schema
 *       token — is dropped. The sampled token narrows the candidate set and
 *       the walk continues while more than one value is still possible.</li>
 *   <li>Renormalise over the attributed mass. The pre-normalisation total is
 *       reported as {@link DecisionDistribution#observedMass()}.</li>
 * </ol>
 *
 * <h2>Approximations (stated, not hidden)</h2>
 * <ul>
 *   <li><b>Multi-token values.</b> Providers return alternatives only along
 *       the sampled path. A non-sampled alternative that is still a prefix of
 *       several allowed values (e.g. {@code "C"} for {@code CONFIRM} and
 *       {@code CANCEL}, when the model actually sampled {@code "ESC"}) has its
 *       probability split evenly among them, because how it would have
 *       continued was never observed. Even splitting can only lower the
 *       concentration, so the error runs toward escalation, never toward
 *       false confidence.</li>
 *   <li><b>Truncated alternatives.</b> Mass outside the requested
 *       {@code top_logprobs} is unobserved; values never offered score
 *       {@code 0} and the distribution is renormalised over what was
 *       observed.</li>
 * </ul>
 *
 * <p>Scoring declines (empty result — the caller stays silent and the
 * model-reported field applies) when the field is absent from the output, the
 * sampled value is not an allowed value, any token on the walked path carries
 * no {@code top_logprobs}, or no mass could be attributed.</p>
 */
final class DecisionScorer {

    private static final Logger logger = LoggerFactory.getLogger(DecisionScorer.class);

    /** Bound on tokens walked for one value — no allowed value needs more. */
    static final int MAX_DECISION_TOKENS = 16;

    private DecisionScorer() {
    }

    /**
     * Score the decision carried by {@code entries} (one model round, in order).
     *
     * @return the decision confidence, or empty when the distribution cannot
     *         be built (reason logged at DEBUG)
     */
    static Optional<AiConfidence> score(List<LogprobCapture.Entry> entries, DecisionField field) {
        var text = new StringBuilder();
        var starts = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            starts[i] = text.length();
            text.append(entries.get(i).sampled().token());
        }
        var valueStart = locateValue(text, field.name(), field.quoted());
        if (valueStart < 0) {
            return decline(field, "the field is absent from the response JSON");
        }
        var sampledValue = readValue(text, valueStart, field.quoted());
        if (sampledValue == null || !field.values().contains(sampledValue)) {
            return decline(field, "the emitted value is not one of " + field.values());
        }
        var first = tokenAt(starts, entries, valueStart);
        if (first < 0) {
            return decline(field, "no token carries the value start");
        }

        var mass = new LinkedHashMap<String, Double>();
        for (var v : field.values()) {
            mass.put(v, 0.0);
        }
        var valueTokens = new ArrayList<TokenLogprob>();
        List<String> candidates = field.values();
        var committed = "";
        var pathMass = 1.0;
        var prefix = entries.get(first).sampled().token().substring(0, valueStart - starts[first]);

        for (int j = first; ; j++) {
            if (j >= entries.size() || j - first >= MAX_DECISION_TOKENS) {
                // The value never narrowed to one candidate within the bound:
                // split what the sampled path carries rather than guess.
                spread(mass, pathMass, candidates);
                break;
            }
            var entry = entries.get(j);
            if (entry.top().isEmpty()) {
                return decline(field, "the provider returned no top_logprobs for a value token");
            }
            var sampled = entry.sampled();
            valueTokens.add(sampled);
            var tokenPrefix = j == first ? prefix : "";
            var sampledSkipped = false;
            for (var alt : entry.top()) {
                if (!sampledSkipped && alt.token().equals(sampled.token())) {
                    // Accounted for below through the sampled path.
                    sampledSkipped = true;
                    continue;
                }
                var continuation = continuation(alt.token(), tokenPrefix, field.quoted());
                if (continuation == null || continuation.isEmpty()) {
                    continue;
                }
                var consistent = consistent(committed + continuation, candidates, field.quoted());
                if (!consistent.isEmpty()) {
                    spread(mass, pathMass * alt.linearProbability(), consistent);
                }
            }
            var sampledContinuation = continuation(sampled.token(), tokenPrefix, field.quoted());
            var next = committed + (sampledContinuation != null ? sampledContinuation : "");
            var consistent = consistent(next, candidates, field.quoted());
            if (consistent.isEmpty()) {
                return decline(field, "the sampled path left every allowed value");
            }
            pathMass *= sampled.linearProbability();
            if (consistent.size() == 1) {
                spread(mass, pathMass, consistent);
                break;
            }
            committed = next;
            candidates = consistent;
        }

        var total = 0.0;
        for (var m : mass.values()) {
            total += m;
        }
        if (!(total > 0.0)) {
            return decline(field, "no probability mass could be attributed to an allowed value");
        }
        var probabilities = new LinkedHashMap<String, Double>();
        for (Map.Entry<String, Double> e : mass.entrySet()) {
            probabilities.put(e.getKey(), e.getValue() / total);
        }
        var distribution = new DecisionDistribution(field.name(), probabilities, Math.min(1.0, total));
        return Optional.of(AiConfidence.fromDecision(distribution, valueTokens));
    }

    private static Optional<AiConfidence> decline(DecisionField field, String reason) {
        logger.debug("Decision confidence for '{}' not scored: {}; no native confidence emitted",
                field.name(), reason);
        return Optional.empty();
    }

    /**
     * The part of an alternative token that falls inside the value, given the
     * sampled token's text before the value start. {@code null} when the
     * alternative does not share that prefix (it diverged before the value).
     * For a bare literal a whitespace-only prefix is optional, since JSON
     * allows either spacing.
     */
    private static String continuation(String alt, String prefix, boolean quoted) {
        if (alt.startsWith(prefix)) {
            var rest = alt.substring(prefix.length());
            return quoted ? rest : rest.stripLeading();
        }
        if (!quoted && prefix.isBlank()) {
            return alt.stripLeading();
        }
        return null;
    }

    /**
     * Allowed values the text so far is consistent with: either the text is a
     * prefix of the value as rendered in JSON, or it runs past the complete
     * rendered value (closing quote, or a non-identifier character after a
     * literal).
     */
    private static List<String> consistent(String text, List<String> candidates, boolean quoted) {
        var result = new ArrayList<String>(candidates.size());
        for (var v : candidates) {
            var rendered = quoted ? v + "\"" : v;
            if (rendered.startsWith(text)) {
                result.add(v);
            } else if (text.startsWith(rendered)) {
                if (quoted) {
                    result.add(v);
                } else {
                    var after = text.charAt(rendered.length());
                    if (!Character.isLetterOrDigit(after) && after != '_') {
                        result.add(v);
                    }
                }
            }
        }
        return result;
    }

    private static void spread(Map<String, Double> mass, double m, List<String> values) {
        var share = m / values.size();
        for (var v : values) {
            mass.merge(v, share, Double::sum);
        }
    }

    private static int tokenAt(int[] starts, List<LogprobCapture.Entry> entries, int offset) {
        for (int i = starts.length - 1; i >= 0; i--) {
            if (starts[i] <= offset) {
                var end = starts[i] + entries.get(i).sampled().token().length();
                return offset < end ? i : -1;
            }
        }
        return -1;
    }

    /**
     * Offset where the value of top-level property {@code name} starts
     * (after the opening quote when {@code quoted}), or {@code -1}. Tracks
     * string literals and nesting depth so a same-named nested property or a
     * string containing the name cannot match.
     */
    static int locateValue(CharSequence text, String name, boolean quoted) {
        var depth = 0;
        var n = text.length();
        var i = 0;
        while (i < n) {
            var c = text.charAt(i);
            if (c == '"') {
                var end = endOfString(text, i);
                if (end < 0) {
                    return -1;
                }
                if (depth == 1) {
                    var colon = skipWhitespace(text, end + 1);
                    if (colon < n && text.charAt(colon) == ':'
                            && name.contentEquals(text.subSequence(i + 1, end))) {
                        var v = skipWhitespace(text, colon + 1);
                        if (v >= n) {
                            return -1;
                        }
                        // Type check both ways: an enum value is a JSON
                        // string, a boolean value is a bare literal.
                        var isString = text.charAt(v) == '"';
                        if (quoted) {
                            return isString ? v + 1 : -1;
                        }
                        return isString ? -1 : v;
                    }
                }
                i = end + 1;
                continue;
            }
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
            i++;
        }
        return -1;
    }

    private static String readValue(CharSequence text, int start, boolean quoted) {
        var n = text.length();
        var i = start;
        if (quoted) {
            while (i < n) {
                var c = text.charAt(i);
                if (c == '\\') {
                    return null;
                }
                if (c == '"') {
                    return text.subSequence(start, i).toString();
                }
                i++;
            }
            return null;
        }
        while (i < n && Character.isLetter(text.charAt(i))) {
            i++;
        }
        return i > start ? text.subSequence(start, i).toString() : null;
    }

    private static int endOfString(CharSequence text, int openQuote) {
        var i = openQuote + 1;
        while (i < text.length()) {
            var c = text.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '"') {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    private static int skipWhitespace(CharSequence text, int from) {
        var i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }
}
