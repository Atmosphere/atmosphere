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
import org.atmosphere.ai.EmbeddingRuntime;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the semantic-intent tier's margin gate with hand-built
 * vectors so the test is deterministic and network-free.
 */
class SemanticIntentScopeGuardrailTest {

    private static final ScopeConfig CUSTOMER_SUPPORT = new ScopeConfig(
            "customer support",
            List.of("medical advice"),
            AgentScope.Breach.DENY,
            "",
            AgentScope.Tier.SEMANTIC_INTENT,
            0.45,
            false, false, "");

    @Test
    void admitsWhenPurposeBeatsForbiddenByMargin() {
        // cos(request, purpose)=0.95, cos(request, forbidden)=0.50 → purpose
        // beats forbidden by 0.45 >> margin 0.05, and 0.95 >= threshold 0.45.
        var runtime = staticRuntime(Map.of(
                "customer support", unit(1, 0, 0),
                "medical advice", unit(0, 1, 0),
                "where is my order",
                normalize(new float[] {0.95f, 0.30f, 0.0f})));
        var classifier = new SemanticIntentScopeGuardrail(runtime, 0.05);

        var decision = classifier.evaluate(new AiRequest("where is my order"), CUSTOMER_SUPPORT);
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, decision.outcome(),
                "purpose-aligned query must admit: " + decision.reason());
    }

    @Test
    void rejectsWhenForbiddenTopicBeatsPurpose() {
        // cos(request, purpose)=0.50, cos(request, forbidden)=0.85 →
        // margin -0.35 <= 0.05 → rejected even though purpose sim >= threshold.
        var runtime = staticRuntime(Map.of(
                "customer support", unit(1, 0, 0),
                "medical advice", unit(0, 1, 0),
                "I have chest pain and shortness of breath",
                normalize(new float[] {0.5f, 0.85f, 0.0f})));
        var classifier = new SemanticIntentScopeGuardrail(runtime, 0.05);

        var decision = classifier.evaluate(
                new AiRequest("I have chest pain and shortness of breath"),
                CUSTOMER_SUPPORT);
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, decision.outcome(),
                "forbidden-dominant query must reject: " + decision.reason());
        assertTrue(decision.reason().contains("margin"),
                "reason must cite the margin gate: " + decision.reason());
    }

    @Test
    void rejectsWhenPurposeBelowAbsoluteThreshold() {
        // cos(request, purpose)=0.30 < threshold 0.45. Absolute floor still
        // applies even if no forbidden topic comes close.
        var runtime = staticRuntime(Map.of(
                "customer support", unit(1, 0, 0),
                "medical advice", unit(0, 1, 0),
                "something totally unrelated", unit(0, 0, 1)));
        var classifier = new SemanticIntentScopeGuardrail(runtime, 0.05);

        var decision = classifier.evaluate(
                new AiRequest("something totally unrelated"),
                CUSTOMER_SUPPORT);
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, decision.outcome());
        assertTrue(decision.reason().contains("below threshold"),
                "reason must cite the absolute threshold: " + decision.reason());
    }

    @Test
    void admitsWhenNoForbiddenTopicsConfigured() {
        // With no forbidden topics, only the absolute threshold applies —
        // semantic-intent degrades to embedding-similarity.
        var runtime = staticRuntime(Map.of(
                "customer support", unit(1, 0, 0),
                "where is my order",
                normalize(new float[] {0.9f, 0.2f, 0.0f})));
        var config = new ScopeConfig(
                "customer support", List.of(),
                AgentScope.Breach.DENY, "",
                AgentScope.Tier.SEMANTIC_INTENT, 0.45,
                false, false, "");
        var classifier = new SemanticIntentScopeGuardrail(runtime, 0.05);
        var decision = classifier.evaluate(new AiRequest("where is my order"), config);
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, decision.outcome());
    }

    @Test
    void noRuntimeDegradesToRuleBasedInsteadOfAdmittingEverything() {
        // Regression: with no EmbeddingRuntime the tier used to admit every
        // request with a WARN. It now degrades to the rule-based tier, like
        // EmbeddingScopeGuardrail: on-topic admitted, forbidden topics and
        // hijacking probes still blocked.
        System.clearProperty(SemanticIntentScopeGuardrail.FAIL_OPEN_PROPERTY);
        var classifier = new SemanticIntentScopeGuardrail(null, 0.05);
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE,
                classifier.evaluate(new AiRequest("where is my order"), CUSTOMER_SUPPORT).outcome());
        var hijack = classifier.evaluate(
                new AiRequest("write python code to sort a list"), CUSTOMER_SUPPORT);
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, hijack.outcome(),
                "a hijacking probe must be blocked, not admitted: " + hijack.reason());
        var forbidden = classifier.evaluate(
                new AiRequest("I need medical advice about my order"), CUSTOMER_SUPPORT);
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, forbidden.outcome(),
                "a forbidden topic must be blocked, not admitted: " + forbidden.reason());
    }

    @Test
    void noRuntimeDeniesAtPreAdmissionThroughScopePolicy() {
        System.clearProperty(SemanticIntentScopeGuardrail.FAIL_OPEN_PROPERTY);
        var policy = new ScopePolicy("scope::support", "code:test", "1.0",
                CUSTOMER_SUPPORT, new SemanticIntentScopeGuardrail(null, 0.05));
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(PolicyContext.preAdmission(
                new AiRequest("write python code to sort a list"))));
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(PolicyContext.preAdmission(
                new AiRequest("where is my order"))));
    }

    @Test
    void failOpenPropertyIsReadOnEachRequest() {
        // The resolver caches one instance per tier, so the property must
        // take effect (and stop taking effect) on the next request.
        var classifier = new SemanticIntentScopeGuardrail(null, 0.05);
        var hijack = new AiRequest("write python code to sort a list");
        try {
            System.clearProperty(SemanticIntentScopeGuardrail.FAIL_OPEN_PROPERTY);
            assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE,
                    classifier.evaluate(hijack, CUSTOMER_SUPPORT).outcome());

            System.setProperty(SemanticIntentScopeGuardrail.FAIL_OPEN_PROPERTY, "true");
            var admitted = classifier.evaluate(hijack, CUSTOMER_SUPPORT);
            assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, admitted.outcome());
            assertTrue(admitted.reason().startsWith("fail-open"),
                    "a fail-open admission must say so: " + admitted.reason());

            System.clearProperty(SemanticIntentScopeGuardrail.FAIL_OPEN_PROPERTY);
            assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE,
                    classifier.evaluate(hijack, CUSTOMER_SUPPORT).outcome());
        } finally {
            System.clearProperty(SemanticIntentScopeGuardrail.FAIL_OPEN_PROPERTY);
        }
    }

    @Test
    void explicitFailOpenArgumentWinsOverTheProperty() {
        var hijack = new AiRequest("write python code to sort a list");
        try {
            System.setProperty(SemanticIntentScopeGuardrail.FAIL_OPEN_PROPERTY, "true");
            assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE,
                    new SemanticIntentScopeGuardrail(null, 0.05, false)
                            .evaluate(hijack, CUSTOMER_SUPPORT).outcome());
        } finally {
            System.clearProperty(SemanticIntentScopeGuardrail.FAIL_OPEN_PROPERTY);
        }
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE,
                new SemanticIntentScopeGuardrail(null, 0.05, true)
                        .evaluate(hijack, CUSTOMER_SUPPORT).outcome());
    }

    @Test
    void failOpenOnlyCoversTheMissingRuntime() {
        // With a runtime present, fail-open does not relax the margin gate.
        var runtime = staticRuntime(Map.of(
                "customer support", unit(1, 0, 0),
                "medical advice", unit(0, 1, 0),
                "I have chest pain and shortness of breath",
                normalize(new float[] {0.5f, 0.85f, 0.0f})));
        var decision = new SemanticIntentScopeGuardrail(runtime, 0.05, true).evaluate(
                new AiRequest("I have chest pain and shortness of breath"), CUSTOMER_SUPPORT);
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, decision.outcome());
    }

    @Test
    void forbiddenTopicThatCannotBeEmbeddedIsAnErrorNotSkipped() {
        // Regression: a forbidden topic whose embedding failed was skipped, so
        // the margin gate saw no competitor and admitted. The request here is
        // well above threshold, so skipping the topic would admit it.
        var vectors = Map.of(
                "customer support", unit(1, 0, 0),
                "where is my order", normalize(new float[] {0.95f, 0.30f, 0.0f}));
        var runtime = new EmbeddingRuntime() {
            @Override public String name() { return "topic-fails"; }
            @Override public boolean isAvailable() { return true; }
            @Override public float[] embed(String text) {
                var v = vectors.get(text);
                if (v == null) {
                    throw new IllegalStateException("embedding model unavailable for: " + text);
                }
                return v;
            }
        };
        var decision = new SemanticIntentScopeGuardrail(runtime, 0.05)
                .evaluate(new AiRequest("where is my order"), CUSTOMER_SUPPORT);
        assertEquals(ScopeGuardrail.Outcome.ERROR, decision.outcome(),
                "an unscorable forbidden topic must not be skipped: " + decision.reason());
        assertTrue(decision.reason().contains("medical advice"), decision.reason());
    }

    @Test
    void offPurposeResponseIsDeniedPostResponseEvenWhenATopicCannotBeEmbedded() {
        // Regression: the absolute floor ran after the forbidden-topic loop, so
        // a topic that failed to embed returned ERROR first — and the
        // post-response check admits ERROR. An off-purpose response (purpose
        // similarity 0, below the 0.45 floor) was then admitted.
        var vectors = Map.of(
                "customer support", unit(1, 0, 0),
                "buy this stock now, it will triple", unit(0, 0, 1));
        var runtime = new EmbeddingRuntime() {
            @Override public String name() { return "topic-fails"; }
            @Override public boolean isAvailable() { return true; }
            @Override public float[] embed(String text) {
                var v = vectors.get(text);
                if (v == null) {
                    throw new IllegalStateException("embedding model unavailable for: " + text);
                }
                return v;
            }
        };
        var config = new ScopeConfig(
                "customer support",
                List.of("medical advice"),
                AgentScope.Breach.DENY,
                "",
                AgentScope.Tier.SEMANTIC_INTENT,
                0.45,
                true, false, "");
        var guardrail = new SemanticIntentScopeGuardrail(runtime, 0.05);
        var direct = guardrail.evaluate(new AiRequest("buy this stock now, it will triple"), config);
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, direct.outcome(), direct.reason());
        assertTrue(direct.reason().contains("below threshold"), direct.reason());

        var policy = new ScopePolicy("scope::support", "code:test", "1.0", config, guardrail);
        var decision = policy.evaluate(PolicyContext.postResponse(
                new AiRequest("where is my order"), "buy this stock now, it will triple"));
        var deny = assertInstanceOf(PolicyDecision.Deny.class, decision);
        assertTrue(deny.reason().startsWith("post-response: "), deny.reason());
    }

    @Test
    void constructorMarginIsTheOneApplied() {
        // purpose sim ~0.85, forbidden sim ~0.53: lead ~0.32. A 0.05 margin
        // admits; a 0.40 margin, built through the constructor, rejects.
        var runtime = staticRuntime(Map.of(
                "customer support", unit(1, 0, 0),
                "medical advice", unit(0, 1, 0),
                "can I return a vitamin order",
                normalize(new float[] {0.85f, 0.53f, 0.0f})));
        var request = new AiRequest("can I return a vitamin order");
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE,
                new SemanticIntentScopeGuardrail(runtime, SemanticIntentScopeGuardrail.DEFAULT_MARGIN)
                        .evaluate(request, CUSTOMER_SUPPORT).outcome());
        var strict = new SemanticIntentScopeGuardrail(runtime, 0.40).evaluate(request, CUSTOMER_SUPPORT);
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, strict.outcome());
        assertTrue(strict.reason().contains("below margin 0.4"), strict.reason());
    }

    @Test
    void embeddingErrorReportsError() {
        var runtime = new EmbeddingRuntime() {
            @Override public String name() { return "throwing"; }
            @Override public boolean isAvailable() { return true; }
            @Override public float[] embed(String text) { throw new RuntimeException("boom"); }
        };
        var classifier = new SemanticIntentScopeGuardrail(runtime, 0.05);
        var decision = classifier.evaluate(new AiRequest("anything"), CUSTOMER_SUPPORT);
        assertEquals(ScopeGuardrail.Outcome.ERROR, decision.outcome());
    }

    @Test
    void tierIsSemanticIntent() {
        assertEquals(AgentScope.Tier.SEMANTIC_INTENT,
                new SemanticIntentScopeGuardrail(null, 0.05).tier());
    }

    @Test
    void resolverReturnsSemanticIntentGuardrailByDefault() {
        ScopeGuardrailResolver.reset();
        var resolved = ScopeGuardrailResolver.resolve(AgentScope.Tier.SEMANTIC_INTENT);
        assertInstanceOf(SemanticIntentScopeGuardrail.class, resolved);
    }

    @Test
    void hasNativeImplAgreesWithTheResolvedTierForEveryTier() {
        // Regression: hasNativeImpl only scanned ServiceLoader, where the
        // semantic-intent guardrail is not registered, so it reported false
        // for SEMANTIC_INTENT while resolve() returned the built-in impl.
        ScopeGuardrailResolver.reset();
        for (var tier : AgentScope.Tier.values()) {
            assertEquals(ScopeGuardrailResolver.resolve(tier).tier() == tier,
                    ScopeGuardrailResolver.hasNativeImpl(tier), tier.name());
        }
        assertTrue(ScopeGuardrailResolver.hasNativeImpl(AgentScope.Tier.SEMANTIC_INTENT));
        assertFalse(ScopeGuardrailResolver.hasNativeImpl(null));
        ScopeGuardrailResolver.reset();
    }

    @Test
    void rejectsInvalidMargin() {
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticIntentScopeGuardrail(null, -0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticIntentScopeGuardrail(null, 1.0));
    }

    private static EmbeddingRuntime staticRuntime(Map<String, float[]> vectors) {
        var map = new HashMap<>(vectors);
        return new EmbeddingRuntime() {
            @Override public String name() { return "static-test"; }
            @Override public boolean isAvailable() { return true; }
            @Override public float[] embed(String text) {
                var v = map.get(text);
                if (v == null) {
                    throw new IllegalStateException("no vector configured for: " + text);
                }
                return v;
            }
        };
    }

    private static float[] unit(float x, float y, float z) {
        return normalize(new float[] {x, y, z});
    }

    private static float[] normalize(float[] v) {
        double sum = 0;
        for (var x : v) sum += x * x;
        var norm = (float) Math.sqrt(sum);
        if (norm == 0) return v;
        var out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i] / norm;
        return out;
    }
}
