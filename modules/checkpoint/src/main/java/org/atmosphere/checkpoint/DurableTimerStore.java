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
package org.atmosphere.checkpoint;

import java.util.List;

/**
 * Durable storage for {@link DurableTimer}s. The store is the durability
 * boundary: an {@link InMemoryDurableTimerStore} survives only restarts of the
 * {@link DurableTimerService} (re-arm from store), while a database-backed
 * implementation (e.g. over the same JDBC the {@code SqliteCheckpointStore}
 * uses) survives a full JVM restart.
 */
public interface DurableTimerStore {

    /** Persist (or replace by id) a timer. */
    void save(DurableTimer timer);

    /** All currently-armed timers. */
    List<DurableTimer> all();

    /**
     * Atomically remove a timer by id — the <em>claim</em> operation that makes
     * firing exactly-once even under concurrent polls.
     *
     * @return {@code true} if this call removed the timer (i.e. claimed it),
     *         {@code false} if it was already gone
     */
    boolean remove(String id);

    /** Descriptive name for diagnostics. */
    default String name() {
        return getClass().getSimpleName();
    }
}
