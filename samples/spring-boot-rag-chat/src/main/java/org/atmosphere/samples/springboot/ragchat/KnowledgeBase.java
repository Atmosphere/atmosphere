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
package org.atmosphere.samples.springboot.ragchat;

import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.rag.InMemoryContextProvider;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe singleton holding loaded knowledge base documents.
 *
 * <p>Populated at startup by {@link VectorStoreConfig} and accessed by
 * {@link RagAgent}'s {@code @AiTool} methods for explicit document search.</p>
 */
public final class KnowledgeBase {

    private static final KnowledgeBase INSTANCE = new KnowledgeBase();
    private final List<ContextProvider.Document> documents = new CopyOnWriteArrayList<>();

    private KnowledgeBase() {
    }

    public static KnowledgeBase instance() {
        return INSTANCE;
    }

    public void addDocuments(List<ContextProvider.Document> docs) {
        documents.addAll(docs);
    }

    public List<ContextProvider.Document> documents() {
        return List.copyOf(documents);
    }

    /**
     * Find a document by source path. Matches exact path or suffix.
     */
    public ContextProvider.Document findBySource(String source) {
        return documents.stream()
                .filter(d -> d.source().equals(source)
                        || d.source().endsWith("/" + source)
                        || d.source().endsWith(source))
                .findFirst()
                .orElse(null);
    }

    /**
     * Search the knowledge base using word-overlap scoring.
     * Works without embeddings — always available, even in demo mode.
     */
    public List<ContextProvider.Document> search(String query, int maxResults) {
        if (documents.isEmpty()) {
            return List.of();
        }
        var provider = new InMemoryContextProvider(documents);
        return provider.retrieve(query, maxResults);
    }
}
