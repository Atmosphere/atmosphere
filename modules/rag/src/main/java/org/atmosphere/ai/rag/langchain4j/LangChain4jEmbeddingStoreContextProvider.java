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

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.atmosphere.ai.ContextProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Bridges LangChain4j's {@link ContentRetriever} to Atmosphere's {@link ContextProvider}.
 *
 * <p>Uses the static volatile pattern so that the content retriever can be set
 * by Spring auto-configuration or manual wiring while being consumed by the
 * framework-managed {@code @AiEndpoint} infrastructure.</p>
 *
 * <p>Any LangChain4j {@link ContentRetriever} implementation can be used,
 * including {@link dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever}.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * var retriever = EmbeddingStoreContentRetriever.builder()
 *         .embeddingStore(embeddingStore)
 *         .embeddingModel(embeddingModel)
 *         .maxResults(5)
 *         .build();
 * LangChain4jEmbeddingStoreContextProvider.setContentRetriever(retriever);
 * }</pre>
 */
public class LangChain4jEmbeddingStoreContextProvider implements ContextProvider {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jEmbeddingStoreContextProvider.class);

    private static volatile ContentRetriever contentRetriever;

    /**
     * Set the {@link ContentRetriever} to use for retrieval.
     * Typically called during application startup.
     *
     * @param retriever the content retriever instance
     */
    public static void setContentRetriever(ContentRetriever retriever) {
        contentRetriever = retriever;
    }

    @Override
    public List<Document> retrieve(String query, int maxResults) {
        var retriever = contentRetriever;
        if (retriever == null) {
            logger.warn("ContentRetriever not configured; returning empty results");
            return List.of();
        }

        var contents = retriever.retrieve(Query.from(query));

        var results = new ArrayList<Document>();
        int count = 0;
        for (var content : contents) {
            if (count >= maxResults) {
                break;
            }

            var segment = content.textSegment();
            var text = segment.text();
            var score = extractScore(content);

            var metadata = new HashMap<String, String>();
            for (var entry : segment.metadata().toMap().entrySet()) {
                metadata.put(entry.getKey(), String.valueOf(entry.getValue()));
            }

            var source = metadata.getOrDefault("source",
                    metadata.getOrDefault("file_name", "langchain4j"));

            results.add(new Document(text, source, score, metadata));
            count++;
        }

        logger.debug("ContentRetriever returned {} results for query: {}", results.size(), query);
        return List.copyOf(results);
    }

    private static double extractScore(Content content) {
        var scoreObj = content.metadata().get(ContentMetadata.SCORE);
        if (scoreObj instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0;
    }
}
