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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory implementation of {@link LongTermMemory}. Facts are lost on restart.
 * Suitable for development and testing. For production, use a
 * {@code SessionStore}-backed implementation.
 */
public class InMemoryLongTermMemory implements LongTermMemory {

    private final ConcurrentMap<String, UserFacts> facts = new ConcurrentHashMap<>();
    private final int maxFacts;

    public InMemoryLongTermMemory(int maxFacts) {
        this.maxFacts = maxFacts;
    }

    public InMemoryLongTermMemory() {
        this(100);
    }

    @Override
    public void saveFact(String userId, String fact) {
        var uf = facts.computeIfAbsent(userId, k -> new UserFacts());
        uf.lock.lock();
        try {
            uf.entries.add(fact);
            while (uf.entries.size() > maxFacts) {
                uf.entries.removeFirst();
            }
        } finally {
            uf.lock.unlock();
        }
    }

    @Override
    public List<String> getFacts(String userId, int max) {
        var uf = facts.get(userId);
        if (uf == null) {
            return List.of();
        }
        uf.lock.lock();
        try {
            var end = uf.entries.size();
            var start = Math.max(0, end - max);
            return List.copyOf(uf.entries.subList(start, end));
        } finally {
            uf.lock.unlock();
        }
    }

    private static final class UserFacts {
        final ReentrantLock lock = new ReentrantLock();
        final List<String> entries = new ArrayList<>();
    }

    @Override
    public void clear(String userId) {
        facts.remove(userId);
    }
}
