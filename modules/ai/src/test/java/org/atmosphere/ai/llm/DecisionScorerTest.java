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
import org.atmosphere.ai.ConfidenceRoute;
import org.atmosphere.ai.ConfidenceRouting;
import org.atmosphere.ai.TokenLogprob;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DecisionScorer}, {@link DecisionField#fromSchema} and
 * {@link LogprobCapture}: value location, prefix handling, bounds, and every
 * decline path.
 */
class DecisionScorerTest {

    private static final double EPS = 1e-9;
    private static final DecisionField VERDICT =
            new DecisionField("verdict", List.of("APPROVE", "REJECT", "DEFER"), true);
    private static final DecisionField APPROVED =
            new DecisionField("approved", List.of("true", "false"), false);

    // ------------------------------------------------------------- locateValue

    @Test
    void locateValueSkipsNestedAndQuotedOccurrences() {
        var text = "{\"note\":\"verdict\",\"inner\":{\"verdict\":\"REJECT\"},\"verdict\":\"APPROVE\"}";
        var offset = DecisionScorer.locateValue(text, "verdict", true);
        assertTrue(offset > 0);
        assertTrue(text.startsWith("APPROVE", offset),
                "only the top-level property counts, not a nested one or a string value: " + offset);
    }

    @Test
    void locateValueToleratesLeadingProseAndWhitespace() {
        var text = "Here you go:\n```json\n{ \"approved\" :  true }\n```";
        var offset = DecisionScorer.locateValue(text, "approved", false);
        assertTrue(text.startsWith("true", offset));
    }

    @Test
    void locateValueRejectsTypeMismatchAndAbsence() {
        assertEquals(-1, DecisionScorer.locateValue("{\"verdict\":42}", "verdict", true),
                "an enum field must be a JSON string");
        assertEquals(-1, DecisionScorer.locateValue("{\"other\":\"APPROVE\"}", "verdict", true));
        assertEquals(-1, DecisionScorer.locateValue("{\"verdict\":\"APPROVE\"}", "verdict", false),
                "a boolean field must be a bare literal, not a string");
        assertEquals(-1, DecisionScorer.locateValue("{\"note\":\"unterminated", "verdict", true),
                "an unterminated string ends the scan");
    }

    // ----------------------------------------------------------------- scoring

    @Test
    void valueStartingMidTokenUsesThePrefix() {
        // The provider merged the key's closing quote, the separator and the
        // first letters: '":"AP'. The '":' rival stops before the value, so
        // it is ambiguous and lands on the least likely value.
        var entries = List.of(
                entry("{\"verdict", 0.0, alt("{\"verdict", 0.0)),
                entry("\":\"AP", -0.2, alt("\":\"AP", -0.2), alt("\":\"RE", -2.0), alt("\":", -3.5)),
                entry("PROVE", 0.0, alt("PROVE", 0.0)),
                entry("\"}", 0.0));
        var confidence = DecisionScorer.score(entries, VERDICT).orElseThrow();
        var decision = confidence.decision().orElseThrow();
        var approve = Math.exp(-0.2);
        var reject = Math.exp(-2.0);
        assertEquals(approve, decision.probabilities().get("APPROVE"), EPS);
        assertEquals(reject, decision.probabilities().get("REJECT"), EPS);
        assertEquals(1 - approve - reject, decision.probabilities().get("DEFER"), EPS,
                "the ambiguous and unobserved mass goes where it lowers confidence");
        assertEquals(approve + reject + Math.exp(-3.5), decision.observedMass(), EPS);
        assertEquals((3 * approve - 1) / 2, confidence.aggregate().getAsDouble(), EPS);
    }

    /**
     * The value is chosen at the token BEFORE its first character: the model
     * sampled {@code " \""} (p=0.5) while the rival {@code " \"REJECT"} (0.5)
     * carries the quote and the value in one token. Reading alternatives only
     * from the value's first character on scored this coin flip 1.0 (ACT).
     */
    @Test
    void rivalCarriedByTheOpeningQuoteTokenIsScored() {
        var entries = List.of(
                entry("{\"verdict\":", 0.0, alt("{\"verdict\":", 0.0)),
                entry(" \"", Math.log(0.5), alt(" \"", Math.log(0.5)), alt(" \"REJECT", Math.log(0.5))),
                entry("APPROVE", 0.0, alt("APPROVE", 0.0)),
                entry("\"}", 0.0));
        var confidence = DecisionScorer.score(entries, VERDICT).orElseThrow();
        var decision = confidence.decision().orElseThrow();
        assertEquals(0.5, decision.probabilities().get("APPROVE"), EPS);
        assertEquals(0.5, decision.probabilities().get("REJECT"), EPS);
        assertEquals(0.25, confidence.aggregate().getAsDouble(), EPS);
        assertEquals(ConfidenceRoute.ESCALATE, ConfidenceRouting.defaults().route(confidence));
    }

    /**
     * The rival spells the separator differently from the sampled token
     * ({@code " \"REJECT"} against a sampled {@code "\"APPROVE"}). It is the
     * same decision, so it must count for REJECT rather than vanish.
     */
    @Test
    void rivalWithADifferentSeparatorSpellingIsScored() {
        var entries = List.of(
                entry("{\"verdict\":", 0.0, alt("{\"verdict\":", 0.0)),
                entry("\"APPROVE", Math.log(0.5), alt("\"APPROVE", Math.log(0.5)), alt(" \"REJECT", Math.log(0.5))),
                entry("\"}", 0.0));
        var confidence = DecisionScorer.score(entries, VERDICT).orElseThrow();
        var decision = confidence.decision().orElseThrow();
        assertEquals(0.5, decision.probabilities().get("REJECT"), EPS);
        assertEquals(1.0, decision.observedMass(), EPS);
        assertEquals(0.25, confidence.aggregate().getAsDouble(), EPS);
        assertEquals(ConfidenceRoute.ESCALATE, ConfidenceRouting.defaults().route(confidence));
    }

    /**
     * Probability the provider did not list is not evidence for the sampled
     * value: APPROVE at p=0.61 with no listed rival must not score 1.0.
     */
    @Test
    void unobservedMassLowersConfidenceInsteadOfVanishing() {
        var entries = List.of(
                entry("{\"verdict\":\"", 0.0, alt("{\"verdict\":\"", 0.0)),
                entry("APPROVE", -0.5, alt("APPROVE", -0.5)),
                entry("\"}", 0.0));
        var confidence = DecisionScorer.score(entries, VERDICT).orElseThrow();
        var decision = confidence.decision().orElseThrow();
        var approve = Math.exp(-0.5);
        assertEquals(approve, decision.probabilities().get("APPROVE"), EPS);
        assertEquals((1 - approve) / 2, decision.probabilities().get("REJECT"), EPS);
        assertEquals((1 - approve) / 2, decision.probabilities().get("DEFER"), EPS);
        assertEquals(approve, decision.observedMass(), EPS);
        assertEquals((3 * approve - 1) / 2, confidence.aggregate().getAsDouble(), EPS);
        assertEquals(ConfidenceRoute.ESCALATE, ConfidenceRouting.defaults().route(confidence));
    }

    @Test
    void separatorTokenWithoutTopLogprobsCountsAsUnobserved() {
        var entries = List.of(
                entry("{\"verdict\":", 0.0, alt("{\"verdict\":", 0.0)),
                entry(" \"", Math.log(0.5)),
                entry("APPROVE", 0.0, alt("APPROVE", 0.0)),
                entry("\"}", 0.0));
        var confidence = DecisionScorer.score(entries, VERDICT).orElseThrow();
        assertEquals(0.5, confidence.decision().orElseThrow().probabilities().get("APPROVE"), EPS);
        assertEquals(0.25, confidence.aggregate().getAsDouble(), EPS);
    }

    @Test
    void ambiguousPrefixMassIsSpreadOverTheValuesItCouldBe() {
        var field = new DecisionField("action", List.of("CONFIRM", "CANCEL", "ESCALATE"), true);
        // Sampled "ESC" (ESCALATE only); the non-sampled "C" could be CONFIRM
        // or CANCEL, and its continuation was never observed.
        var entries = List.of(
                entry("{\"action\":\"", 0.0, alt("{\"action\":\"", 0.0)),
                entry("ESC", -0.4, alt("ESC", -0.4), alt("C", -1.2)),
                entry("ALATE\"}", 0.0));
        var decision = DecisionScorer.score(entries, field).orElseThrow().decision().orElseThrow();
        var esc = Math.exp(-0.4);
        assertEquals(esc, decision.probabilities().get("ESCALATE"), EPS);
        assertEquals((1 - esc) / 2, decision.probabilities().get("CONFIRM"), EPS);
        assertEquals((1 - esc) / 2, decision.probabilities().get("CANCEL"), EPS);
    }

    /**
     * Splitting ambiguous mass evenly is a guess, not a bound: here it would
     * give AB1 the largest share (0.5, score 0.25) when the unobserved "B"
     * continuation may equally be AB2, a world where the values sit at
     * 0.35 / 0.30 / 0.35 and the score is 0.025. The scorer reports the
     * least concentrated distribution consistent with the evidence.
     */
    @Test
    void ambiguousMassIsAssignedToTheLeastConcentratedDistribution() {
        var field = new DecisionField("code", List.of("AB1", "AB2", "AC"), true);
        var entries = List.of(
                entry("{\"code\":\"", 0.0, alt("{\"code\":\"", 0.0)),
                entry("A", Math.log(0.6), alt("A", Math.log(0.6)), alt("AB1\"", Math.log(0.35)),
                        alt("AC", Math.log(0.05))),
                entry("C", Math.log(0.5), alt("C", Math.log(0.5)), alt("B", Math.log(0.5))),
                entry("\"}", 0.0));
        var confidence = DecisionScorer.score(entries, field).orElseThrow();
        var decision = confidence.decision().orElseThrow();
        assertEquals(0.35, decision.probabilities().get("AB1"), EPS);
        assertEquals(0.30, decision.probabilities().get("AB2"), EPS);
        assertEquals(0.35, decision.probabilities().get("AC"), EPS);
        assertEquals(0.025, confidence.aggregate().getAsDouble(), EPS);
    }

    @Test
    void leastConcentratedReachesTheMinMax() {
        // firm [0.5, 0, 0]; {0,1}: 0.3, {1,2}: 0.2. The largest mass cannot go
        // below 0.5, and no set's mass is piled onto value 0.
        var ambiguous = new LinkedHashMap<Integer, Double>();
        ambiguous.put(0b011, 0.3);
        ambiguous.put(0b110, 0.2);
        var mass = DecisionScorer.leastConcentrated(new double[] {0.5, 0.0, 0.0}, ambiguous);
        assertEquals(0.5, mass[0], EPS);
        assertEquals(0.3, mass[1], EPS);
        assertEquals(0.2, mass[2], EPS);
    }

    @Test
    void sampledTokenAbsentFromTopListStillCounts() {
        var entries = List.of(
                entry("{\"verdict\":\"", 0.0, alt("{\"verdict\":\"", 0.0)),
                entry("DEFER", -3.0, alt("APPROVE", -0.4), alt("REJECT", -1.6)),
                entry("\"}", 0.0));
        var decision = DecisionScorer.score(entries, VERDICT).orElseThrow().decision().orElseThrow();
        assertEquals(Math.exp(-0.4), decision.probabilities().get("APPROVE"), EPS);
        assertEquals(Math.exp(-1.6), decision.probabilities().get("REJECT"), EPS);
        assertEquals(1 - Math.exp(-0.4) - Math.exp(-1.6), decision.probabilities().get("DEFER"), EPS);
        assertEquals("APPROVE", decision.mostLikely(),
                "the most likely value need not be the sampled one");
    }

    @Test
    void booleanLiteralFollowedByDelimiterInOneToken() {
        var entries = List.of(
                entry("{\"approved\":", 0.0, alt("{\"approved\":", 0.0)),
                entry("false}", -0.1, alt("false}", -0.1), alt("true}", -2.5), alt("trueish", -6.0)));
        var decision = DecisionScorer.score(entries, APPROVED).orElseThrow().decision().orElseThrow();
        var no = Math.exp(-0.1);
        assertEquals(no, decision.probabilities().get("false"), EPS);
        assertEquals(no + Math.exp(-2.5), decision.observedMass(), EPS,
                "'trueish' is not the literal true and must not count as observed");
    }

    @Test
    void singleAllowedValueIsAForcedChoice() {
        var field = new DecisionField("verdict", List.of("APPROVE"), true);
        var entries = List.of(
                entry("{\"verdict\":\"", -0.001),
                entry("APPROVE", -0.9, alt("APPROVE", -0.9)),
                entry("\"}", -0.001));
        var confidence = DecisionScorer.score(entries, field).orElseThrow();
        assertEquals(1.0, confidence.aggregate().getAsDouble(), EPS);
    }

    @Test
    void declinesWhenTheValueCannotBeScored() {
        assertTrue(DecisionScorer.score(List.of(
                entry("{\"reason\":\"x\"}", -0.1, alt("{\"reason\":\"x\"}", -0.1))), VERDICT).isEmpty(),
                "field absent");
        assertTrue(DecisionScorer.score(List.of(
                entry("{\"verdict\":\"", -0.001),
                entry("MAYBE", -0.1, alt("MAYBE", -0.1), alt("APPROVE", -3.0)),
                entry("\"}", -0.001)), VERDICT).isEmpty(),
                "a sampled value outside the schema is not scored");
        assertTrue(DecisionScorer.score(List.of(
                entry("{\"verdict\":\"", -0.001),
                entry("APPROVE", -0.1),
                entry("\"}", -0.001)), VERDICT).isEmpty(),
                "a value token without top_logprobs is not scored");
        assertTrue(DecisionScorer.score(List.of(
                entry("{\"verdict\":\"", -0.001),
                entry("APP", -0.1, alt("APP", -0.1))), VERDICT).isEmpty(),
                "a truncated value is not scored");
    }

    // --------------------------------------------------------------- capture

    @Test
    void captureWithoutDecisionKeepsTheHistoricalMean() throws Exception {
        var capture = new LogprobCapture(null);
        capture.capture(new ObjectMapper().readTree(
                "{\"content\":[{\"token\":\"a\",\"logprob\":-0.1,\"top_logprobs\":[{\"token\":\"b\",\"logprob\":-2}]},"
                        + "{\"token\":\"c\",\"logprob\":-0.3}]}"));
        var confidence = capture.toConfidence();
        assertEquals(AiConfidence.Source.LOGPROBS_NATIVE, confidence.source());
        assertEquals((Math.exp(-0.1) + Math.exp(-0.3)) / 2, confidence.aggregate().getAsDouble(), EPS);
    }

    @Test
    void captureIsBoundedInTokens() throws Exception {
        var capture = new LogprobCapture(null);
        var content = new StringBuilder("{\"content\":[");
        for (int i = 0; i < LogprobCapture.MAX_LOGPROB_TOKENS + 5; i++) {
            // The entries past the bound are far less likely; if they were
            // kept they would drag the mean down.
            var logprob = i < LogprobCapture.MAX_LOGPROB_TOKENS ? "-0.1" : "-9";
            content.append(i == 0 ? "" : ",")
                    .append("{\"token\":\"x\",\"logprob\":").append(logprob).append('}');
        }
        content.append("]}");
        capture.capture(new ObjectMapper().readTree(content.toString()));
        var confidence = capture.toConfidence();
        assertEquals(LogprobCapture.MAX_LOGPROB_TOKENS, confidence.tokens().size());
        assertEquals(Math.exp(-0.1), confidence.aggregate().getAsDouble(), EPS);
    }

    @Test
    void captureIsBoundedInAlternatives() throws Exception {
        var capture = new LogprobCapture(VERDICT);
        // The value token lists APPROVE first, then filler, and REJECT only
        // past the provider maximum: a capture that kept it would give
        // REJECT mass.
        var top = new StringBuilder("[{\"token\":\"APPROVE\",\"logprob\":-0.5}");
        for (int i = 1; i < DecisionField.MAX_TOP_LOGPROBS; i++) {
            top.append(",{\"token\":\"f").append(i).append("\",\"logprob\":-9}");
        }
        top.append(",{\"token\":\"REJECT\",\"logprob\":-1}]");
        capture.capture(new ObjectMapper().readTree("{\"content\":["
                + "{\"token\":\"{\\\"verdict\\\":\\\"\",\"logprob\":-0.001,\"top_logprobs\":[]},"
                + "{\"token\":\"APPROVE\",\"logprob\":-0.5,\"top_logprobs\":" + top + "},"
                + "{\"token\":\"\\\"}\",\"logprob\":-0.001,\"top_logprobs\":[]}]}"));
        var decision = capture.toConfidence().decision().orElseThrow();
        assertEquals(Math.exp(-0.001) * Math.exp(-0.5), decision.observedMass(), EPS,
                "only the sampled APPROVE is observed; REJECT past the cap is not");
        assertEquals(decision.probabilities().get("DEFER"), decision.probabilities().get("REJECT"), EPS,
                "REJECT holds only its share of the unobserved mass, like DEFER");
    }

    @Test
    void emptyCaptureIsSilent() {
        assertNull(new LogprobCapture(VERDICT).toConfidence());
        assertNull(new LogprobCapture(null).toConfidence());
    }

    // ------------------------------------------------------------- fromSchema

    @Test
    void fromSchemaResolvesEnumAndBooleanOnly() {
        var schema = "{\"type\":\"object\",\"properties\":{"
                + "\"verdict\":{\"type\":\"string\",\"enum\":[\"APPROVE\",\"REJECT\"]},"
                + "\"approved\":{\"type\":\"boolean\"},"
                + "\"reason\":{\"type\":\"string\"},"
                + "\"score\":{\"type\":\"number\"},"
                + "\"mixed\":{\"enum\":[\"A\",1]},"
                + "\"inner\":{\"type\":\"object\",\"properties\":{\"flag\":{\"type\":\"boolean\"}}}}}";
        var verdict = DecisionField.fromSchema(schema, "verdict").orElseThrow();
        assertEquals(List.of("APPROVE", "REJECT"), verdict.values());
        assertTrue(verdict.quoted());
        assertEquals(5, verdict.topLogprobs());
        var approved = DecisionField.fromSchema(schema, "approved").orElseThrow();
        assertEquals(List.of("true", "false"), approved.values());
        assertFalse(approved.quoted());
        assertTrue(DecisionField.fromSchema(schema, "reason").isEmpty(), "free string");
        assertTrue(DecisionField.fromSchema(schema, "score").isEmpty(), "number");
        assertTrue(DecisionField.fromSchema(schema, "mixed").isEmpty(), "non-string enum");
        assertTrue(DecisionField.fromSchema(schema, "flag").isEmpty(), "nested property");
        assertTrue(DecisionField.fromSchema(schema, "missing").isEmpty());
        assertTrue(DecisionField.fromSchema("{not json", "verdict").isEmpty());
        assertTrue(DecisionField.fromSchema(null, "verdict").isEmpty());
    }

    @Test
    void fromSchemaDeclinesAnOversizedValueSet() {
        var values = new ArrayList<String>();
        for (int i = 0; i <= DecisionField.MAX_VALUES; i++) {
            values.add("\"V" + i + "\"");
        }
        var schema = "{\"properties\":{\"v\":{\"type\":\"string\",\"enum\":[" + String.join(",", values) + "]}}}";
        assertTrue(DecisionField.fromSchema(schema, "v").isEmpty());
    }

    @Test
    void topLogprobsIsCappedAtTheProviderMaximum() {
        var values = new ArrayList<String>();
        for (int i = 0; i < DecisionField.MAX_TOP_LOGPROBS; i++) {
            values.add("V" + i);
        }
        assertEquals(DecisionField.MAX_TOP_LOGPROBS, new DecisionField("v", values, true).topLogprobs());
        assertEquals(DecisionField.MAX_TOP_LOGPROBS,
                new DecisionField("v", values.subList(0, DecisionField.MAX_TOP_LOGPROBS - 3), true).topLogprobs());
    }

    // ---------------------------------------------------------------- helpers

    private static TokenLogprob alt(String token, double logprob) {
        return new TokenLogprob(token, logprob);
    }

    private static LogprobCapture.Entry entry(String token, double logprob, TokenLogprob... top) {
        return new LogprobCapture.Entry(new TokenLogprob(token, logprob), List.of(top));
    }
}
