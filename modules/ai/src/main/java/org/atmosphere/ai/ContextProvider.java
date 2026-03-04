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
