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
package org.atmosphere.session.redis;

import org.atmosphere.ai.memory.LongTermMemory;
import org.atmosphere.ai.memory.LongTermMemoryProvider;

/**
 * {@link LongTermMemoryProvider} gated on the {@code REDIS_URL} environment
 * variable — the same runtime-truth signal {@link RedisConversationPersistence}
 * uses: no URL, not available, no connection attempt. When available, the
 * deep-agent preset's resolution chain ({@code LongTermMemories.resolve})
 * prefers it over the SQLite provider (an external shared store beats a
 * node-local file for multi-instance deployments).
 *
 * <p>The store is memoized in a static holder: {@code ServiceLoader}
 * instantiates a fresh provider per lookup and the resolver runs once per
 * AI endpoint, so without memoization every endpoint would open its own
 * Redis connection. The single shared store lives for the process
 * lifetime.</p>
 */
public class RedisLongTermMemoryProvider implements LongTermMemoryProvider {

    private static final class Holder {
        private static final RedisLongTermMemory STORE =
                new RedisLongTermMemory(System.getenv("REDIS_URL"));
    }

    @Override
    public boolean isAvailable() {
        var url = System.getenv("REDIS_URL");
        return url != null && !url.isBlank();
    }

    @Override
    public LongTermMemory get() {
        try {
            return Holder.STORE;
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            // REDIS_URL is set but the connection failed at first use (and, on
            // later lookups, the class stays failed). Returning null makes the
            // resolver log a WARN and fall back instead of failing endpoint
            // registration (Terminal Path Completeness).
            return null;
        }
    }

    /** Above the SQLite provider (10): shared external store wins. */
    @Override
    public int priority() {
        return 20;
    }
}
