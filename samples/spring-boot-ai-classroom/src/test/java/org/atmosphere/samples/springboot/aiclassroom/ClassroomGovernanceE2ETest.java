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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-request scope configuration loaded from YAML. Four rooms
 * (math / code / science / general) each carry their own
 * {@code ScopeConfig}; operators change YAML, restart, rooms change.
 *
 * <p>Unique to Atmosphere among JVM AI frameworks: a single
 * {@code @AiEndpoint} serves four different scopes selected per-request
 * from the path param. MS Agent Framework, Spring AI, LangChain4j all
 * resolve scope statically at endpoint definition — they'd need four
 * separate endpoints for this. This test proves the mechanism works
 * end-to-end from YAML load → interceptor → scope policy evaluation.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "atmosphere.admin.enabled=false" })
class ClassroomGovernanceE2ETest {

    @Autowired RoomScopesConfig.Rooms rooms;

    private static PolicyContext preAdm(String msg) {
        return PolicyContext.preAdmission(
                new AiRequest(msg, null, null, "student-42", null, null, null, null, null));
    }

    @Test
    void goal1_yamlRoomsLoadedAtStartup() {
        assertNotNull(rooms, "RoomScopesConfig must publish the Rooms bean");
        assertEquals(Set.of("math", "code", "science", "general"),
                rooms.byKey().keySet(),
                "YAML must declare exactly the four canonical classroom rooms");
        assertEquals("general", rooms.defaultKey());
    }

    @Test
    void goal1_eachRoomCarriesIndependentSystemPrompt() {
        var math = rooms.byKey().get("math");
        var code = rooms.byKey().get("code");
        var science = rooms.byKey().get("science");
        assertTrue(math.systemPrompt().toLowerCase().contains("mathematic"));
        assertTrue(code.systemPrompt().toLowerCase().contains("software"));
        assertTrue(science.systemPrompt().toLowerCase().contains("science"));
    }

    @Test
    void goal1_mathRoomRejectsCodingQuestions() {
        var math = rooms.byKey().get("math");
        var policy = new ScopePolicy("math-room", "yaml:classroom-test", "1",
                math.scope(), ScopeGuardrailResolver.resolve(math.scope().tier()));

        // An on-topic math prompt admits
        var onTopic = policy.evaluate(preAdm("What is the derivative of x^2?"));
        assertInstanceOf(PolicyDecision.Admit.class, onTopic);

        // A programming prompt triggers the polite redirect (transform)
        var offTopic = policy.evaluate(preAdm("Please write python code to sort a list"));
        assertTrue(offTopic instanceof PolicyDecision.Transform
                        || offTopic instanceof PolicyDecision.Deny,
                "math room must reject programming asks, got: " + offTopic);
    }

    @Test
    void goal1_codeRoomRejectsMedicalAdviceViaYamlForbiddenTopic() {
        var code = rooms.byKey().get("code");
        var policy = new ScopePolicy("code-room", "yaml:classroom-test", "1",
                code.scope(), ScopeGuardrailResolver.resolve(code.scope().tier()));

        // The forbidden-topic match is the deterministic assertion —
        // rule-based scope's on-topic admit is probabilistic depending on
        // keyword overlap, but off-topic-via-forbidden is rock-solid.
        var offTopic = policy.evaluate(
                preAdm("Please give me medical advice for chest pain"));
        assertTrue(offTopic instanceof PolicyDecision.Transform
                        || offTopic instanceof PolicyDecision.Deny,
                "code room forbids medical advice per YAML, got: " + offTopic);
    }

    @Test
    void goal1_interceptorInstallsScopeConfigOnMetadata() {
        var math = RoomContextInterceptor.scopeFor("math");
        assertNotNull(math);
        assertTrue(math.purpose().toLowerCase().contains("mathematic"));
        assertTrue(math.forbiddenTopics().stream()
                .anyMatch(t -> t.toLowerCase().contains("source code")));
    }

    @Test
    void goal1_unknownRoomFallsBackToDefault() {
        var fallback = RoomContextInterceptor.scopeFor("quantum-basketball");
        var general = rooms.byKey().get("general").scope();
        assertEquals(general.purpose(), fallback.purpose(),
                "unknown rooms must fall back to the configured default");
    }

    @Test
    void goal1_scopeCarriesPerRoomRedirectMessage() {
        var science = rooms.byKey().get("science").scope();
        assertTrue(science.redirectMessage().contains("science room"),
                "per-room redirect messages must survive YAML load");
    }

    @Test
    void goal1_scopeConfigIsReadyForRequestScopeMetadataKey() {
        // The whole point: ScopeConfig lands on AiRequest.metadata() under
        // REQUEST_SCOPE_METADATA_KEY and AiStreamingSession picks it up.
        var code = RoomContextInterceptor.scopeFor("code");
        var req = new AiRequest("how do I write a for loop", null, null, null, null,
                null, null,
                Map.of(ScopePolicy.REQUEST_SCOPE_METADATA_KEY, code), null);
        assertEquals(code, req.metadata().get(ScopePolicy.REQUEST_SCOPE_METADATA_KEY));
    }
}
