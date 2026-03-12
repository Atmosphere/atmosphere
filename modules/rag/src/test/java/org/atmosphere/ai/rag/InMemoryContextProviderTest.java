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
package org.atmosphere.ai.rag;

import org.atmosphere.ai.ContextProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryContextProviderTest {

    @Test
    void retrieveReturnsMatchingDocuments() {
        var docs = List.of(
                new ContextProvider.Document("WebSocket enables real-time bidirectional communication", "ws.md", 1.0),
                new ContextProvider.Document("SSE provides server-sent events for streaming", "sse.md", 1.0),
                new ContextProvider.Document("Long-polling is a fallback transport mechanism", "lp.md", 1.0)
        );
        var provider = new InMemoryContextProvider(docs);

        var results = provider.retrieve("WebSocket real-time", 2);

        assertFalse(results.isEmpty());
        assertEquals("ws.md", results.get(0).source());
    }

    @Test
    void retrieveRanksResultsByRelevance() {
        var docs = List.of(
                new ContextProvider.Document("Atmosphere is a Java framework", "overview.md", 1.0),
                new ContextProvider.Document("Java WebSocket API uses annotations like @ServerEndpoint", "jsr356.md", 1.0),
                new ContextProvider.Document("Java framework for building real-time apps with WebSocket", "intro.md", 1.0)
        );
        var provider = new InMemoryContextProvider(docs);

        var results = provider.retrieve("Java WebSocket framework", 3);

        assertFalse(results.isEmpty());
        // The document with the most word overlap should rank highest
        assertTrue(results.get(0).score() >= results.get(results.size() - 1).score());
    }

    @Test
    void retrieveRespectsMaxResults() {
        var docs = List.of(
                new ContextProvider.Document("Document one about Java", "d1.md", 1.0),
                new ContextProvider.Document("Document two about Java", "d2.md", 1.0),
                new ContextProvider.Document("Document three about Java", "d3.md", 1.0)
        );
        var provider = new InMemoryContextProvider(docs);

        var results = provider.retrieve("Java", 2);

        assertEquals(2, results.size());
    }

    @Test
    void retrieveReturnsEmptyForNoMatch() {
        var docs = List.of(
                new ContextProvider.Document("Atmosphere supports WebSocket", "ws.md", 1.0)
        );
        var provider = new InMemoryContextProvider(docs);

        var results = provider.retrieve("quantum entanglement", 5);

        assertTrue(results.isEmpty());
    }

    @Test
    void retrieveReturnsEmptyForNullQuery() {
        var docs = List.of(
                new ContextProvider.Document("Some content", "doc.md", 1.0)
        );
        var provider = new InMemoryContextProvider(docs);

        var results = provider.retrieve(null, 5);

        assertTrue(results.isEmpty());
    }

    @Test
    void retrieveReturnsEmptyForBlankQuery() {
        var docs = List.of(
                new ContextProvider.Document("Some content", "doc.md", 1.0)
        );
        var provider = new InMemoryContextProvider(docs);

        var results = provider.retrieve("   ", 5);

        assertTrue(results.isEmpty());
    }

    @Test
    void retrieveReturnsEmptyForZeroMaxResults() {
        var docs = List.of(
                new ContextProvider.Document("Java framework", "doc.md", 1.0)
        );
        var provider = new InMemoryContextProvider(docs);

        var results = provider.retrieve("Java", 0);

        assertTrue(results.isEmpty());
    }

    @Test
    void retrieveReturnsEmptyForEmptyDocuments() {
        var provider = new InMemoryContextProvider(List.of());

        var results = provider.retrieve("anything", 5);

        assertTrue(results.isEmpty());
    }

    @Test
    void scoreReflectsWordOverlap() {
        var docs = List.of(
                new ContextProvider.Document("Java is a programming language", "java.md", 1.0),
                new ContextProvider.Document("Java programming with WebSocket and real-time", "ws.md", 1.0)
        );
        var provider = new InMemoryContextProvider(docs);

        var results = provider.retrieve("Java programming WebSocket", 2);

        assertEquals(2, results.size());
        // The document with more matching words should have a higher score
        assertTrue(results.get(0).score() > results.get(1).score());
    }

    @Test
    void fromClasspathThrowsForMissingResource() {
        assertThrows(IllegalArgumentException.class,
                () -> InMemoryContextProvider.fromClasspath("nonexistent-file.txt"));
    }

    @Test
    void documentsAreImmutableAfterConstruction() {
        var mutableList = new java.util.ArrayList<>(List.of(
                new ContextProvider.Document("test", "test.md", 1.0)
        ));
        var provider = new InMemoryContextProvider(mutableList);
        mutableList.clear();

        var results = provider.retrieve("test", 5);

        assertFalse(results.isEmpty());
    }

    @Test
    void metadataIsPreserved() {
        var metadata = java.util.Map.of("author", "jfarcand", "version", "4.0");
        var docs = List.of(
                new ContextProvider.Document("Atmosphere framework", "atmo.md", 1.0, metadata)
        );
        var provider = new InMemoryContextProvider(docs);

        var results = provider.retrieve("Atmosphere", 1);

        assertEquals(1, results.size());
        assertEquals("jfarcand", results.get(0).metadata().get("author"));
        assertEquals("4.0", results.get(0).metadata().get("version"));
    }
}
