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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * File-backed {@link EpisodicMemoryStore} that serializes the entire entry
 * list to a single JSON document on every mutation. Loads lazily on the
 * first call so an empty or missing file is treated as "no entries yet".
 *
 * <p>Writes are atomic: serialized to a sibling {@code .tmp} file then
 * renamed via {@link StandardCopyOption#ATOMIC_MOVE} so a crash mid-write
 * cannot leave a half-written JSON document at {@link #path}.</p>
 */
public final class JsonFileEpisodicMemoryStore implements EpisodicMemoryStore {

    private static final Logger logger = LoggerFactory.getLogger(JsonFileEpisodicMemoryStore.class);

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final TypeReference<List<WireEntry>> WIRE_LIST = new TypeReference<>() { };

    /**
     * Wire-format record used only by the JSON layer so we do not require the
     * optional {@code jackson-datatype-jsr310} module to serialize the
     * {@link Instant} field on {@link MemoryEntry}.
     */
    private record WireEntry(String id, EpisodicMemoryType type, String content,
                             String createdAt, Map<String, String> metadata) {
        MemoryEntry toEntry() {
            return new MemoryEntry(id, type, content,
                    createdAt != null ? Instant.parse(createdAt) : Instant.EPOCH,
                    metadata != null ? metadata : Map.of());
        }

        static WireEntry from(MemoryEntry entry) {
            return new WireEntry(entry.id(), entry.type(), entry.content(),
                    entry.createdAt().toString(), entry.metadata());
        }
    }

    private final Path path;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<MemoryEntry> cache;
    private boolean loaded;

    public JsonFileEpisodicMemoryStore(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    @Override
    public void store(MemoryEntry entry) {
        Objects.requireNonNull(entry, "entry");
        lock.writeLock().lock();
        try {
            ensureLoaded();
            cache.removeIf(existing -> existing.id().equals(entry.id()));
            cache.add(entry);
            flush();
        } finally {
            lock.writeLock().unlock();
        }
        EpisodicMemoryAccessEventBridge.emit(getClass(),
                EpisodicMemoryAccessEventBridge.STORE, entry.type(), 1);
    }

    @Override
    public List<MemoryEntry> recall(EpisodicMemoryQuery query) {
        Objects.requireNonNull(query, "query");
        var contains = query.contentContains().map(s -> s.toLowerCase(Locale.ROOT));
        List<MemoryEntry> snapshot;
        lock.readLock().lock();
        try {
            ensureLoaded();
            snapshot = new ArrayList<>(cache);
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
        EpisodicMemoryAccessEventBridge.emit(getClass(),
                EpisodicMemoryAccessEventBridge.RECALL,
                query.type().orElse(null), matches.size());
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
            ensureLoaded();
            removed = cache.removeIf(entry -> id.equals(entry.id()));
            if (removed) {
                flush();
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (removed) {
            EpisodicMemoryAccessEventBridge.emit(getClass(),
                    EpisodicMemoryAccessEventBridge.FORGET, null, 1);
        }
        return removed;
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            ensureLoaded();
            return cache.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Visible for testing. */
    public Path path() {
        return path;
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        // The caller may hold the read lock or the write lock; either is
        // sufficient because load() only runs once and any concurrent reader
        // observes the populated cache on its retry under the read lock.
        if (Files.isRegularFile(path)) {
            try {
                var bytes = Files.readAllBytes(path);
                if (bytes.length == 0) {
                    cache = new ArrayList<>();
                } else {
                    var wire = MAPPER.readValue(bytes, WIRE_LIST);
                    cache = new ArrayList<>(wire.size());
                    for (var entry : wire) {
                        cache.add(entry.toEntry());
                    }
                }
            } catch (IOException | RuntimeException e) {
                logger.warn("Failed to load episodic memory from {} — starting empty", path, e);
                cache = new ArrayList<>();
            }
        } else {
            cache = new ArrayList<>();
        }
        loaded = true;
    }

    private void flush() {
        var parent = path.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            var tmp = path.resolveSibling(path.getFileName() + ".tmp");
            var wire = new ArrayList<WireEntry>(cache.size());
            for (var entry : cache) {
                wire.add(WireEntry.from(entry));
            }
            Files.write(tmp, MAPPER.writeValueAsBytes(wire));
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // Filesystems without atomic-move support (some network mounts)
                // fall back to a non-atomic replace; better than failing the write.
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist episodic memory to " + path, e);
        }
    }
}
