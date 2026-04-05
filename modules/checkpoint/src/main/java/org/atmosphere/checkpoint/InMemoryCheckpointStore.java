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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Default {@link CheckpointStore} backed by in-memory
 * {@link ConcurrentHashMap}s. Suitable for development, tests, and single-JVM
 * deployments where durability across restarts is not required.
 *
 * <p>Enforces a maximum snapshot count to prevent unbounded memory growth.
 * When the limit is exceeded, the oldest snapshots (by {@code createdAt}) are
 * evicted. The default cap is {@value #DEFAULT_MAX_SNAPSHOTS}.</p>
 */
public final class InMemoryCheckpointStore implements CheckpointStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryCheckpointStore.class);

    public static final int DEFAULT_MAX_SNAPSHOTS = 10_000;

    private final int maxSnapshots;
    private final ConcurrentHashMap<CheckpointId, WorkflowSnapshot<?>> snapshots = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<CheckpointListener> listeners = new CopyOnWriteArrayList<>();

    public InMemoryCheckpointStore() {
        this(DEFAULT_MAX_SNAPSHOTS);
    }

    public InMemoryCheckpointStore(int maxSnapshots) {
        if (maxSnapshots <= 0) {
            throw new IllegalArgumentException("maxSnapshots must be positive, got " + maxSnapshots);
        }
        this.maxSnapshots = maxSnapshots;
    }

    @Override
    public void start() {
        // no-op for in-memory
    }

    @Override
    public void stop() {
        snapshots.clear();
        listeners.clear();
    }

    @Override
    public <S> WorkflowSnapshot<S> save(WorkflowSnapshot<S> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        snapshots.put(snapshot.id(), snapshot);
        evictIfNeeded();
        dispatch(new CheckpointEvent.Saved(snapshot.id(), snapshot.coordinationId(), Instant.now()));
        return snapshot;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S> Optional<WorkflowSnapshot<S>> load(CheckpointId id) {
        Objects.requireNonNull(id, "id must not be null");
        var snapshot = (WorkflowSnapshot<S>) snapshots.get(id);
        if (snapshot != null) {
            dispatch(new CheckpointEvent.Loaded(id, snapshot.coordinationId(), Instant.now()));
        }
        return Optional.ofNullable(snapshot);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S> WorkflowSnapshot<S> fork(CheckpointId sourceId, S newState) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        var source = (WorkflowSnapshot<S>) snapshots.get(sourceId);
        if (source == null) {
            throw new IllegalArgumentException("Unknown source checkpoint: " + sourceId);
        }
        var forked = WorkflowSnapshot.<S>builder()
                .id(CheckpointId.random())
                .parentId(sourceId)
                .coordinationId(source.coordinationId())
                .agentName(source.agentName())
                .state(newState)
                .metadata(source.metadata())
                .createdAt(Instant.now())
                .build();
        snapshots.put(forked.id(), forked);
        evictIfNeeded();
        dispatch(new CheckpointEvent.Forked(forked.id(), sourceId, forked.coordinationId(), Instant.now()));
        return forked;
    }

    @Override
    public List<WorkflowSnapshot<?>> list(CheckpointQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        Stream<WorkflowSnapshot<?>> stream = snapshots.values().stream();

        if (query.coordinationId() != null) {
            stream = stream.filter(s -> query.coordinationId().equals(s.coordinationId()));
        }
        if (query.agentName() != null) {
            stream = stream.filter(s -> query.agentName().equals(s.agentName()));
        }
        if (query.since() != null) {
            stream = stream.filter(s -> !s.createdAt().isBefore(query.since()));
        }
        if (query.until() != null) {
            stream = stream.filter(s -> !s.createdAt().isAfter(query.until()));
        }

        // Stable ordering: oldest first, then by id as a tiebreaker.
        stream = stream.sorted(
                java.util.Comparator.comparing(WorkflowSnapshot<?>::createdAt)
                        .thenComparing(s -> s.id().value()));

        if (query.limit() > 0) {
            stream = stream.limit(query.limit());
        }
        return stream.toList();
    }

    @Override
    public boolean delete(CheckpointId id) {
        Objects.requireNonNull(id, "id must not be null");
        var removed = snapshots.remove(id);
        if (removed != null) {
            dispatch(new CheckpointEvent.Deleted(id, removed.coordinationId(), Instant.now()));
            return true;
        }
        return false;
    }

    @Override
    public int deleteCoordination(String coordinationId) {
        Objects.requireNonNull(coordinationId, "coordinationId must not be null");
        int removed = 0;
        for (var entry : snapshots.entrySet()) {
            if (coordinationId.equals(entry.getValue().coordinationId())
                    && snapshots.remove(entry.getKey(), entry.getValue())) {
                dispatch(new CheckpointEvent.Deleted(entry.getKey(), coordinationId, Instant.now()));
                removed++;
            }
        }
        return removed;
    }

    @Override
    public void addListener(CheckpointListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
    }

    @Override
    public void removeListener(CheckpointListener listener) {
        listeners.remove(listener);
    }

    /** Current number of snapshots held (exposed for testing + diagnostics). */
    public int size() {
        return snapshots.size();
    }

    private void dispatch(CheckpointEvent event) {
        for (var listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException ex) {
                LOGGER.warn("CheckpointListener {} threw an exception handling {}",
                        listener.getClass().getName(), event, ex);
            }
        }
    }

    private void evictIfNeeded() {
        if (snapshots.size() <= maxSnapshots) {
            return;
        }
        // Snapshot the entries, sort by createdAt ascending, evict oldest
        // until within bounds. Concurrent saves during eviction are tolerated:
        // we only remove entries whose value has not changed since we observed
        // it, via remove(key, value).
        var ordered = snapshots.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByValue(
                        java.util.Comparator.comparing(WorkflowSnapshot<?>::createdAt)))
                .toList();
        int overflow = snapshots.size() - maxSnapshots;
        for (var entry : ordered) {
            if (overflow <= 0) {
                break;
            }
            if (snapshots.remove(entry.getKey(), entry.getValue())) {
                overflow--;
            }
        }
    }
}
