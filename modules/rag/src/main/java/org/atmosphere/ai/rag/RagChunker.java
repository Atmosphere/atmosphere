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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Small text chunking utility for RAG ingestion.
 */
public final class RagChunker {

    public static final int DEFAULT_MAX_CHARS = 1_200;
    public static final int DEFAULT_OVERLAP_CHARS = 150;

    private RagChunker() {
    }

    /**
     * Split a document into retrievable chunks using the default production-safe size.
     *
     * @param document document to chunk
     * @return the original document when it already fits, otherwise source-attributed chunks
     */
    public static List<ContextProvider.Document> chunk(ContextProvider.Document document) {
        return chunk(document, DEFAULT_MAX_CHARS, DEFAULT_OVERLAP_CHARS);
    }

    /**
     * Split many documents into retrievable chunks using the default size.
     *
     * @param documents documents to chunk
     * @return chunked documents
     */
    public static List<ContextProvider.Document> chunkAll(Collection<ContextProvider.Document> documents) {
        return chunkAll(documents, DEFAULT_MAX_CHARS, DEFAULT_OVERLAP_CHARS);
    }

    /**
     * Split many documents into retrievable chunks.
     *
     * @param documents documents to chunk
     * @param maxChars maximum characters per chunk
     * @param overlapChars characters to overlap between adjacent chunks
     * @return chunked documents
     */
    public static List<ContextProvider.Document> chunkAll(
            Collection<ContextProvider.Document> documents, int maxChars, int overlapChars) {
        Objects.requireNonNull(documents, "documents");
        var chunks = new ArrayList<ContextProvider.Document>();
        for (var document : documents) {
            chunks.addAll(chunk(document, maxChars, overlapChars));
        }
        return List.copyOf(chunks);
    }

    /**
     * Split a document into retrievable chunks.
     *
     * @param document document to chunk
     * @param maxChars maximum characters per chunk
     * @param overlapChars characters to overlap between adjacent chunks
     * @return the original document when it already fits, otherwise source-attributed chunks
     */
    public static List<ContextProvider.Document> chunk(
            ContextProvider.Document document, int maxChars, int overlapChars) {
        Objects.requireNonNull(document, "document");
        validate(maxChars, overlapChars);

        var content = Objects.requireNonNull(document.content(), "document.content");
        var source = normalizeSource(document.source());
        if (content.isBlank() || content.length() <= maxChars) {
            return List.of(document);
        }

        record Span(int start, int end, String text) { }

        var spans = new ArrayList<Span>();
        int start = 0;
        while (start < content.length()) {
            var end = Math.min(content.length(), start + maxChars);
            if (end < content.length()) {
                end = chooseBreak(content, start, end);
            }
            var text = content.substring(start, end).trim();
            if (!text.isEmpty()) {
                spans.add(new Span(start, end, text));
            }
            if (end >= content.length()) {
                break;
            }
            var nextStart = Math.max(0, end - overlapChars);
            while (nextStart < end && Character.isWhitespace(content.charAt(nextStart))) {
                nextStart++;
            }
            start = nextStart > start ? nextStart : end;
        }

        var result = new ArrayList<ContextProvider.Document>(spans.size());
        for (int i = 0; i < spans.size(); i++) {
            var span = spans.get(i);
            var metadata = new HashMap<>(document.metadata());
            metadata.put("source_document", source);
            metadata.put("chunk_index", Integer.toString(i + 1));
            metadata.put("chunk_count", Integer.toString(spans.size()));
            metadata.put("chunk_start", Integer.toString(span.start()));
            metadata.put("chunk_end", Integer.toString(span.end()));
            result.add(new ContextProvider.Document(
                    span.text(),
                    source + "#chunk-" + (i + 1),
                    document.score(),
                    Map.copyOf(metadata)));
        }
        return List.copyOf(result);
    }

    private static int chooseBreak(String content, int start, int limit) {
        var min = start + Math.max(1, (limit - start) / 2);
        var paragraph = content.lastIndexOf("\n\n", limit);
        if (paragraph >= min) {
            return paragraph;
        }
        for (int i = limit; i >= min; i--) {
            if (Character.isWhitespace(content.charAt(i - 1))) {
                return i;
            }
        }
        return limit;
    }

    private static void validate(int maxChars, int overlapChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        if (overlapChars < 0 || overlapChars >= maxChars) {
            throw new IllegalArgumentException("overlapChars must be non-negative and smaller than maxChars");
        }
    }

    private static String normalizeSource(String source) {
        return source == null || source.isBlank() ? "document" : source;
    }
}
