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
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void noRuntimeAdmitsWithWarning() {
        var classifier = new SemanticIntentScopeGuardrail(null, 0.05);
        var decision = classifier.evaluate(new AiRequest("anything"), CUSTOMER_SUPPORT);
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, decision.outcome(),
                "no runtime → admit so the rule-based tier can cover");
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
