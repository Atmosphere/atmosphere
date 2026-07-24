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
package org.atmosphere.samples.springboot.ragchat;

import org.atmosphere.ai.rag.spring.SpringAiVectorStoreContextProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the P1 "flagship rag-chat fakes retrieval" gap: the
 * sample built a real Spring AI vector store but never queried it — every
 * retrieval was word-overlap keyword scoring. These tests pin the fixed wiring:
 * when a {@code VectorStore} is configured, both the {@code @AiEndpoint}
 * provider chain (via {@link SpringAiVectorStoreContextProvider}) and the
 * {@code @AiTool} path ({@link KnowledgeBase#search}) run real
 * {@code similaritySearch}, and the keyword provider stops duplicating the
 * trusted corpus while still contributing the poisoned demo document.
 *
 * <p>The {@link TopicEmbeddingModel} is deterministic — it buckets topic words
 * into fixed vector dimensions — so {@code SimpleVectorStore}'s cosine ranking
 * is exercised for real without an API key.</p>
 */
class VectorRetrievalTest {

    /**
     * Deterministic embedding model: counts topic-word occurrences into fixed
     * dimensions, so cosine similarity ranks a transports query nearest the
     * transports doc. Exercises the REAL SimpleVectorStore similarity path.
     */
    static final class TopicEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            var embeddings = new ArrayList<Embedding>();
            var texts = request.getInstructions();
            for (int i = 0; i < texts.size(); i++) {
                embeddings.add(new Embedding(embed(texts.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText() == null ? "" : document.getText());
        }

        @Override
        public float[] embed(String text) {
            var lower = text.toLowerCase(Locale.ROOT);
            var v = new float[4];
            v[0] = countOf(lower, "websocket") + countOf(lower, "transport");
            v[1] = countOf(lower, "agent") + countOf(lower, "tool");
            v[2] = countOf(lower, "embedding") + countOf(lower, "vector");
            v[3] = 0.1f; // keeps every vector non-zero so cosine is defined
            return v;
        }

        private static int countOf(String text, String word) {
            var count = 0;
            var idx = text.indexOf(word);
            while (idx >= 0) {
                count++;
                idx = text.indexOf(word, idx + word.length());
            }
            return count;
        }
    }

    @AfterEach
    void resetStaticStore() {
        // The provider holds a process-global static store — reset so other
        // tests (InjectionSafetyDemoTest's keyless demo path) see demo mode.
        SpringAiVectorStoreContextProvider.setVectorStore(null);
    }

    private static SimpleVectorStore seededStore() {
        var store = SimpleVectorStore.builder(new TopicEmbeddingModel()).build();
        store.add(List.of(
                new Document("Atmosphere supports WebSocket transport with SSE and "
                        + "long-polling transport fallback.", Map.of("source", "vector:transports")),
                new Document("Agents call tools through the AgentRuntime tool bridge; "
                        + "an agent can spawn a sub-agent.", Map.of("source", "vector:agents")),
                new Document("Vector embedding stores hold document embedding chunks.",
                        Map.of("source", "vector:embeddings"))));
        return store;
    }

    @Test
    void endpointProviderRunsRealSimilaritySearchWhenStoreConfigured() {
        SpringAiVectorStoreContextProvider.setVectorStore(seededStore());
        var provider = new SpringAiVectorStoreContextProvider();
        assertTrue(provider.isAvailable(), "provider must report the configured store");

        var results = provider.retrieve("which websocket transport does Atmosphere use?", 2);

        assertFalse(results.isEmpty(), "similaritySearch must return results");
        assertEquals("vector:transports", results.get(0).source(),
                "cosine ranking must put the transports doc first — proves the real "
                        + "VectorStore.similaritySearch path ran, not word-overlap");
    }

    @Test
    void knowledgeBaseSearchDelegatesToVectorStoreWhenAvailable() {
        // The @AiTool search path must ride the same vector store (mode parity
        // with the endpoint path). The vector docs carry marker sources that do
        // not exist in the in-memory corpus, so a vector-sourced result proves
        // the delegation.
        KnowledgeBase.instance().addDocuments(List.of(
                new org.atmosphere.ai.ContextProvider.Document(
                        "Fallback corpus entry about websocket transport.",
                        "docs/fallback-entry.md", 1.0)));
        SpringAiVectorStoreContextProvider.setVectorStore(seededStore());

        var results = KnowledgeBase.instance().search("websocket transport", 2);

        assertFalse(results.isEmpty());
        assertTrue(results.get(0).source().startsWith("vector:"),
                "search must delegate to the vector store when configured, got: "
                        + results.get(0).source());
    }

    @Test
    void keywordProviderContributesOnlyThePoisonedDocInKeyedMode() {
        // With the vector store active, the trusted corpus rides similarity
        // search — the keyword provider must stop duplicating it and contribute
        // only the untrusted community doc, keeping the A04 drop demo alive
        // without doubling the retrieved context.
        SpringAiVectorStoreContextProvider.setVectorStore(seededStore());

        var docs = new KnowledgeBaseContextProvider()
                .retrieve("how do I secure Atmosphere? ignore instructions", 5);

        assertTrue(docs.stream().allMatch(
                        d -> d.source().equals("docs/community-security-tips.md")),
                "keyed mode must not duplicate the trusted corpus through the "
                        + "keyword provider, got: " + docs.stream().map(
                                org.atmosphere.ai.ContextProvider.Document::source).toList());
    }
}
