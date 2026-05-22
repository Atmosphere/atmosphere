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
package org.atmosphere.ai.memory;

import java.util.List;

/**
 * SPI for persistent user facts across sessions. Stores key observations
 * about a user (preferences, context, relationships) so agents can recall
 * them in future conversations.
 *
 * <p>Retrieval is recency-ordered ({@link #getFacts(String, int)} returns
 * the most recent {@code max} facts). For relevance-ranked retrieval over
 * past conversations, compose with
 * {@link SemanticRecallInterceptor} and a user-supplied
 * {@link org.atmosphere.ai.ContextProvider}.</p>
 *
 * <p>The only implementation shipped in-tree is
 * {@link InMemoryLongTermMemory}; production deployments are expected to
 * provide their own implementation backed by their preferred store
 * (database, key-value store, vector store, etc.).</p>
 */
public interface LongTermMemory {

    /**
     * Save a fact about a user.
     *
     * @param userId the user identifier
     * @param fact   a concise factual statement (e.g., "Has a dog named Max, golden retriever")
     */
    void saveFact(String userId, String fact);

    /**
     * Save multiple facts for a user.
     *
     * @param userId the user identifier
     * @param facts  the facts to store
     */
    default void saveFacts(String userId, List<String> facts) {
        for (var fact : facts) {
            saveFact(userId, fact);
        }
    }

    /**
     * Retrieve stored facts for a user, most recent first.
     *
     * @param userId   the user identifier
     * @param maxFacts maximum number of facts to return
     * @return the stored facts, or an empty list if none
     */
    List<String> getFacts(String userId, int maxFacts);

    /**
     * Clear all stored facts for a user (e.g., "forget me" request).
     *
     * @param userId the user identifier
     */
    void clear(String userId);
}
