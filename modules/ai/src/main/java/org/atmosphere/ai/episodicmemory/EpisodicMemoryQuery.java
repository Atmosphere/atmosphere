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

import java.util.Optional;

/**
 * Filter for {@link EpisodicMemoryStore#recall}. Empty optionals mean
 * "match anything"; {@code limit} bounds the result list (clamped to a
 * minimum of 1).
 *
 * @param type            optional type filter
 * @param contentContains optional case-insensitive substring filter on
 *                        {@link MemoryEntry#content}
 * @param limit           maximum number of entries returned, most recent first
 */
public record EpisodicMemoryQuery(
        Optional<EpisodicMemoryType> type,
        Optional<String> contentContains,
        int limit
) {

    public EpisodicMemoryQuery {
        type = type == null ? Optional.empty() : type;
        contentContains = contentContains == null ? Optional.empty() : contentContains;
        if (limit < 1) {
            limit = 1;
        }
    }

    /** Return the most recent {@code limit} entries of any type. */
    public static EpisodicMemoryQuery recent(int limit) {
        return new EpisodicMemoryQuery(Optional.empty(), Optional.empty(), limit);
    }

    /** Return the most recent {@code limit} entries of the given type. */
    public static EpisodicMemoryQuery ofType(EpisodicMemoryType type, int limit) {
        return new EpisodicMemoryQuery(Optional.ofNullable(type), Optional.empty(), limit);
    }
}
