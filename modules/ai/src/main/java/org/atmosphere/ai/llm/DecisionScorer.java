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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the model's probability distribution over a {@link DecisionField}'s
 * allowed values from the {@code top_logprobs} at the tokens that carry the
 * choice of the field's value, and turns it into a
 * {@link AiConfidence.Source#DECISION_LOGPROBS} confidence.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Concatenate the round's sampled tokens and locate the top-level
 *       property {@code field} in the JSON text (a small string/depth-aware
 *       scan; the first top-level occurrence wins): the key's closing quote
 *       and the start of its value.</li>
 *   <li>Walk the sampled path from the token that contains the key's closing
 *       quote. The value can be chosen before its first character — a token
 *       {@code " \""} whose rival is {@code " \"REJECT"}, or a rival that
 *       spells the separator differently — so every alternative is read as a
 *       replacement for its whole position: the fixed text before it, then
 *       the alternative, parsed past optional whitespace, the colon and (for
 *       an enum) the opening quote. An alternative consistent with exactly one
 *       value adds its probability (times the probability of the sampled
 *       prefix so far) to that value. One consistent with several values — a
 *       separator variant that has not reached the value yet, or a multi-token
 *       prefix such as {@code "C"} for {@code CONFIRM} and {@code CANCEL} —
 *       is <em>ambiguous</em> over that set, because how it would have
 *       continued was never observed. The rest of the position's
 *       probability — alternatives outside the requested {@code top_logprobs},
 *       off-schema or diverging tokens — is <em>unobserved</em> and ambiguous
 *       over every value still possible. The sampled token narrows the
 *       candidate set and the walk continues while more than one value is
 *       still possible.</li>
 *   <li>Assign the ambiguous and unobserved mass to the values least
 *       favourably for confidence: the allocation that minimises the largest
 *       value's probability, computed exactly
 *       ({@link #leastConcentrated}). The resulting distribution sums to
 *       {@code 1} without renormalising, and its
 *       {@link DecisionDistribution#normalizedMargin()} is the lowest
 *       concentration consistent with what the provider returned — a lower
 *       bound, so evidence the scorer could not attribute can only move the
 *       route toward escalation. {@link DecisionDistribution#observedMass()}
 *       reports the share that was attributed from listed alternatives.</li>
 * </ol>
 *
 * <p>A position before the value (the key's closing quote, the colon, the
 * opening quote) that carries no {@code top_logprobs} contributes its
 * non-sampled probability as unobserved mass. Scoring declines (empty result
 * — the caller stays silent and the model-reported field applies) when the
 * field is absent from the output, the sampled value is not an allowed value,
 * a token that carries part of the value has no {@code top_logprobs}, or no
 * mass could be observed.</p>
 */
final class DecisionScorer {

    private static final Logger logger = LoggerFactory.getLogger(DecisionScorer.class);

    /** Bound on tokens walked for one decision, from the key's closing quote. */
    static final int MAX_DECISION_TOKENS = 16;

    /** Slack on the flow capacities, absorbing floating-point rounding. */
    private static final double FLOW_EPS = 1e-12;

    /** Re-levelling passes over the ambiguous sets after the flow. */
    private static final int BALANCE_SWEEPS = 32;

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
        var location = locate(text, field.name(), field.quoted());
        if (location == null) {
            return decline(field, "the field is absent from the response JSON");
        }
        var keyEnd = location[0];
        var valueStart = location[1];
        var leadStart = keyEnd + 1;
        var sampledValue = readValue(text, valueStart, field.quoted());
        if (sampledValue == null || !field.values().contains(sampledValue)) {
            return decline(field, "the emitted value is not one of " + field.values());
        }
        var first = tokenAt(starts, entries, keyEnd);
        if (first < 0) {
            return decline(field, "no token carries the key's closing quote");
        }

        var values = field.values();
        var k = values.size();
        var firm = new double[k];
        var ambiguous = new LinkedHashMap<Integer, Double>();
        var observed = 0.0;
        var valueTokens = new ArrayList<TokenLogprob>();
        var candidates = (1 << k) - 1;
        var pathMass = 1.0;

        for (int j = first; ; j++) {
            if (j >= entries.size() || j - first >= MAX_DECISION_TOKENS) {
                // The value never narrowed to one candidate within the bound:
                // what the sampled path carries is ambiguous over what is left.
                ambiguous.merge(candidates, pathMass, Double::sum);
                observed += pathMass;
                break;
            }
            var entry = entries.get(j);
            var sampled = entry.sampled();
            var tokenEnd = starts[j] + sampled.token().length();
            var carriesValue = tokenEnd > valueStart;
            if (carriesValue) {
                if (entry.top().isEmpty()) {
                    return decline(field, "the provider returned no top_logprobs for a value token");
                }
                valueTokens.add(sampled);
            }
            // Text every alternative at this position must reproduce before
            // the key ends, and the fixed text between the key and it.
            var required = starts[j] < leadStart ? text.substring(starts[j], leadStart) : "";
            var context = starts[j] >= leadStart ? text.substring(leadStart, starts[j]) : "";
            var attributed = 0.0;
            var sampledSkipped = false;
            for (var alt : entry.top()) {
                if (!sampledSkipped && alt.token().equals(sampled.token())) {
                    // Accounted for below through the sampled path.
                    sampledSkipped = true;
                    continue;
                }
                var set = consistentSet(alt.token(), required, context, values, candidates, field.quoted());
                if (set == 0) {
                    continue;
                }
                var m = pathMass * alt.linearProbability();
                attributed += alt.linearProbability();
                observed += m;
                if (Integer.bitCount(set) == 1) {
                    firm[Integer.numberOfTrailingZeros(set)] += m;
                } else {
                    ambiguous.merge(set, m, Double::sum);
                }
            }
            var pSampled = sampled.linearProbability();
            var unobserved = Math.max(0.0, 1.0 - pSampled - attributed);
            if (unobserved > 0.0) {
                ambiguous.merge(candidates, pathMass * unobserved, Double::sum);
            }
            var next = consistentSet(sampled.token(), required, context, values, candidates, field.quoted());
            if (next == 0) {
                return decline(field, "the sampled path left every allowed value");
            }
            pathMass *= pSampled;
            if (Integer.bitCount(next) == 1) {
                firm[Integer.numberOfTrailingZeros(next)] += pathMass;
                observed += pathMass;
                break;
            }
            candidates = next;
        }

        var total = 0.0;
        for (var m : firm) {
            total += m;
        }
        for (var m : ambiguous.values()) {
            total += m;
        }
        if (!(total > 0.0) || !(observed > 0.0)) {
            return decline(field, "no probability mass could be attributed to an allowed value");
        }
        var mass = leastConcentrated(firm, ambiguous);
        var probabilities = new LinkedHashMap<String, Double>();
        for (int v = 0; v < k; v++) {
            probabilities.put(values.get(v), mass[v] / total);
        }
        var distribution = new DecisionDistribution(field.name(), probabilities,
                Math.min(1.0, observed / total));
        return Optional.of(AiConfidence.fromDecision(distribution, valueTokens));
    }

    private static Optional<AiConfidence> decline(DecisionField field, String reason) {
        logger.debug("Decision confidence for '{}' not scored: {}; no native confidence emitted",
                field.name(), reason);
        return Optional.empty();
    }

    /**
     * Allowed values (as a bit set over {@code values}, restricted to
     * {@code candidates}) that the text is still consistent with when
     * {@code token} occupies the current position; {@code 0} when it diverges
     * from the key, from the JSON separator, or from every value.
     */
    private static int consistentSet(String token, String required, String context,
                                     List<String> values, int candidates, boolean quoted) {
        String lead;
        if (!required.isEmpty()) {
            if (!token.startsWith(required)) {
                return 0;
            }
            lead = token.substring(required.length());
        } else {
            lead = context + token;
        }
        var valueText = valueText(lead, quoted);
        if (valueText == null) {
            return 0;
        }
        var set = 0;
        for (int v = 0; v < values.size(); v++) {
            if ((candidates & (1 << v)) != 0 && consistent(valueText, values.get(v), quoted)) {
                set |= 1 << v;
            }
        }
        return set;
    }

    /**
     * The value text inside {@code lead} (the text right after the key's
     * closing quote): skip whitespace, the colon, whitespace and — for an
     * enum — the opening quote. An empty string when the lead stops before
     * the value starts (every value still possible); {@code null} when it
     * breaks the JSON separator.
     */
    private static String valueText(String lead, boolean quoted) {
        var n = lead.length();
        var i = skipWhitespace(lead, 0);
        if (i == n) {
            return "";
        }
        if (lead.charAt(i) != ':') {
            return null;
        }
        i = skipWhitespace(lead, i + 1);
        if (i == n) {
            return "";
        }
        if (quoted) {
            return lead.charAt(i) == '"' ? lead.substring(i + 1) : null;
        }
        return lead.substring(i);
    }

    /**
     * Whether value text so far is consistent with {@code value}: either a
     * prefix of the value as rendered in JSON, or it runs past the complete
     * rendered value (closing quote, or a non-identifier character after a
     * literal).
     */
    private static boolean consistent(String text, String value, boolean quoted) {
        var rendered = quoted ? value + "\"" : value;
        if (rendered.startsWith(text)) {
            return true;
        }
        if (!text.startsWith(rendered)) {
            return false;
        }
        if (quoted) {
            return true;
        }
        var after = text.charAt(rendered.length());
        return !Character.isLetterOrDigit(after) && after != '_';
    }

    /**
     * Assign each ambiguous mass (bit set of values → mass) to its values so
     * that the largest resulting value mass is as small as possible, and
     * return the per-value masses. The optimum {@code T*} is
     * {@code max over value sets U of (firm(U) + ambiguous mass confined to U) / |U|}
     * (Hall's condition for the fractional assignment), and an allocation
     * reaching it is a max flow with value capacities {@code T* - firm(v)}.
     */
    static double[] leastConcentrated(double[] firm, Map<Integer, Double> ambiguous) {
        var k = firm.length;
        var result = firm.clone();
        if (ambiguous.isEmpty()) {
            return result;
        }
        var sets = new int[ambiguous.size()];
        var amounts = new double[ambiguous.size()];
        var c = 0;
        for (var e : ambiguous.entrySet()) {
            sets[c] = e.getKey();
            amounts[c] = e.getValue();
            c++;
        }
        var target = 0.0;
        for (int u = 1; u < 1 << k; u++) {
            var sum = 0.0;
            for (int v = 0; v < k; v++) {
                if ((u & (1 << v)) != 0) {
                    sum += firm[v];
                }
            }
            for (int i = 0; i < c; i++) {
                if ((sets[i] & ~u) == 0) {
                    sum += amounts[i];
                }
            }
            target = Math.max(target, sum / Integer.bitCount(u));
        }

        // Nodes: source 0, ambiguous sets 1..c, values c+1..c+k, sink c+k+1.
        var n = c + k + 2;
        var sink = n - 1;
        var cap = new double[n][n];
        for (int i = 0; i < c; i++) {
            cap[0][1 + i] = amounts[i];
            for (int v = 0; v < k; v++) {
                if ((sets[i] & (1 << v)) != 0) {
                    cap[1 + i][1 + c + v] = amounts[i];
                }
            }
        }
        for (int v = 0; v < k; v++) {
            cap[1 + c + v][sink] = Math.max(0.0, target - firm[v]) + FLOW_EPS;
        }
        var flow = new double[n][n];
        var parent = new int[n];
        while (true) {
            Arrays.fill(parent, -1);
            parent[0] = 0;
            var queue = new ArrayDeque<Integer>();
            queue.add(0);
            while (!queue.isEmpty() && parent[sink] < 0) {
                var x = queue.poll();
                for (int y = 0; y < n; y++) {
                    if (parent[y] < 0 && cap[x][y] - flow[x][y] > FLOW_EPS) {
                        parent[y] = x;
                        queue.add(y);
                    }
                }
            }
            if (parent[sink] < 0) {
                break;
            }
            var push = Double.MAX_VALUE;
            for (int y = sink; y != 0; y = parent[y]) {
                push = Math.min(push, cap[parent[y]][y] - flow[parent[y]][y]);
            }
            for (int y = sink; y != 0; y = parent[y]) {
                flow[parent[y]][y] += push;
                flow[y][parent[y]] -= push;
            }
        }
        var alloc = new double[c][k];
        for (int i = 0; i < c; i++) {
            for (int v = 0; v < k; v++) {
                alloc[i][v] = Math.max(0.0, flow[1 + i][1 + c + v]);
                result[v] += alloc[i][v];
            }
        }
        // The flow reaches T* but may pile a set's mass on one member. Re-level
        // each set in turn over its members (water-filling): that never raises
        // the largest mass, allocates each set's full amount (absorbing flow
        // rounding), and spreads mass evenly where the evidence cannot tell
        // the values apart.
        for (int sweep = 0; sweep < BALANCE_SWEEPS; sweep++) {
            for (int i = 0; i < c; i++) {
                for (int v = 0; v < k; v++) {
                    result[v] -= alloc[i][v];
                }
                waterFill(result, sets[i], amounts[i], alloc[i]);
                for (int v = 0; v < k; v++) {
                    result[v] += alloc[i][v];
                }
            }
        }
        return result;
    }

    /**
     * Spread {@code amount} over the members of {@code set} so the smallest
     * loads rise to a common level, writing each member's share to
     * {@code out}.
     */
    private static void waterFill(double[] loads, int set, double amount, double[] out) {
        Arrays.fill(out, 0.0);
        var members = new ArrayList<Integer>(Integer.bitCount(set));
        for (int v = 0; v < loads.length; v++) {
            if ((set & (1 << v)) != 0) {
                members.add(v);
            }
        }
        members.sort((a, b) -> Double.compare(loads[a], loads[b]));
        var sum = 0.0;
        var level = 0.0;
        for (int p = 0; p < members.size(); p++) {
            sum += loads[members.get(p)];
            level = (amount + sum) / (p + 1);
            if (p + 1 == members.size() || level <= loads[members.get(p + 1)]) {
                break;
            }
        }
        for (var v : members) {
            out[v] = Math.max(0.0, level - loads[v]);
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
     * (after the opening quote when {@code quoted}), or {@code -1}.
     */
    static int locateValue(CharSequence text, String name, boolean quoted) {
        var location = locate(text, name, quoted);
        return location == null ? -1 : location[1];
    }

    /**
     * {@code {offset of the key's closing quote, offset of the value start}}
     * for top-level property {@code name}, or {@code null}. Tracks string
     * literals and nesting depth so a same-named nested property or a string
     * containing the name cannot match.
     */
    private static int[] locate(CharSequence text, String name, boolean quoted) {
        var depth = 0;
        var n = text.length();
        var i = 0;
        while (i < n) {
            var c = text.charAt(i);
            if (c == '"') {
                var end = endOfString(text, i);
                if (end < 0) {
                    return null;
                }
                if (depth == 1) {
                    var colon = skipWhitespace(text, end + 1);
                    if (colon < n && text.charAt(colon) == ':'
                            && name.contentEquals(text.subSequence(i + 1, end))) {
                        var v = skipWhitespace(text, colon + 1);
                        if (v >= n) {
                            return null;
                        }
                        // Type check both ways: an enum value is a JSON
                        // string, a boolean value is a bare literal.
                        var isString = text.charAt(v) == '"';
                        if (quoted) {
                            return isString ? new int[] {end, v + 1} : null;
                        }
                        return isString ? null : new int[] {end, v};
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
        return null;
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
