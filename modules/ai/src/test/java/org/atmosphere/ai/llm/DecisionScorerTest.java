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
import org.atmosphere.ai.TokenLogprob;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
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
        // The provider merged the separator and the first letters: '":"AP'.
        var entries = List.of(
                entry("{\"verdict", -0.001),
                entry("\":\"AP", -0.2, alt("\":\"AP", -0.2), alt("\":\"RE", -1.8), alt("\":", -3.0)),
                entry("PROVE", -0.001, alt("PROVE", -0.001)),
                entry("\"}", -0.001));
        var confidence = DecisionScorer.score(entries, VERDICT).orElseThrow();
        var decision = confidence.decision().orElseThrow();
        var approve = Math.exp(-0.2);
        var reject = Math.exp(-1.8);
        assertEquals(approve / (approve + reject), decision.probabilities().get("APPROVE"), EPS);
        assertEquals(reject / (approve + reject), decision.probabilities().get("REJECT"), EPS);
    }

    @Test
    void ambiguousNonSampledPrefixSplitsEvenly() {
        var field = new DecisionField("action", List.of("CONFIRM", "CANCEL", "ESCALATE"), true);
        // Sampled "ESC" (ESCALATE only); the non-sampled "C" could be CONFIRM
        // or CANCEL, and its continuation was never observed.
        var entries = List.of(
                entry("{\"action\":\"", -0.001),
                entry("ESC", -0.4, alt("ESC", -0.4), alt("C", -1.2)),
                entry("ALATE\"}", -0.001));
        var decision = DecisionScorer.score(entries, field).orElseThrow().decision().orElseThrow();
        var esc = Math.exp(-0.4);
        var c = Math.exp(-1.2);
        var total = esc + c;
        assertEquals(esc / total, decision.probabilities().get("ESCALATE"), EPS);
        assertEquals(c / 2 / total, decision.probabilities().get("CONFIRM"), EPS);
        assertEquals(c / 2 / total, decision.probabilities().get("CANCEL"), EPS);
    }

    @Test
    void sampledTokenAbsentFromTopListStillCounts() {
        var entries = List.of(
                entry("{\"verdict\":\"", -0.001),
                entry("DEFER", -3.0, alt("APPROVE", -0.3), alt("REJECT", -1.5)),
                entry("\"}", -0.001));
        var decision = DecisionScorer.score(entries, VERDICT).orElseThrow().decision().orElseThrow();
        var total = Math.exp(-3.0) + Math.exp(-0.3) + Math.exp(-1.5);
        assertEquals(Math.exp(-3.0) / total, decision.probabilities().get("DEFER"), EPS);
        assertEquals("APPROVE", decision.mostLikely(),
                "the most likely value need not be the sampled one");
    }

    @Test
    void booleanLiteralFollowedByDelimiterInOneToken() {
        var entries = List.of(
                entry("{\"approved\":", -0.001),
                entry("false}", -0.1, alt("false}", -0.1), alt("true}", -2.5), alt("trueish", -6.0)));
        var decision = DecisionScorer.score(entries, APPROVED).orElseThrow().decision().orElseThrow();
        var no = Math.exp(-0.1);
        var yes = Math.exp(-2.5);
        assertEquals(no / (no + yes), decision.probabilities().get("false"), EPS,
                "'trueish' is not the literal true and must not count");
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
        assertEquals(1.0, decision.probabilities().get("APPROVE"), EPS);
        assertEquals(0.0, decision.probabilities().get("REJECT"), EPS);
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
