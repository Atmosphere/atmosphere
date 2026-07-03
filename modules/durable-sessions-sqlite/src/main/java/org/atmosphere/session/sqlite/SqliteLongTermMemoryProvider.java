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
package org.atmosphere.session.sqlite;

import org.atmosphere.ai.memory.LongTermMemory;
import org.atmosphere.ai.memory.LongTermMemoryProvider;

/**
 * {@link LongTermMemoryProvider} that makes long-term memory durable the
 * moment this jar is on the classpath: the deep-agent preset's resolution
 * chain ({@code LongTermMemories.resolve}) picks it over the JVM-lifetime
 * in-memory fallback. Uses {@link SqliteLongTermMemory}'s default database
 * file; apps that need a custom path bridge their own store instead (the
 * {@code org.atmosphere.ai.memory.store} framework property wins over every
 * provider).
 *
 * <p>The store is memoized in a static holder: {@code ServiceLoader}
 * instantiates a fresh provider per lookup and the resolver runs once per
 * AI endpoint, so without memoization every endpoint would open its own
 * SQLite connection to the same file. The single shared store lives for the
 * process lifetime (durability is per-write; no close required on exit).</p>
 */
public class SqliteLongTermMemoryProvider implements LongTermMemoryProvider {

    private static final class Holder {
        private static final SqliteLongTermMemory STORE = new SqliteLongTermMemory();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public LongTermMemory get() {
        return Holder.STORE;
    }

    /** Above the default (0) so durability beats any zero-priority provider. */
    @Override
    public int priority() {
        return 10;
    }
}
