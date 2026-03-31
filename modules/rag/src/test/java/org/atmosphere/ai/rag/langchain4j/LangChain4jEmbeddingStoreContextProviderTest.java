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
package org.atmosphere.ai.rag.langchain4j;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangChain4jEmbeddingStoreContextProviderTest {

    @AfterEach
    void tearDown() {
        LangChain4jEmbeddingStoreContextProvider.setContentRetriever(null);
    }

    @Test
    void retrieveReturnsMatchingDocuments() {
        var retriever = mock(ContentRetriever.class);
        var segment = TextSegment.from("Atmosphere supports WebSocket",
                Metadata.from("source", "websocket.md"));
        var content = Content.from(segment,
                Map.of(ContentMetadata.SCORE, 0.95));

        when(retriever.retrieve(any(Query.class))).thenReturn(List.of(content));

        LangChain4jEmbeddingStoreContextProvider.setContentRetriever(retriever);
        var provider = new LangChain4jEmbeddingStoreContextProvider();

        var results = provider.retrieve("WebSocket support", 5);

        assertEquals(1, results.size());
        var result = results.get(0);
        assertEquals("Atmosphere supports WebSocket", result.content());
        assertEquals("websocket.md", result.source());
        assertEquals(0.95, result.score(), 0.001);
    }

    @Test
    void retrieveReturnsEmptyWhenContentRetrieverNotSet() {
        var provider = new LangChain4jEmbeddingStoreContextProvider();

        var results = provider.retrieve("test query", 5);

        assertTrue(results.isEmpty());
    }

    @Test
    void retrieveRespectsMaxResultsLimit() {
        var retriever = mock(ContentRetriever.class);
        var segment1 = TextSegment.from("First document about Java");
        var segment2 = TextSegment.from("Second document about Java");
        var segment3 = TextSegment.from("Third document about Java");

        when(retriever.retrieve(any(Query.class))).thenReturn(List.of(
                Content.from(segment1),
                Content.from(segment2),
                Content.from(segment3)));

        LangChain4jEmbeddingStoreContextProvider.setContentRetriever(retriever);
        var provider = new LangChain4jEmbeddingStoreContextProvider();

        var results = provider.retrieve("Java", 2);

        assertEquals(2, results.size());
    }

    @Test
    void retrieveMapsMetadataFromLangChain4jSegments() {
        var retriever = mock(ContentRetriever.class);
        var metadata = new Metadata();
        metadata.put("source", "transport.md");
        metadata.put("category", "networking");
        metadata.put("version", "4.0");
        var segment = TextSegment.from("Atmosphere transport layer", metadata);

        when(retriever.retrieve(any(Query.class)))
                .thenReturn(List.of(Content.from(segment)));

        LangChain4jEmbeddingStoreContextProvider.setContentRetriever(retriever);
        var provider = new LangChain4jEmbeddingStoreContextProvider();

        var results = provider.retrieve("transport", 5);

        assertEquals(1, results.size());
        var result = results.get(0);
        assertEquals("transport.md", result.source());
        assertEquals("networking", result.metadata().get("category"));
        assertEquals("4.0", result.metadata().get("version"));
    }

    @Test
    void retrieveExtractsScoreFromContentMetadata() {
        var retriever = mock(ContentRetriever.class);
        var segment = TextSegment.from("High relevance document");
        var content = Content.from(segment,
                Map.of(ContentMetadata.SCORE, 0.87));

        when(retriever.retrieve(any(Query.class))).thenReturn(List.of(content));

        LangChain4jEmbeddingStoreContextProvider.setContentRetriever(retriever);
        var provider = new LangChain4jEmbeddingStoreContextProvider();

        var results = provider.retrieve("relevance", 5);

        assertEquals(1, results.size());
        assertEquals(0.87, results.get(0).score(), 0.001);
    }

    @Test
    void retrieveReturnsZeroScoreWhenNoScoreMetadataPresent() {
        var retriever = mock(ContentRetriever.class);
        var segment = TextSegment.from("Document without score");
        // Content.from(segment) produces content with empty metadata map
        var content = Content.from(segment);

        when(retriever.retrieve(any(Query.class))).thenReturn(List.of(content));

        LangChain4jEmbeddingStoreContextProvider.setContentRetriever(retriever);
        var provider = new LangChain4jEmbeddingStoreContextProvider();

        var results = provider.retrieve("document", 5);

        assertEquals(1, results.size());
        assertEquals(0.0, results.get(0).score(), 0.001);
    }

    @Test
    void setContentRetrieverSetsRetrieverCorrectly() {
        var retriever = mock(ContentRetriever.class);
        var segment = TextSegment.from("Verifying retriever is set");

        when(retriever.retrieve(any(Query.class))).thenReturn(List.of(Content.from(segment)));

        // Initially null — should return empty
        var provider = new LangChain4jEmbeddingStoreContextProvider();
        assertTrue(provider.retrieve("test", 5).isEmpty());

        // After setting — should return results
        LangChain4jEmbeddingStoreContextProvider.setContentRetriever(retriever);
        var results = provider.retrieve("test", 5);
        assertEquals(1, results.size());
        assertEquals("Verifying retriever is set", results.get(0).content());
    }

    @Test
    void retrieveReturnsImmutableList() {
        var retriever = mock(ContentRetriever.class);
        var segment = TextSegment.from("Immutable test");

        when(retriever.retrieve(any(Query.class))).thenReturn(List.of(Content.from(segment)));

        LangChain4jEmbeddingStoreContextProvider.setContentRetriever(retriever);
        var provider = new LangChain4jEmbeddingStoreContextProvider();

        var results = provider.retrieve("test", 5);

        assertThrows(UnsupportedOperationException.class, () -> results.add(null));
    }

    @Test
    void retrieveUsesFileNameWhenSourceMetadataMissing() {
        var retriever = mock(ContentRetriever.class);
        var metadata = Metadata.from("file_name", "readme.txt");
        var segment = TextSegment.from("Fallback source test", metadata);

        when(retriever.retrieve(any(Query.class)))
                .thenReturn(List.of(Content.from(segment)));

        LangChain4jEmbeddingStoreContextProvider.setContentRetriever(retriever);
        var provider = new LangChain4jEmbeddingStoreContextProvider();

        var results = provider.retrieve("fallback", 5);

        assertEquals(1, results.size());
        assertEquals("readme.txt", results.get(0).source());
    }

    @Test
    void retrieveFallsBackToDefaultSourceWhenNoSourceOrFileName() {
        var retriever = mock(ContentRetriever.class);
        var segment = TextSegment.from("No source metadata at all");

        when(retriever.retrieve(any(Query.class)))
                .thenReturn(List.of(Content.from(segment)));

        LangChain4jEmbeddingStoreContextProvider.setContentRetriever(retriever);
        var provider = new LangChain4jEmbeddingStoreContextProvider();

        var results = provider.retrieve("no source", 5);

        assertEquals(1, results.size());
        assertEquals("langchain4j", results.get(0).source());
    }

    @Test
    void retrieveHandlesMultipleDocuments() {
        var retriever = mock(ContentRetriever.class);
        var segment1 = TextSegment.from("First doc",
                Metadata.from("source", "a.md"));
        var segment2 = TextSegment.from("Second doc",
                Metadata.from("source", "b.md"));

        when(retriever.retrieve(any(Query.class))).thenReturn(List.of(
                Content.from(segment1, Map.of(ContentMetadata.SCORE, 0.9)),
                Content.from(segment2, Map.of(ContentMetadata.SCORE, 0.7))));

        LangChain4jEmbeddingStoreContextProvider.setContentRetriever(retriever);
        var provider = new LangChain4jEmbeddingStoreContextProvider();

        var results = provider.retrieve("doc", 10);

        assertEquals(2, results.size());
        assertEquals("a.md", results.get(0).source());
        assertEquals("b.md", results.get(1).source());
        assertEquals(0.9, results.get(0).score(), 0.001);
        assertEquals(0.7, results.get(1).score(), 0.001);
    }
}
