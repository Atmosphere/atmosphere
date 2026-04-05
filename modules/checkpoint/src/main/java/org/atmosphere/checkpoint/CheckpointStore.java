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
import java.util.Optional;

/**
 * Pluggable persistence for {@link WorkflowSnapshot} instances. Provides the
 * primitives needed for durable execution: <em>save</em> a new snapshot,
 * <em>load</em> it back, <em>fork</em> from any point to branch execution,
 * <em>list</em> existing snapshots matching a filter, and <em>delete</em>
 * when no longer needed.
 *
 * <p>Implementations must be thread-safe. The default in-memory
 * {@link InMemoryCheckpointStore} is provided for development and tests;
 * production deployments should use a persistent backend (JDBC, Redis, etc.)
 * packaged in a separate module.</p>
 *
 * <p>This SPI is deliberately agent-runtime-neutral: application code owns
 * the workflow state type {@code S} and its serialization. Backends that
 * need to serialize to bytes accept the state as opaque and delegate to a
 * caller-supplied serializer.</p>
 */
public interface CheckpointStore {

    /** Start the store (acquire resources). Called once before first use. */
    void start();

    /** Stop the store (release resources). Called once during shutdown. */
    void stop();

    /**
     * Persist the given snapshot. If a snapshot with the same id already
     * exists, it is replaced.
     *
     * @return the snapshot as stored (may be the same instance)
     */
    <S> WorkflowSnapshot<S> save(WorkflowSnapshot<S> snapshot);

    /**
     * Load a previously-saved snapshot by id.
     *
     * @return the snapshot, or {@link Optional#empty()} if not found
     */
    <S> Optional<WorkflowSnapshot<S>> load(CheckpointId id);

    /**
     * Derive a new snapshot from an existing one. The new snapshot has a
     * fresh id, points to {@code sourceId} as its parent, copies
     * coordinationId/agentName/metadata, and carries the supplied state.
     * The new snapshot is persisted before being returned.
     *
     * @throws IllegalArgumentException if {@code sourceId} is unknown
     */
    <S> WorkflowSnapshot<S> fork(CheckpointId sourceId, S newState);

    /**
     * Return all snapshots matching the given filter. The returned list is
     * a point-in-time copy — safe to iterate without external locking.
     */
    List<WorkflowSnapshot<?>> list(CheckpointQuery query);

    /**
     * Delete a snapshot by id.
     *
     * @return {@code true} if a snapshot was removed, {@code false} if none existed
     */
    boolean delete(CheckpointId id);

    /**
     * Delete every snapshot belonging to the given coordination. Useful for
     * cleaning up finished workflows.
     *
     * @return the number of snapshots deleted
     */
    int deleteCoordination(String coordinationId);

    /** Register a listener for lifecycle events. */
    void addListener(CheckpointListener listener);

    /** Unregister a listener previously added via {@link #addListener}. */
    void removeListener(CheckpointListener listener);

    /** No-op store that persists nothing. */
    CheckpointStore NOOP = new NoopCheckpointStore();
}

/**
 * Package-private no-op implementation.
 */
final class NoopCheckpointStore implements CheckpointStore {

    @Override
    public void start() {}

    @Override
    public void stop() {}

    @Override
    public <S> WorkflowSnapshot<S> save(WorkflowSnapshot<S> snapshot) {
        return snapshot;
    }

    @Override
    public <S> Optional<WorkflowSnapshot<S>> load(CheckpointId id) {
        return Optional.empty();
    }

    @Override
    public <S> WorkflowSnapshot<S> fork(CheckpointId sourceId, S newState) {
        throw new IllegalArgumentException("NOOP store has no snapshots to fork from");
    }

    @Override
    public List<WorkflowSnapshot<?>> list(CheckpointQuery query) {
        return List.of();
    }

    @Override
    public boolean delete(CheckpointId id) {
        return false;
    }

    @Override
    public int deleteCoordination(String coordinationId) {
        return 0;
    }

    @Override
    public void addListener(CheckpointListener listener) {}

    @Override
    public void removeListener(CheckpointListener listener) {}
}
