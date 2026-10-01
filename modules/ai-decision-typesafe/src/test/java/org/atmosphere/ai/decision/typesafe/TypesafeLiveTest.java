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

import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.decision.Answer;
import org.atmosphere.ai.decision.DecisionRequest;
import org.atmosphere.ai.decision.Question;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Calls the real {@code https://api.typesafe.ai}. Every test here is skipped
 * unless it is asked for:
 * <ul>
 *   <li>{@link #answersEveryQuestionTypeWithTheRealKey()} needs
 *       {@code TYPESAFE_API_KEY} (the {@code typesafe-decision-live.yml} lane
 *       passes the repository secret of that name);</li>
 *   <li>{@link #theRealApiRejectsAnInvalidKey()} needs no key, only network; it
 *       runs with the key (in that lane) or with {@code -Dtypesafe.live=true}.</li>
 * </ul>
 * The lane asserts the suite ran with zero skips, so a missing secret cannot
 * pass as green.
 */
class TypesafeLiveTest {

    private static String key() {
        var key = System.getenv(TypesafeDecisionModel.API_KEY_ENV);
        return key == null || key.isBlank() ? null : key;
    }

    @Test
    void answersEveryQuestionTypeWithTheRealKey() {
        assumeTrue(key() != null, "TYPESAFE_API_KEY is not set");
        try (var model = TypesafeDecisionModel.builder().apiKey(key()).build()) {
            assertTrue(model.isAvailable(), "GET /v1/models with the key");

            var options = new LinkedHashMap<String, String>();
            options.put("billing", "Payments, invoicing, refunds");
            options.put("technical", "Bugs, outages, integrations");
            options.put("sales", "Pricing, upgrades, new accounts");
            var questions = new LinkedHashMap<String, Question>();
            questions.put("department", new Question.Choice("Which team should handle this?", options));
            questions.put("frustration", new Question.Score("How frustrated is the customer?",
                    List.of("Calm", "Frustrated", "Very angry")));
            questions.put("english", new Question.Noul("Is the message written in English?", null, null));
            var result = model.decide(new DecisionRequest("Help! My payouts have been failing for 3 days.",
                    questions, Duration.ofSeconds(30)));

            assertTrue(result.model().startsWith("jev-"), "answered by " + result.model());
            var choice = assertInstanceOf(Answer.Choice.class, result.answers().get("department"),
                    String.valueOf(result.answers().get("department")));
            assertEquals(options.keySet(), choice.probabilities().keySet());
            assertEquals(AiConfidence.Source.PROVIDER_DISTRIBUTION, choice.confidence().source());
            var score = assertInstanceOf(Answer.Score.class, result.answers().get("frustration"),
                    String.valueOf(result.answers().get("frustration")));
            assertEquals(3, score.probabilities().size());
            var noul = assertInstanceOf(Answer.Noul.class, result.answers().get("english"),
                    String.valueOf(result.answers().get("english")));
            assertTrue(noul.probabilityTrue().isPresent());
            assertTrue(result.usage().isPresent(), "usage is documented as required");
        }
    }

    @Test
    void theRealApiRejectsAnInvalidKey() {
        assumeTrue(key() != null || Boolean.getBoolean("typesafe.live"),
                "set TYPESAFE_API_KEY or -Dtypesafe.live=true to call the real API");
        try (var model = TypesafeDecisionModel.builder().apiKey("ts-invalid-key-for-atmosphere-tests").build()) {
            assertFalse(model.isAvailable(), "GET /v1/models rejects the key");
            var answer = model.decide(DecisionRequest.of("hello", "q",
                    new Question.Noul("Is this a greeting?", null, null)).withTimeout(Duration.ofSeconds(15)))
                    .answers().get("q");
            var failed = assertInstanceOf(Answer.Failed.class, answer);
            assertEquals(Answer.Failed.Reason.ERROR, failed.reason());
            assertTrue(failed.detail().startsWith("HTTP 401 authentication_error"), failed.detail());
            assertTrue(failed.detail().contains("(request req_"), failed.detail());
        }
    }
}
