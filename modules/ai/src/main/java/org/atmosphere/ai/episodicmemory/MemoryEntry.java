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

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One persisted memory in an {@link EpisodicMemoryStore}. {@code id} is
 * stable across reads and writes; {@code createdAt} is set by the factory
 * helpers when callers do not supply one.
 *
 * @param id        unique identifier within the store
 * @param type      classification (see {@link EpisodicMemoryType})
 * @param content   the memory body
 * @param createdAt UTC instant the memory was first stored
 * @param metadata  arbitrary string-keyed metadata; never {@code null}
 */
public record MemoryEntry(
        String id,
        EpisodicMemoryType type,
        String content,
        Instant createdAt,
        Map<String, String> metadata
) {

    public MemoryEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * Convenience factory that generates an id + timestamp.
     *
     * @param type    classification of the new memory
     * @param content the memory body
     * @return a freshly stamped entry
     */
    public static MemoryEntry of(EpisodicMemoryType type, String content) {
        return new MemoryEntry(UUID.randomUUID().toString(), type, content,
                Instant.now(), Map.of());
    }

    /** Convenience factory that generates an id + timestamp and carries metadata. */
    public static MemoryEntry of(EpisodicMemoryType type, String content,
                                 Map<String, String> metadata) {
        return new MemoryEntry(UUID.randomUUID().toString(), type, content,
                Instant.now(), metadata);
    }
}
