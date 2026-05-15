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

import java.util.List;

/**
 * SPI for RAG (Retrieval-Augmented Generation) context augmentation.
 * Implementations retrieve relevant documents/context to augment the
 * user's message before it reaches the LLM.
 *
 * <p>This is a thin wrapper designed to bridge framework-specific retrievers:</p>
 * <ul>
 *   <li>Spring AI: {@code QuestionAnswerAdvisor} / content retrievers</li>
 *   <li>LangChain4j: {@code ContentRetriever} / {@code EmbeddingStoreContentRetriever}</li>
 *   <li>Direct vector store clients (Pinecone, Weaviate, pgvector, etc.)</li>
 * </ul>
 *
 * <p>Wired into the interceptor chain via {@link AiInterceptor#preProcess}:</p>
 * <pre>
 * Guardrails (pre) → Rate Limit → RAG (ContextProvider) → [LLM call] → ...
 * </pre>
 *
 * <p>Example:</p>
 * <pre>{@code
 * public class ProductDocsProvider implements ContextProvider {
 *     private final VectorStore store;
 *
 *     @Override
 *     public List<Document> retrieve(String query, int maxResults) {
 *         return store.similaritySearch(query, maxResults);
 *     }
 * }
 * }</pre>
 */
public interface ContextProvider {

    /**
     * Retrieve relevant documents for the given query.
     *
     * @param query      the user's message or search query
     * @param maxResults maximum number of documents to return
     * @return matching documents, ordered by relevance (most relevant first)
     */
    List<Document> retrieve(String query, int maxResults);

    /**
     * Transform the user's query before retrieval. Override to implement
     * query rewriting strategies such as multi-query, HyDE (Hypothetical
     * Document Embeddings), or step-back prompting.
     *
     * <p>The default returns the query unchanged.</p>
     *
     * @param originalQuery the user's original message
     * @return the transformed query to use for retrieval
     */
    default String transformQuery(String originalQuery) {
        return originalQuery;
    }

    /**
     * Filter retrieved documents before reranking. Override to apply score
     * thresholds, tenant filters, metadata predicates, or safety filters.
     *
     * @param query     the normalized query used for retrieval
     * @param documents the initially retrieved documents
     * @return filtered documents
     */
    default List<Document> filter(String query, List<Document> documents) {
        return safeDocuments(documents);
    }

    /**
     * Re-rank retrieved documents after initial retrieval. Override to
     * implement cross-encoder reranking, LLM-based reranking, or
     * reciprocal rank fusion across multiple retrievers.
     *
     * <p>The default returns documents unchanged.</p>
     *
     * @param query     the query used for retrieval
     * @param documents the initially retrieved documents
     * @return the re-ranked document list
     */
    default List<Document> rerank(String query, List<Document> documents) {
        return safeDocuments(documents);
    }

    /**
     * Post-process retrieved documents after filtering and reranking. Override
     * to merge adjacent chunks, truncate context, deduplicate sources, or enrich
     * attribution metadata before prompt injection.
     *
     * @param query     the normalized query used for retrieval
     * @param documents the filtered and reranked documents
     * @return documents to inject into the prompt
     */
    default List<Document> postProcess(String query, List<Document> documents) {
        return safeDocuments(documents);
    }

    /**
     * Ingest documents into the backing store. Only supported by providers
     * that manage their own document store (not read-only bridges).
     *
     * <p>The default throws {@link UnsupportedOperationException}.</p>
     *
     * @param documents the documents to ingest
     * @throws UnsupportedOperationException if this provider is read-only
     */
    default void ingest(List<Document> documents) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " is read-only and does not support ingestion");
    }

    /**
     * Whether this provider is available and properly configured.
     * Used by {@link java.util.ServiceLoader} auto-discovery to filter out
     * providers whose backing store is not present.
     *
     * @return true if the provider can serve requests (default: true)
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Normalize a retrieval query at the SPI boundary.
     *
     * @param query raw or transformed query
     * @return trimmed query, or the empty string for null input
     */
    static String normalizeQuery(String query) {
        return query == null ? "" : query.trim();
    }

    /**
     * Whether a normalized query should be sent to a retriever.
     *
     * @param query raw or transformed query
     * @return true when retrieval should proceed
     */
    static boolean shouldRetrieve(String query) {
        return !normalizeQuery(query).isBlank();
    }

    /**
     * Format citation metadata consistently for prompt injection.
     *
     * @param document retrieved document
     * @return source attribution string
     */
    static String formatCitation(Document document) {
        var metadata = document.metadata() == null ? java.util.Map.<String, String>of() : document.metadata();
        var source = firstNonBlank(document.source(), metadata.get("source"), metadata.get("source_document"), "unknown");
        var sourceDocument = metadata.get("source_document");
        var chunkIndex = metadata.get("chunk_index");
        var chunkCount = metadata.get("chunk_count");
        var chunkStart = metadata.get("chunk_start");
        var chunkEnd = metadata.get("chunk_end");

        var citation = new StringBuilder(source);
        if (sourceDocument != null && !sourceDocument.isBlank() && !sourceDocument.equals(source)) {
            citation.append(" (document: ").append(sourceDocument).append(')');
        }
        if (chunkIndex != null && !chunkIndex.isBlank()) {
            citation.append(" chunk ").append(chunkIndex);
            if (chunkCount != null && !chunkCount.isBlank()) {
                citation.append('/').append(chunkCount);
            }
        }
        if (chunkStart != null && chunkEnd != null && !chunkStart.isBlank() && !chunkEnd.isBlank()) {
            citation.append(" chars ").append(chunkStart).append('-').append(chunkEnd);
        }
        citation.append(" score ").append(String.format(java.util.Locale.ROOT, "%.3f", document.score()));
        return citation.toString();
    }

    private static List<Document> safeDocuments(List<Document> documents) {
        return documents == null ? List.of() : documents;
    }

    private static String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "unknown";
    }

    /**
     * A retrieved document with content and metadata.
     *
     * @param content  the document text
     * @param source   source identifier (URL, file path, etc.)
     * @param score    relevance score (0.0 to 1.0, higher is more relevant)
     * @param metadata additional metadata
     */
    record Document(
            String content,
            String source,
            double score,
            java.util.Map<String, String> metadata
    ) {
        public Document(String content, String source, double score) {
            this(content, source, score, java.util.Map.of());
        }
    }
}
