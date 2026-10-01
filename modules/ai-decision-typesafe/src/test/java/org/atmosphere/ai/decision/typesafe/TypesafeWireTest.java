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
package org.atmosphere.ai.decision.typesafe;

import org.atmosphere.ai.decision.Answer;
import org.atmosphere.ai.decision.DecisionRequest;
import org.atmosphere.ai.decision.Question;
import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Strict decoding: what is UNPARSEABLE, what is INVALID_ANSWER, and the header parsing. */
class TypesafeWireTest {

    private static final Question.Choice CHOICE;
    private static final Question.Score SCORE = new Question.Score("How bad?", List.of("low", "mid", "high"));
    private static final Question.Noul NOUL = new Question.Noul("Is it?", null, null);

    static {
        var options = new LinkedHashMap<String, String>();
        options.put("a", "first");
        options.put("b", "");
        CHOICE = new Question.Choice("Which?", options);
    }

    private static Answer decode(Question question, String json) {
        return TypesafeWire.decodeAnswer("q", question, TypesafeWire.MAPPER.readTree(json));
    }

    private static Answer.Failed.Reason reason(Question question, String json) {
        return assertInstanceOf(Answer.Failed.class, decode(question, json)).reason();
    }

    @Test
    void noulBoundariesAndConfidence() {
        var half = assertInstanceOf(Answer.Noul.class, decode(NOUL, "{\"type\":\"noul\",\"noul\":0.5}"));
        assertTrue(half.value());
        assertEquals(0.0, half.confidence().aggregate().getAsDouble(), 1e-12);
        var low = assertInstanceOf(Answer.Noul.class, decode(NOUL, "{\"noul\":0}"));
        assertFalse(low.value());
        assertEquals(1.0, low.confidence().aggregate().getAsDouble(), 1e-12);
        assertEquals(Answer.Failed.Reason.INVALID_ANSWER, reason(NOUL, "{\"noul\":1.2}"));
        assertEquals(Answer.Failed.Reason.INVALID_ANSWER, reason(NOUL, "{\"noul\":-0.1}"));
        assertEquals(Answer.Failed.Reason.UNPARSEABLE, reason(NOUL, "{\"noul\":\"0.9\"}"));
        assertEquals(Answer.Failed.Reason.UNPARSEABLE, reason(NOUL, "{\"value\":0.9}"));
        assertEquals(Answer.Failed.Reason.UNPARSEABLE, reason(NOUL, "[0.9]"));
    }

    @Test
    void anAnswerOfAnotherTypeIsInvalid() {
        assertEquals(Answer.Failed.Reason.INVALID_ANSWER, reason(NOUL,
                "{\"type\":\"choice\",\"noul\":0.9}"));
    }

    @Test
    void choiceMustBeAnOptionTheMostLikelyAndCarryAFullDistribution() {
        var ok = assertInstanceOf(Answer.Choice.class, decode(CHOICE,
                "{\"choice\":\"b\",\"probabilities\":{\"a\":0.3,\"b\":0.7},\"confidence\":0.4}"));
        assertEquals("b", ok.choice());
        assertEquals(Map.of("a", 0.3, "b", 0.7), ok.probabilities());
        assertEquals(Answer.Failed.Reason.INVALID_ANSWER, reason(CHOICE,
                "{\"choice\":\"c\",\"probabilities\":{\"a\":0.3,\"b\":0.7},\"confidence\":0.4}"));
        // The documented "choice" is the highest-probability option; one that is not
        // contradicts its own distribution and is never acted on.
        assertEquals(Answer.Failed.Reason.INVALID_ANSWER, reason(CHOICE,
                "{\"choice\":\"a\",\"probabilities\":{\"a\":0.3,\"b\":0.7},\"confidence\":0.4}"));
        assertEquals(Answer.Failed.Reason.INVALID_ANSWER, reason(CHOICE,
                "{\"choice\":\"b\",\"probabilities\":{\"b\":1.0},\"confidence\":1.0}"));
        assertEquals(Answer.Failed.Reason.INVALID_ANSWER, reason(CHOICE,
                "{\"choice\":\"b\",\"probabilities\":{\"a\":0.0,\"b\":1.0,\"z\":0.0},\"confidence\":1.0}"));
        assertEquals(Answer.Failed.Reason.INVALID_ANSWER, reason(CHOICE,
                "{\"choice\":\"b\",\"probabilities\":{\"a\":0.3,\"b\":0.9},\"confidence\":0.4}"));
        assertEquals(Answer.Failed.Reason.INVALID_ANSWER, reason(CHOICE,
                "{\"choice\":\"b\",\"probabilities\":{\"a\":0.3,\"b\":0.7},\"confidence\":1.4}"));
        assertEquals(Answer.Failed.Reason.UNPARSEABLE, reason(CHOICE,
                "{\"choice\":\"b\",\"probabilities\":{\"a\":0.3,\"b\":0.7}}"));
    }

