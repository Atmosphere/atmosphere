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
package org.atmosphere.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ContextProvider} interface default methods and the
 * {@link ContextProvider.Document} record.
 */
class ContextProviderTest {

    /** Minimal implementation that just returns fixed documents. */
    static class StubProvider implements ContextProvider {
        private final List<Document> docs;

        StubProvider(List<Document> docs) {
            this.docs = docs;
        }

        @Override
        public List<Document> retrieve(String query, int maxResults) {
            return docs.stream().limit(maxResults).toList();
        }
    }

    // --- Document record tests ---

    @Test
    void documentRecordThreeArgConstructor() {
        var doc = new ContextProvider.Document("content", "source.txt", 0.95);
        assertEquals("content", doc.content());
        assertEquals("source.txt", doc.source());
        assertEquals(0.95, doc.score(), 0.001);
        assertEquals(Map.of(), doc.metadata());
    }

    @Test
    void documentRecordFourArgConstructor() {
        var meta = Map.of("author", "test");
        var doc = new ContextProvider.Document("content", "src", 0.8, meta);
        assertEquals("content", doc.content());
        assertEquals("src", doc.source());
        assertEquals(0.8, doc.score(), 0.001);
        assertEquals("test", doc.metadata().get("author"));
    }

    @Test
    void documentRecordEquality() {
        var doc1 = new ContextProvider.Document("text", "file.md", 0.5);
        var doc2 = new ContextProvider.Document("text", "file.md", 0.5);
        assertEquals(doc1, doc2);
        assertEquals(doc1.hashCode(), doc2.hashCode());
    }

    @Test
    void documentRecordInequalityOnContent() {
        var doc1 = new ContextProvider.Document("text1", "file.md", 0.5);
        var doc2 = new ContextProvider.Document("text2", "file.md", 0.5);
        assertTrue(!doc1.equals(doc2));
    }

    @Test
    void documentRecordInequalityOnScore() {
        var doc1 = new ContextProvider.Document("text", "file.md", 0.5);
        var doc2 = new ContextProvider.Document("text", "file.md", 0.9);
        assertTrue(!doc1.equals(doc2));
    }

    // --- Default method tests ---

    @Test
    void transformQueryReturnsOriginalByDefault() {
        var provider = new StubProvider(List.of());
        assertEquals("test query", provider.transformQuery("test query"));
    }

    @Test
    void transformQueryPreservesEmptyString() {
        var provider = new StubProvider(List.of());
        assertEquals("", provider.transformQuery(""));
    }

    @Test
    void rerankReturnsDocumentsUnchangedByDefault() {
        var docs = List.of(
                new ContextProvider.Document("a", "1", 0.9),
                new ContextProvider.Document("b", "2", 0.5)
        );
        var provider = new StubProvider(docs);
        var result = provider.rerank("query", docs);
        assertEquals(docs, result);
        assertEquals(2, result.size());
        assertEquals("a", result.get(0).content());
    }

    @Test
    void rerankWithEmptyListReturnsEmptyList() {
        var provider = new StubProvider(List.of());
        var result = provider.rerank("query", List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void ingestThrowsUnsupportedByDefault() {
        var provider = new StubProvider(List.of());
        var docs = List.of(new ContextProvider.Document("data", "src", 1.0));
        var ex = assertThrows(UnsupportedOperationException.class,
                () -> provider.ingest(docs));
        assertTrue(ex.getMessage().contains("read-only"));
        assertTrue(ex.getMessage().contains("StubProvider"));
    }

    @Test
    void isAvailableReturnsTrueByDefault() {
        var provider = new StubProvider(List.of());
        assertTrue(provider.isAvailable());
    }

    @Test
    void retrieveRespectsMaxResults() {
        var docs = List.of(
                new ContextProvider.Document("a", "1", 0.9),
                new ContextProvider.Document("b", "2", 0.8),
                new ContextProvider.Document("c", "3", 0.7)
        );
        var provider = new StubProvider(docs);
        var result = provider.retrieve("query", 2);
        assertEquals(2, result.size());
    }

    @Test
    void customProviderCanOverrideTransformQuery() {
        ContextProvider provider = new ContextProvider() {
            @Override
            public List<Document> retrieve(String query, int maxResults) {
                return List.of();
            }

            @Override
            public String transformQuery(String originalQuery) {
                return "rewritten: " + originalQuery;
            }
        };
        assertEquals("rewritten: hello", provider.transformQuery("hello"));
    }

    @Test
    void customProviderCanOverrideRerank() {
        ContextProvider provider = new ContextProvider() {
            @Override
            public List<Document> retrieve(String query, int maxResults) {
                return List.of();
            }

            @Override
            public List<Document> rerank(String query, List<Document> documents) {
                return documents.stream()
                        .sorted((a, b) -> Double.compare(b.score(), a.score()))
                        .toList();
            }
        };
        var docs = List.of(
                new ContextProvider.Document("low", "1", 0.2),
                new ContextProvider.Document("high", "2", 0.9)
        );
        var result = provider.rerank("q", docs);
        assertEquals("high", result.get(0).content());
        assertEquals("low", result.get(1).content());
    }

    @Test
    void customProviderCanOverrideIngest() {
        var ingested = new java.util.ArrayList<ContextProvider.Document>();
        ContextProvider provider = new ContextProvider() {
            @Override
            public List<Document> retrieve(String query, int maxResults) {
                return List.of();
            }

            @Override
            public void ingest(List<Document> documents) {
                ingested.addAll(documents);
            }
        };
        var doc = new ContextProvider.Document("data", "src", 1.0);
        provider.ingest(List.of(doc));
        assertEquals(1, ingested.size());
        assertEquals("data", ingested.get(0).content());
    }

    @Test
    void customProviderCanOverrideIsAvailable() {
        ContextProvider unavailable = new ContextProvider() {
            @Override
            public List<Document> retrieve(String query, int maxResults) {
                return List.of();
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };
        assertTrue(!unavailable.isAvailable());
    }
}
