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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingScopeGuardrailTest {

    @Test
    void tierIsEmbeddingSimilarity() {
        assertEquals(AgentScope.Tier.EMBEDDING_SIMILARITY,
                new EmbeddingScopeGuardrail(null).tier());
    }

    @Test
    void missingRuntimeAdmitsWithWarning() {
        var guardrail = new EmbeddingScopeGuardrail(null);
        var decision = guardrail.evaluate(new AiRequest("hi"), ruleConfig("support"));
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, decision.outcome());
    }

    @Test
    void highSimilarityToPurposeAdmits() {
        // Fake runtime: returns the same vector for on-topic text, orthogonal for off-topic.
        var runtime = new StubEmbeddingRuntime(Map.of(
                "customer support", new float[] { 1.0f, 0.0f, 0.0f, 0.0f },
                "where is my order", new float[] { 0.9f, 0.1f, 0.0f, 0.0f }));
        var guardrail = new EmbeddingScopeGuardrail(runtime);

        var decision = guardrail.evaluate(
                new AiRequest("where is my order"),
                new ScopeConfig(
                        "customer support", List.of(), AgentScope.Breach.DENY, "",
                        AgentScope.Tier.EMBEDDING_SIMILARITY, 0.5, false, false, ""));
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, decision.outcome());
        assertTrue(decision.similarity() > 0.9,
                "expected high cosine similarity: " + decision.similarity());
    }

    @Test
    void lowSimilarityRejects() {
        var runtime = new StubEmbeddingRuntime(Map.of(
                "customer support", new float[] { 1.0f, 0.0f, 0.0f, 0.0f },
                "write python code to reverse a linked list", new float[] { 0.0f, 1.0f, 0.0f, 0.0f }));
        var guardrail = new EmbeddingScopeGuardrail(runtime);

        var decision = guardrail.evaluate(
                new AiRequest("write python code to reverse a linked list"),
                new ScopeConfig(
                        "customer support", List.of(), AgentScope.Breach.DENY, "",
                        AgentScope.Tier.EMBEDDING_SIMILARITY, 0.5, false, false, ""));
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, decision.outcome());
        assertTrue(decision.reason().contains("below threshold"),
                "reason should cite the threshold: " + decision.reason());
    }

    @Test
    void closerToForbiddenTopicWins() {
        // Purpose vector and message vector are moderately close; forbidden
        // topic vector is MUCH closer to the message. Should reject with
        // "closer to forbidden topic" reason, not "below threshold".
        var runtime = new StubEmbeddingRuntime(Map.of(
                "general assistant", new float[] { 0.7f, 0.7f, 0.0f, 0.0f },
                "medical", new float[] { 0.1f, 0.1f, 1.0f, 0.0f },
                "my symptoms include fever and headache", new float[] { 0.2f, 0.2f, 0.9f, 0.0f }));
        var guardrail = new EmbeddingScopeGuardrail(runtime);

        var decision = guardrail.evaluate(
                new AiRequest("my symptoms include fever and headache"),
                new ScopeConfig(
                        "general assistant", List.of("medical"), AgentScope.Breach.DENY, "",
                        AgentScope.Tier.EMBEDDING_SIMILARITY, 0.3, false, false, ""));
        assertEquals(ScopeGuardrail.Outcome.OUT_OF_SCOPE, decision.outcome());
        assertTrue(decision.reason().toLowerCase().contains("forbidden topic"),
                "reason should blame the forbidden topic: " + decision.reason());
    }

    @Test
    void purposeEmbeddingIsCached() {
        var embedCount = new AtomicInteger();
        var runtime = new CountingEmbeddingRuntime(embedCount, Map.of(
                "customer support", new float[] { 1.0f, 0.0f, 0.0f },
                "order 1", new float[] { 0.9f, 0.1f, 0.0f },
                "order 2", new float[] { 0.85f, 0.15f, 0.0f },
                "order 3", new float[] { 0.8f, 0.2f, 0.0f }));
        var guardrail = new EmbeddingScopeGuardrail(runtime);
        var config = new ScopeConfig(
                "customer support", List.of(), AgentScope.Breach.DENY, "",
                AgentScope.Tier.EMBEDDING_SIMILARITY, 0.5, false, false, "");

        guardrail.evaluate(new AiRequest("order 1"), config);
        guardrail.evaluate(new AiRequest("order 2"), config);
        guardrail.evaluate(new AiRequest("order 3"), config);

        // Expect: 1 purpose embed + 3 message embeds = 4 embed calls total.
        // If the purpose was re-embedded each turn, we'd see 6.
        assertEquals(4, embedCount.get(),
                "purpose must be embedded exactly once, not per request");
    }

    @Test
    void unrestrictedBypassesEmbeddingEntirely() {
        var embedCount = new AtomicInteger();
        var runtime = new CountingEmbeddingRuntime(embedCount, Map.of());
        var guardrail = new EmbeddingScopeGuardrail(runtime);
        var config = new ScopeConfig(
                "", List.of(), AgentScope.Breach.DENY, "",
                AgentScope.Tier.EMBEDDING_SIMILARITY, 0.5, false,
                true, "LLM playground");
        var decision = guardrail.evaluate(new AiRequest("anything goes"), config);
        assertEquals(ScopeGuardrail.Outcome.IN_SCOPE, decision.outcome());
        assertEquals(0, embedCount.get(),
                "unrestricted must not consume embedding calls");
    }

    @Test
    void runtimeFailureReportsError() {
        var runtime = new ThrowingEmbeddingRuntime();
        var guardrail = new EmbeddingScopeGuardrail(runtime);
        var decision = guardrail.evaluate(new AiRequest("hi"), ruleConfig("support"));
        assertEquals(ScopeGuardrail.Outcome.ERROR, decision.outcome());
    }

    @Test
    void cosineSimilaritySanity() {
        var a = new float[] { 1.0f, 0.0f, 0.0f };
        var b = new float[] { 0.0f, 1.0f, 0.0f };
        // identical vectors
        assertEquals(1.0, EmbeddingScopeGuardrail.cosineSimilarity(a, a.clone()), 1e-9);
        // orthogonal vectors
        assertEquals(0.0, EmbeddingScopeGuardrail.cosineSimilarity(a, b), 1e-9);
        // length mismatch
        assertEquals(-1.0,
                EmbeddingScopeGuardrail.cosineSimilarity(a, new float[] { 1.0f, 1.0f }),
                1e-9);
    }

    private static ScopeConfig ruleConfig(String purpose) {
        return new ScopeConfig(purpose, List.of(), AgentScope.Breach.DENY, "",
                AgentScope.Tier.EMBEDDING_SIMILARITY, 0.5, false, false, "");
    }

    // --- stub embedding runtimes ----------------------------------------

    private static class StubEmbeddingRuntime implements EmbeddingRuntime {
        private final Map<String, float[]> lookup;

        StubEmbeddingRuntime(Map<String, float[]> lookup) {
            this.lookup = lookup;
        }

        @Override public String name() { return "stub"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int dimensions() { return 4; }

        @Override
        public float[] embed(String text) {
            var v = lookup.get(text);
            if (v == null) {
                // Unknown input — return zeros so the test can see "unknown" without crashing.
                return new float[4];
            }
            return v.clone();
        }
    }

    private static class CountingEmbeddingRuntime extends StubEmbeddingRuntime {
        private final AtomicInteger counter;

        CountingEmbeddingRuntime(AtomicInteger counter, Map<String, float[]> lookup) {
            super(lookup);
            this.counter = counter;
        }

        @Override
        public float[] embed(String text) {
            counter.incrementAndGet();
            return super.embed(text);
        }
    }

    private static class ThrowingEmbeddingRuntime implements EmbeddingRuntime {
        @Override public String name() { return "throw"; }
        @Override public boolean isAvailable() { return true; }

        @Override public float[] embed(String text) {
            throw new RuntimeException("boom");
        }
    }
}
