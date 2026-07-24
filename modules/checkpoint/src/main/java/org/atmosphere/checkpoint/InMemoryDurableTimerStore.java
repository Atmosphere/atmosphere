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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * Default in-memory {@link DurableTimerStore}. The map IS the durability
 * boundary within one JVM: a {@link DurableTimerService} that crashes and is
 * recreated over the same store instance re-arms its timers. For survival across
 * a full JVM restart, back the service with a database-backed store such as
 * {@link SqliteDurableTimerStore}.
 *
 * <h2>Backpressure (Correctness Invariant #3)</h2>
 *
 * The map is bounded at {@link #DEFAULT_MAX_TIMERS} (override via the
 * {@code (int)} constructor). Armed timers are all live — none is terminal, so
 * there is nothing safe to evict; a genuinely new timer past the cap is
 * therefore <em>rejected</em> with a {@link RejectedExecutionException} rather
 * than silently dropping a scheduled fire. Re-saving an existing id is an
 * update and always allowed.
 */
public final class InMemoryDurableTimerStore implements DurableTimerStore {

    /** Default cap on concurrently armed timers (a new timer past it is rejected). */
    public static final int DEFAULT_MAX_TIMERS = 100_000;

    private final ConcurrentHashMap<String, DurableTimer> timers = new ConcurrentHashMap<>();
    private final int maxTimers;
    private final Object insertGuard = new Object();

    /** Create a store bounded at {@link #DEFAULT_MAX_TIMERS}. */
    public InMemoryDurableTimerStore() {
        this(DEFAULT_MAX_TIMERS);
    }

    /** Create a store bounded at {@code maxTimers} armed timers. */
    public InMemoryDurableTimerStore(int maxTimers) {
        if (maxTimers <= 0) {
            throw new IllegalArgumentException("maxTimers must be > 0, got " + maxTimers);
        }
        this.maxTimers = maxTimers;
    }

    @Override
    public void save(DurableTimer timer) {
        Objects.requireNonNull(timer, "timer");
        // Serialize only the cap check + insert of a NEW id so the bound is
        // exact; a replace-by-id never grows the map and needs no guard.
        synchronized (insertGuard) {
            if (!timers.containsKey(timer.id()) && timers.size() >= maxTimers) {
                throw new RejectedExecutionException("Durable-timer cap exceeded (maxTimers="
                        + maxTimers + "); rejecting new timer " + timer.id()
                        + " rather than dropping an armed timer");
            }
            timers.put(timer.id(), timer);
        }
    }

    @Override
    public List<DurableTimer> all() {
        return List.copyOf(timers.values());
    }

    @Override
    public boolean remove(String id) {
        return timers.remove(id) != null;
    }

    @Override
    public String name() {
        return "in-memory";
    }
}
