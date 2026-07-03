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

/**
 * ServiceLoader SPI for supplying the framework's {@link LongTermMemory}
 * store — the long-term-memory sibling of
 * {@link org.atmosphere.ai.ConversationPersistence}, which durable-store
 * modules already use for conversation history. Register implementations in
 * {@code META-INF/services/org.atmosphere.ai.memory.LongTermMemoryProvider}.
 *
 * <p>Resolution lives in {@link LongTermMemories#resolve}: a framework-property
 * bridged store wins, then the highest-priority available provider, then the
 * zero-dep {@link InMemoryLongTermMemory} fallback.</p>
 */
public interface LongTermMemoryProvider {

    /**
     * Whether this provider can supply a working store right now — e.g. its
     * backing driver is on the classpath and its connection config is present.
     * Must reflect confirmed runtime state, not classpath presence alone.
     *
     * @return {@code true} when {@link #get()} would return a usable store
     */
    boolean isAvailable();

    /**
     * The store this provider supplies. Called only after
     * {@link #isAvailable()} returned {@code true}.
     *
     * @return the long-term memory store
     */
    LongTermMemory get();

    /**
     * Selection priority when multiple providers are available — highest wins.
     *
     * @return the priority, default {@code 0}
     */
    default int priority() {
        return 0;
    }
}
