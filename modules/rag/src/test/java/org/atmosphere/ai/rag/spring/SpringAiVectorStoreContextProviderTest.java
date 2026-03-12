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
package org.atmosphere.ai.rag.spring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiVectorStoreContextProviderTest {

    @AfterEach
    void tearDown() {
        SpringAiVectorStoreContextProvider.setVectorStore(null);
    }

    @Test
    void retrieveReturnsEmptyWhenVectorStoreNotSet() {
        var provider = new SpringAiVectorStoreContextProvider();

        var results = provider.retrieve("test query", 5);

        assertTrue(results.isEmpty());
    }

    @Test
    void retrieveMapsSpringAiDocumentsToContextProviderDocuments() {
        var vectorStore = mock(VectorStore.class);
        var springDoc = new Document("doc-1", "Atmosphere supports WebSocket",
                Map.of("source", "websocket.md", "category", "transport"));

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(springDoc));

        SpringAiVectorStoreContextProvider.setVectorStore(vectorStore);
        var provider = new SpringAiVectorStoreContextProvider();

        var results = provider.retrieve("WebSocket support", 5);

        assertEquals(1, results.size());
        var result = results.get(0);
        assertEquals("Atmosphere supports WebSocket", result.content());
        assertEquals("websocket.md", result.source());
        assertEquals("transport", result.metadata().get("category"));
    }

    @Test
    void retrievePassesTopKToSearchRequest() {
        var vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of());

        SpringAiVectorStoreContextProvider.setVectorStore(vectorStore);
        var provider = new SpringAiVectorStoreContextProvider();

        provider.retrieve("query", 3);

        verify(vectorStore).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void retrieveUsesDocumentIdWhenSourceMetadataMissing() {
        var vectorStore = mock(VectorStore.class);
        var springDoc = new Document("my-doc-id", "Some content", Map.of());

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(springDoc));

        SpringAiVectorStoreContextProvider.setVectorStore(vectorStore);
        var provider = new SpringAiVectorStoreContextProvider();

        var results = provider.retrieve("content", 5);

        assertEquals(1, results.size());
        assertEquals("my-doc-id", results.get(0).source());
    }

    @Test
    void retrieveHandlesMultipleDocuments() {
        var vectorStore = mock(VectorStore.class);
        var doc1 = new Document("id1", "First document", Map.of("source", "a.md"));
        var doc2 = new Document("id2", "Second document", Map.of("source", "b.md"));

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc1, doc2));

        SpringAiVectorStoreContextProvider.setVectorStore(vectorStore);
        var provider = new SpringAiVectorStoreContextProvider();

        var results = provider.retrieve("document", 10);

        assertEquals(2, results.size());
        assertEquals("a.md", results.get(0).source());
        assertEquals("b.md", results.get(1).source());
    }
}
