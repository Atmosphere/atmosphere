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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Process-local {@link EpisodicMemoryStore} backed by an {@link ArrayList}
 * guarded by a read/write lock. Suitable for tests, ephemeral demos, and
 * any deployment that does not need cross-restart persistence.
 */
public final class InMemoryEpisodicMemoryStore implements EpisodicMemoryStore {

    private final List<MemoryEntry> entries = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public void store(MemoryEntry entry) {
        Objects.requireNonNull(entry, "entry");
        lock.writeLock().lock();
        try {
            entries.removeIf(existing -> existing.id().equals(entry.id()));
            entries.add(entry);
        } finally {
            lock.writeLock().unlock();
        }
        emit(EpisodicMemoryAccessEventBridge.STORE, entry.type(), 1);
    }

    @Override
    public List<MemoryEntry> recall(EpisodicMemoryQuery query) {
        Objects.requireNonNull(query, "query");
        var contains = query.contentContains().map(s -> s.toLowerCase(Locale.ROOT));
        List<MemoryEntry> snapshot;
        lock.readLock().lock();
        try {
            snapshot = new ArrayList<>(entries);
        } finally {
            lock.readLock().unlock();
        }
        var matches = snapshot.stream()
                .filter(entry -> query.type().map(t -> t == entry.type()).orElse(true))
                .filter(entry -> contains.map(needle ->
                        entry.content().toLowerCase(Locale.ROOT).contains(needle)).orElse(true))
                .sorted(Comparator.comparing(MemoryEntry::createdAt).reversed())
                .limit(query.limit())
                .toList();
        emit(EpisodicMemoryAccessEventBridge.RECALL, query.type().orElse(null), matches.size());
        return matches;
    }

    @Override
    public boolean forget(String id) {
        if (id == null) {
            return false;
        }
        boolean removed;
        lock.writeLock().lock();
        try {
            removed = entries.removeIf(entry -> id.equals(entry.id()));
        } finally {
            lock.writeLock().unlock();
        }
        if (removed) {
            emit(EpisodicMemoryAccessEventBridge.FORGET, null, 1);
        }
        return removed;
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return entries.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    private void emit(String operation, EpisodicMemoryType type, int count) {
        EpisodicMemoryAccessEventBridge.emit(getClass(), operation, type, count);
    }
}