    @Test
    void scoreMustAgreeWithItsDistribution() {
        var ok = assertInstanceOf(Answer.Score.class, decode(SCORE,
                "{\"score\":1.2,\"probabilities\":{\"0\":0.1,\"1\":0.6,\"2\":0.3},\"confidence\":0.5}"));
        assertEquals(1.2, ok.score(), 1e-12);
        assertEquals(Answer.Failed.Reason.INVALID_ANSWER, reason(SCORE,
                "{\"score\":2.0,\"probabilities\":{\"0\":0.1,\"1\":0.6,\"2\":0.3},\"confidence\":0.5}"));
        assertEquals(Answer.Failed.Reason.INVALID_ANSWER, reason(SCORE,
                "{\"score\":3.5,\"probabilities\":{\"0\":0.0,\"1\":0.0,\"2\":1.0},\"confidence\":1.0}"));
        assertEquals(Answer.Failed.Reason.INVALID_ANSWER, reason(SCORE,
                "{\"score\":0.5,\"probabilities\":{\"0\":0.5,\"1\":0.5},\"confidence\":0.0}"));
        assertEquals(Answer.Failed.Reason.UNPARSEABLE, reason(SCORE,
                "{\"score\":1,\"confidence\":0.5}"));
    }

    @Test
    void aBodyWithoutAnswersFailsEveryQuestion() {
        var request = DecisionRequest.of("s", "q", NOUL);
        var reply = TypesafeWire.decodeReply(request, "{\"model\":\"jev-1.13.0\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(Answer.Failed.Reason.UNPARSEABLE,
                assertInstanceOf(Answer.Failed.class, reply.answers().get("q")).reason());
    }

    @Test
    void choiceOptionWithoutDescriptionIsSentAsNull() {
        var body = new String(TypesafeWire.encodeRequest("jev-1.13.0", DecisionRequest.of("s", "q", CHOICE)),
                StandardCharsets.UTF_8);
        var criteria = TypesafeWire.MAPPER.readTree(body).get("questions").get("q").get("criteria");
        assertEquals("first", criteria.get("a").stringValue());
        assertTrue(criteria.get("b").isNull());
    }

    @Test
    void noulWithOneCriterionSendsOnlyThatOne() {
        var body = new String(TypesafeWire.encodeRequest("jev-1.13.0",
                DecisionRequest.of("s", "q", new Question.Noul("Is it?", "yes means this", null))),
                StandardCharsets.UTF_8);
        var question = TypesafeWire.MAPPER.readTree(body).get("questions").get("q");
        assertEquals("yes means this", question.get("criteria").get("true").stringValue());
        assertFalse(question.get("criteria").has("false"));
        var bare = new String(TypesafeWire.encodeRequest("jev-1.13.0", DecisionRequest.of("s", "q", NOUL)),
                StandardCharsets.UTF_8);
        assertFalse(TypesafeWire.MAPPER.readTree(bare).get("questions").get("q").has("criteria"));
    }

    @Test
    void retryAfterHeaders() {
        assertEquals(250, TypesafeDecisionModel.retryAfterMillis(headers(Map.of("retry-after-ms", "250")))
                .getAsLong());
        assertEquals(2_000, TypesafeDecisionModel.retryAfterMillis(headers(Map.of("Retry-After", "2")))
                .getAsLong());
        // retry-after-ms wins over Retry-After, as in the SDKs.
        assertEquals(10, TypesafeDecisionModel.retryAfterMillis(
                headers(Map.of("retry-after-ms", "10", "Retry-After", "9"))).getAsLong());
        var date = ZonedDateTime.now().plusSeconds(30).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        var fromDate = TypesafeDecisionModel.retryAfterMillis(headers(Map.of("Retry-After", date))).getAsLong();
        assertTrue(fromDate > 25_000 && fromDate <= 30_000, "got " + fromDate);
        assertTrue(TypesafeDecisionModel.retryAfterMillis(headers(Map.of("Retry-After", "soon"))).isEmpty());
        assertTrue(TypesafeDecisionModel.retryAfterMillis(headers(Map.of())).isEmpty());
    }

    @Test
    void retryableStatusesAndBackoffBounds() {
        assertTrue(TypesafeDecisionModel.retryable(408));
        assertTrue(TypesafeDecisionModel.retryable(429));
        assertTrue(TypesafeDecisionModel.retryable(529));
        assertTrue(TypesafeDecisionModel.retryable(500));
        assertFalse(TypesafeDecisionModel.retryable(401));
        assertFalse(TypesafeDecisionModel.retryable(422));
        for (var attempt = 0; attempt < 30; attempt++) {
            var wait = TypesafeDecisionModel.backoffMillis(attempt);
            assertTrue(wait > 0 && wait <= TypesafeDecisionModel.MAX_BACKOFF_MILLIS, "attempt " + attempt);
        }
    }

    @Test
    void errorMessageReadsTheObservedEnvelope() {
        assertEquals("authentication_error: Cannot authenticate with the server. Please check your API key "
                        + "and try again.",
                TypesafeWire.errorMessage(TypesafeStub.fixture("error-401.json").getBytes(StandardCharsets.UTF_8)));
        assertEquals("Field required",
                TypesafeWire.errorMessage(TypesafeStub.fixture("error-422.json").getBytes(StandardCharsets.UTF_8)));
        assertEquals("plain text", TypesafeWire.errorMessage("plain\ntext".getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpHeaders headers(Map<String, String> values) {
        var map = new LinkedHashMap<String, List<String>>();
        values.forEach((k, v) -> map.put(k, List.of(v)));
        return HttpHeaders.of(map, (k, v) -> true);
    }
}
