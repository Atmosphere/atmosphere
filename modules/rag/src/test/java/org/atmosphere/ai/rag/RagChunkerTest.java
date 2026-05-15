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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagChunkerTest {

    @Test
    void shortDocumentIsReturnedUnchanged() {
        var document = new ContextProvider.Document("short text", "short.md", 1.0);

        var chunks = RagChunker.chunk(document, 100, 10);

        assertEquals(1, chunks.size());
        assertSame(document, chunks.get(0));
    }

    @Test
    void longDocumentIsSplitWithSourceAttribution() {
        var document = new ContextProvider.Document(
                "Alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu",
                "guide.md",
                0.7,
                Map.of("category", "docs"));

        var chunks = RagChunker.chunk(document, 24, 5);

        assertTrue(chunks.size() > 1);
        assertEquals("guide.md#chunk-1", chunks.get(0).source());
        assertEquals("docs", chunks.get(0).metadata().get("category"));
        assertEquals("guide.md", chunks.get(0).metadata().get("source_document"));
        assertEquals(Integer.toString(chunks.size()), chunks.get(0).metadata().get("chunk_count"));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.content().length() <= 24));
    }

    @Test
    void nullSourceUsesStableFallback() {
        var document = new ContextProvider.Document(
                "Alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu",
                null,
                1.0,
                Map.of());

        var chunks = RagChunker.chunk(document, 24, 5);

        assertEquals("document#chunk-1", chunks.get(0).source());
        assertEquals("document", chunks.get(0).metadata().get("source_document"));
    }

    @Test
    void chunkAllFlattensDocuments() {
        var documents = List.of(
                new ContextProvider.Document("one two three four five six", "a.md", 1.0),
                new ContextProvider.Document("short", "b.md", 1.0));

        var chunks = RagChunker.chunkAll(documents, 12, 3);

        assertTrue(chunks.size() > documents.size());
        assertEquals("b.md", chunks.get(chunks.size() - 1).source());
    }

    @Test
    void rejectsInvalidChunkSizes() {
        var document = new ContextProvider.Document("content", "doc.md", 1.0);

        assertThrows(IllegalArgumentException.class, () -> RagChunker.chunk(document, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> RagChunker.chunk(document, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> RagChunker.chunk(document, 10, -1));
    }
}
