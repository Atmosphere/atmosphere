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
 * <p>Implementations shipped in-tree:</p>
 * <ul>
 *   <li>{@link InMemoryLongTermMemory} in {@code atmosphere-ai} — facts
 *       lost on restart; suitable for development and testing.</li>
 *   <li>{@code SqliteLongTermMemory} in
 *       {@code atmosphere-durable-sessions-sqlite} — embedded SQLite file,
 *       can share a connection with {@code SqliteSessionStore} and
 *       {@code SqliteConversationPersistence}.</li>
 *   <li>{@code RedisLongTermMemory} in
 *       {@code atmosphere-durable-sessions-redis} — Lettuce-backed LIST
 *       per user, can share a connection with {@code RedisSessionStore}
 *       and {@code RedisConversationPersistence}.</li>
 * </ul>
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

    /**
     * Number of facts currently stored for a user. Used by
     * {@link MemoryConsolidationStrategy} to decide when to consolidate. The
     * default counts via {@link #getFacts}; implementations with a cheaper
     * count (a SQL {@code COUNT}, a Redis {@code LLEN}) should override.
     *
     * @param userId the user identifier
     * @return the stored fact count
     */
    default int factCount(String userId) {
        return getFacts(userId, Integer.MAX_VALUE).size();
    }

    /**
     * Replace a user's entire fact set in one operation — the apply step of
     * {@link MemoryConsolidationStrategy memory consolidation}. The default
     * is {@link #clear} followed by {@link #saveFacts}; implementations that
     * can do this atomically (a transaction, a single key write) should
     * override so a concurrent read never observes an empty store mid-replace
     * (Correctness Invariant #2 — terminal/replace path leaves a consistent
     * state).
     *
     * @param userId the user identifier
     * @param facts  the new fact set, oldest first
     */
    default void replaceFacts(String userId, List<String> facts) {
        clear(userId);
        saveFacts(userId, facts);
    }
}
