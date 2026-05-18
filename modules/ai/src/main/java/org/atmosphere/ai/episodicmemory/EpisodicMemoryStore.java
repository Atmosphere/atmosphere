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
package org.atmosphere.ai.episodicmemory;

import java.nio.file.Path;
import java.util.List;

/**
 * SPI for cross-conversation long-term memory. Distinct from
 * {@link org.atmosphere.ai.AiConversationMemory} (per-conversation chat
 * history) and {@code CheckpointStore} (workflow snapshots) — episodic
 * memories are typed, deliberately stored facts that survive across
 * sessions and inform future runs of any agent that holds the same store.
 *
 * <p>Two built-in implementations cover the common deployment shapes:</p>
 * <ul>
 *   <li>{@link InMemoryEpisodicMemoryStore} — process-local, fast, lost on
 *       restart. Default for tests and ephemeral demos.</li>
 *   <li>{@link JsonFileEpisodicMemoryStore} — durable across restarts,
 *       serialized via Jackson 3 to a single JSON document.</li>
 * </ul>
 *
 * <p>Custom backends (sqlite / redis / pgvector) plug in by implementing
 * this interface; the framework does not require a {@link java.util.ServiceLoader}
 * registration — callers wire their store directly into the pipeline or the
 * tool layer that consumes it.</p>
 */
public interface EpisodicMemoryStore {

    /**
     * Persist the given entry. Re-storing an entry with an existing id
     * replaces the prior copy.
     *
     * @param entry the memory to persist; never {@code null}
     */
    void store(MemoryEntry entry);

    /**
     * Return matching entries, most-recent first, bounded by
     * {@link EpisodicMemoryQuery#limit}.
     *
     * @param query filter expression; never {@code null}
     * @return immutable list of matches in newest-first order; never {@code null}
     */
    List<MemoryEntry> recall(EpisodicMemoryQuery query);

    /**
     * Remove the entry with the given id. Returns {@code true} if an entry
     * was removed, {@code false} when the id was not present.
     */
    boolean forget(String id);

    /** @return the total number of entries currently held by the store. */
    int size();

    /** Construct an in-memory store backed by a thread-safe list. */
    static EpisodicMemoryStore inMemory() {
        return new InMemoryEpisodicMemoryStore();
    }

    /**
     * Construct a JSON-file store at the given path. The file is loaded
     * lazily on the first read or write and rewritten on every mutation.
     *
     * @param path filesystem location for the JSON document
     * @return a file-backed store; never {@code null}
     */
    static EpisodicMemoryStore jsonFile(Path path) {
        return new JsonFileEpisodicMemoryStore(path);
    }
}
