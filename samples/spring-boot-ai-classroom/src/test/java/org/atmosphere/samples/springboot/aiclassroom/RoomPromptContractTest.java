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
package org.atmosphere.samples.springboot.aiclassroom;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.governance.scope.ScopeGuardrailResolver;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the text of the room definitions that the model reads. The framework
 * quotes each room's purpose, forbidden topics and redirect message into the
 * system prompt, and tells the model to reply with exactly the redirect when a
 * request is out of scope. Measured on qwen2.5:3b and qwen2.5:7b (local Ollama),
 * three wordings made the model send that redirect for in-scope questions:
 * <ul>
 *   <li>a redirect that names the room's own subject ("This is the math room —
 *       ask me about a mathematics topic instead.") reads as the answer to a
 *       math question;</li>
 *   <li>a forbidden topic starting with "writing" matches "Write a lesson on
 *       …";</li>
 *   <li>a purpose that does not name lessons and worked examples leaves
 *       "an 800-word lesson" or "five worked examples" in doubt.</li>
 * </ul>
 * What the model then does is measured, not unit-tested; this test keeps the
 * wording that caused it from coming back, and keeps the rule-based guardrail,
 * which does the real enforcement, rejecting what it rejected before.
 */
class RoomPromptContractTest {

    /** Words that name each room's own subject. */
    private static final Map<String, List<String>> SUBJECT_WORDS = Map.of(
            "math", List.of("math"),
            "code", List.of("code", "programming", "software"),
            "science", List.of("scien"),
            "general", List.of("general", "education"));

    private final RoomScopesConfig.Rooms rooms = new RoomScopesConfig().rooms();

    private static PolicyContext preAdmission(String message) {
        return PolicyContext.preAdmission(
                new AiRequest(message, null, null, "student-42", null, null, null, null, null));
    }

    private PolicyDecision guardrail(String room, String message) {
        var scope = rooms.byKey().get(room).scope();
        var policy = new ScopePolicy(room + "-room", "yaml:classroom-test", "1",
                scope, ScopeGuardrailResolver.resolve(scope.tier()));
        return policy.evaluate(preAdmission(message));
    }

    @Test
    void noRedirectNamesItsOwnRoomsSubject() {
        for (var entry : SUBJECT_WORDS.entrySet()) {
            var redirect = rooms.byKey().get(entry.getKey()).scope()
                    .redirectMessage().toLowerCase(Locale.ROOT);
            for (var word : entry.getValue()) {
                assertFalse(redirect.contains(word), "room '" + entry.getKey()
                        + "' redirect names its subject ('" + word + "'): " + redirect);
            }
        }
    }

    @Test
    void noForbiddenTopicStartsWithWriting() {
        for (var entry : rooms.byKey().entrySet()) {
            for (var topic : entry.getValue().scope().forbiddenTopics()) {
                assertFalse(topic.toLowerCase(Locale.ROOT).startsWith("writing"),
                        "room '" + entry.getKey() + "' forbids '" + topic
                                + "', which matches requests to write a lesson");
            }
        }
    }

    @Test
    void everyPurposeNamesLessonsAndWorkedExamples() {
        for (var entry : rooms.byKey().entrySet()) {
            var purpose = entry.getValue().scope().purpose().toLowerCase(Locale.ROOT);
            assertTrue(purpose.contains("lessons") && purpose.contains("worked examples"),
                    "room '" + entry.getKey() + "' purpose: " + purpose);
        }
    }

    @Test
    void guardrailStillRejectsWhatItRejectedAndAdmitsLongLessons() {
        assertInstanceOf(PolicyDecision.Admit.class,
                guardrail("math", "Write a detailed 800-word lesson on prime numbers for high-school students."));
        assertInstanceOf(PolicyDecision.Admit.class,
                guardrail("science", "Write a detailed 800-word lesson on photosynthesis."));
        for (var probe : List.of(
                Map.entry("math", "Write a Python function that sorts a list of numbers."),
                Map.entry("science", "Write a Python function that sorts a list of numbers."),
                Map.entry("code", "Please give me medical advice for chest pain"),
                Map.entry("general", "Which stocks should I buy? I want financial advice."))) {
            var decision = guardrail(probe.getKey(), probe.getValue());
            assertTrue(decision instanceof PolicyDecision.Transform
                            || decision instanceof PolicyDecision.Deny,
                    probe.getKey() + " room must still reject '" + probe.getValue() + "', got: " + decision);
        }
    }
}
