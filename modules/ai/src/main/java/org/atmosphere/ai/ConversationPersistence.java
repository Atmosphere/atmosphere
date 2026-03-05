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

import java.util.Optional;

/**
 * SPI for persisting conversation data. Implementations provide the
 * storage backend (Redis, SQLite, DurableSession metadata, etc.) while
 * {@link PersistentConversationMemory} handles serialization and the
 * sliding-window logic.
 *
 * <p>This is a deliberately thin interface so that it can be backed by:</p>
 * <ul>
 *   <li>Redis — using the same Lettuce connection as {@code RedisSessionStore}</li>
 *   <li>SQLite — using the same database as {@code SqliteSessionStore}</li>
 *   <li>DurableSession metadata — storing JSON in the session's metadata map</li>
 *   <li>Any key-value store</li>
 * </ul>
 *
 * @see PersistentConversationMemory
 */
public interface ConversationPersistence {

    /**
     * Load serialized conversation data.
     *
     * @param conversationId the conversation identifier
     * @return the stored data, or empty if no data exists
     */
    Optional<String> load(String conversationId);

    /**
     * Save serialized conversation data.
     *
     * @param conversationId the conversation identifier
     * @param data           JSON-serialized conversation history
     */
    void save(String conversationId, String data);

    /**
     * Remove conversation data.
     *
     * @param conversationId the conversation identifier
     */
    void remove(String conversationId);

    /**
     * Whether this persistence implementation is available for use.
     * Used by {@link java.util.ServiceLoader} auto-detection to skip
     * implementations whose backend is not configured (e.g., no Redis URL).
     *
     * @return {@code true} if this implementation is ready to use
     */
    default boolean isAvailable() {
        return true;
    }
}
