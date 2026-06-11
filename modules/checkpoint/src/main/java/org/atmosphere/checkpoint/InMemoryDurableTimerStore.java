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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory {@link DurableTimerStore}. The map IS the durability
 * boundary within one JVM: a {@link DurableTimerService} that crashes and is
 * recreated over the same store instance re-arms its timers. For survival across
 * a full JVM restart, back the service with a database-backed store.
 */
public final class InMemoryDurableTimerStore implements DurableTimerStore {

    private final ConcurrentHashMap<String, DurableTimer> timers = new ConcurrentHashMap<>();

    @Override
    public void save(DurableTimer timer) {
        timers.put(timer.id(), timer);
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
