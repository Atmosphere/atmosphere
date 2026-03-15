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

import org.atmosphere.ai.ContextProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Bridges Spring AI's {@link VectorStore} to Atmosphere's {@link ContextProvider}.
 *
 * <p>Uses the static volatile pattern so that the vector store can be set by
 * Spring auto-configuration while being consumed by the framework-managed
 * {@code @AiEndpoint} infrastructure.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * // In Spring configuration
 * SpringAiVectorStoreContextProvider.setVectorStore(vectorStore);
 *
 * // Then register as a ContextProvider
 * var provider = new SpringAiVectorStoreContextProvider();
 * }</pre>
 */
public class SpringAiVectorStoreContextProvider implements ContextProvider {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiVectorStoreContextProvider.class);

    private static volatile VectorStore vectorStore;

    /**
     * Set the {@link VectorStore} to use for similarity search.
     * Typically called by {@link AtmosphereRagAutoConfiguration}.
     *
     * @param store the vector store instance
     */
    public static void setVectorStore(VectorStore store) {
        vectorStore = store;
    }

    @Override
    public boolean isAvailable() {
        return vectorStore != null;
    }

    @Override
    public List<Document> retrieve(String query, int maxResults) {
        var store = vectorStore;
        if (store == null) {
            logger.warn("VectorStore not configured; returning empty results");
            return List.of();
        }

        var searchRequest = SearchRequest.builder()
                .query(query)
                .topK(maxResults)
                .build();

        var springDocs = store.similaritySearch(searchRequest);
        var results = new ArrayList<Document>();
        for (var springDoc : springDocs) {
            var metadata = new HashMap<String, String>();
            for (var entry : springDoc.getMetadata().entrySet()) {
                metadata.put(entry.getKey(), String.valueOf(entry.getValue()));
            }

            var score = springDoc.getScore() != null ? springDoc.getScore() : 0.0;
            var source = metadata.getOrDefault("source", springDoc.getId());

            results.add(new Document(
                    springDoc.getText(),
                    source,
                    score,
                    metadata));
        }

        logger.debug("VectorStore returned {} results for query: {}", results.size(), query);
        return List.copyOf(results);
    }
}
