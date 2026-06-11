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
package org.atmosphere.ai.devinspector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Default in-memory {@link DevInspectorRecorder}: a bounded ring buffer of the
 * most recent turns (oldest evicted past {@code capacity}, Invariant #3).
 * Thread-safe; dev-only, never a production default.
 */
public final class InMemoryDevInspectorRecorder implements DevInspectorRecorder {

    private static final int DEFAULT_CAPACITY = 100;

    private final ArrayDeque<DevInspectorEntry> entries = new ArrayDeque<>();
    private final int capacity;

    public InMemoryDevInspectorRecorder() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryDevInspectorRecorder(int capacity) {
        this.capacity = capacity > 0 ? capacity : DEFAULT_CAPACITY;
    }

    @Override
    public synchronized void record(DevInspectorEntry entry) {
        if (entry == null) {
            return;
        }
        entries.addLast(entry);
        while (entries.size() > capacity) {
            entries.removeFirst();
        }
    }

    @Override
    public synchronized List<DevInspectorEntry> recent(int limit) {
        var snapshot = new ArrayList<>(entries);
        Collections.reverse(snapshot);
        if (limit > 0 && snapshot.size() > limit) {
            return List.copyOf(snapshot.subList(0, limit));
        }
        return List.copyOf(snapshot);
    }

    @Override
    public synchronized int size() {
        return entries.size();
    }

    @Override
    public synchronized void clear() {
        entries.clear();
    }
}
