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
package org.atmosphere.samples.springboot.msgovernance;

import org.atmosphere.ai.AiRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaqRetrievalInterceptorTest {

    private final FaqRetrievalInterceptor interceptor = new FaqRetrievalInterceptor();

    @Test
    void retrievesRefundPolicyForRefundQuestion() {
        var out = interceptor.preProcess(
                new AiRequest("what is your refund policy for returned items"), null);

        var snippet = (String) out.metadata().get(FaqKnowledgeBase.RAG_SNIPPET_METADATA_KEY);
        assertNotNull(snippet);
        assertTrue(snippet.toLowerCase().contains("refund"),
                "expected snippet about refund, got: " + snippet);
        assertEquals("refunds", out.metadata().get(FaqKnowledgeBase.RAG_CATEGORY_METADATA_KEY));
    }

    @Test
    void retrievesPaymentMethodsForBillingQuestion() {
        var out = interceptor.preProcess(
                new AiRequest("what payment methods do you accept"), null);

        assertEquals("billing", out.metadata().get(FaqKnowledgeBase.RAG_CATEGORY_METADATA_KEY));
        var snippet = (String) out.metadata().get(FaqKnowledgeBase.RAG_SNIPPET_METADATA_KEY);
        assertTrue(snippet.toLowerCase().contains("visa"),
                "expected snippet about payment methods, got: " + snippet);
    }

    @Test
    void retrievesShippingCostForShippingQuestion() {
        var out = interceptor.preProcess(
                new AiRequest("how much does shipping cost"), null);
        assertEquals("shipping", out.metadata().get(FaqKnowledgeBase.RAG_CATEGORY_METADATA_KEY));
    }

    @Test
    void recordsOverlapScoreAlongsideSnippet() {
        var out = interceptor.preProcess(
                new AiRequest("how do I reset my password"), null);
        var score = (Integer) out.metadata().get("rag.score");
        assertNotNull(score);
        assertTrue(score >= FaqKnowledgeBase.MIN_MATCH_SCORE,
                "expected score >= min, got: " + score);
    }

    @Test
    void leavesRequestUnchangedOnNoMatch() {
        var out = interceptor.preProcess(
                new AiRequest("pineapple quantum disco"), null);
        assertNull(out.metadata().get(FaqKnowledgeBase.RAG_SNIPPET_METADATA_KEY));
    }

    @Test
    void doesNotMutateOriginalMessage() {
        var original = "what is your refund policy";
        var out = interceptor.preProcess(new AiRequest(original), null);
        assertEquals(original, out.message(),
                "message must stay unchanged so scope/audit see the real user text");
    }

    @Test
    void nullOrBlankMessageShortCircuits() {
        var blank = interceptor.preProcess(new AiRequest(""), null);
        assertNull(blank.metadata().get(FaqKnowledgeBase.RAG_SNIPPET_METADATA_KEY));
    }

    @Test
    void rejectsNullKnowledgeBase() {
        assertThrows(IllegalArgumentException.class, () -> new FaqRetrievalInterceptor(null));
    }

    @Test
    void customKnowledgeBaseIsRetrievable() {
        var kb = new FaqKnowledgeBase(List.of(
                FaqKnowledgeBase.entry("custom",
                        "How does thermonuclear widget manufacturing work",
                        "Widgets are manufactured in carefully controlled environments.",
                        "widget", "manufacture", "thermonuclear")));
        var custom = new FaqRetrievalInterceptor(kb);

        var out = custom.preProcess(
                new AiRequest("tell me about thermonuclear widget manufacturing"), null);
        assertEquals("custom", out.metadata().get(FaqKnowledgeBase.RAG_CATEGORY_METADATA_KEY));
    }

    @Test
    void exampleCorpKbIsNotEmpty() {
        var kb = FaqKnowledgeBase.exampleCorp();
        assertFalse(kb.entries().isEmpty());
        assertTrue(kb.entries().size() >= 5);
    }
}
