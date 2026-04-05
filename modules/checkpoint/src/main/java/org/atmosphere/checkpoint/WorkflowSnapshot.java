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

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable record capturing the state of an agent workflow at a point in time.
 *
 * <p>A snapshot belongs to a <em>coordination</em> (the top-level workflow
 * run, identified by {@code coordinationId}) and references an optional
 * <em>parent</em> snapshot. The parent chain forms the execution history;
 * forks create branches in that chain.</p>
 *
 * @param id             unique identifier of this snapshot
 * @param parentId       the snapshot this one was derived from, or {@code null} if it is a root
 * @param coordinationId the workflow run this snapshot belongs to
 * @param agentName      the agent whose state was captured (may be {@code null} for coordinator-level checkpoints)
 * @param state          user-owned opaque workflow state (caller controls type + serialization)
 * @param metadata       arbitrary key/value metadata (tags, labels, correlation ids)
 * @param createdAt      wall-clock timestamp when the snapshot was created
 * @param <S>            the application-owned state type
 */
public record WorkflowSnapshot<S>(
        CheckpointId id,
        CheckpointId parentId,
        String coordinationId,
        String agentName,
        S state,
        Map<String, String> metadata,
        Instant createdAt
) {

    public WorkflowSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(coordinationId, "coordinationId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /** Whether this snapshot has no parent (i.e. is the root of its history chain). */
    public boolean isRoot() {
        return parentId == null;
    }

    /** The parent snapshot id, if any. */
    public Optional<CheckpointId> parent() {
        return Optional.ofNullable(parentId);
    }

    /** Fluent builder for snapshots. */
    public static <S> Builder<S> builder() {
        return new Builder<>();
    }

    /** Convenience: create a root snapshot with a fresh id. */
    public static <S> WorkflowSnapshot<S> root(String coordinationId, S state) {
        return WorkflowSnapshot.<S>builder()
                .id(CheckpointId.random())
                .coordinationId(coordinationId)
                .state(state)
                .createdAt(Instant.now())
                .build();
    }

    /**
     * Create a child snapshot derived from this one with updated state. The
     * returned snapshot has a fresh id, this snapshot as its parent, and the
     * same coordinationId.
     */
    public WorkflowSnapshot<S> deriveWith(S newState) {
        return WorkflowSnapshot.<S>builder()
                .id(CheckpointId.random())
                .parentId(this.id)
                .coordinationId(this.coordinationId)
                .agentName(this.agentName)
                .state(newState)
                .metadata(this.metadata)
                .createdAt(Instant.now())
                .build();
    }

    public static final class Builder<S> {
        private CheckpointId id;
        private CheckpointId parentId;
        private String coordinationId;
        private String agentName;
        private S state;
        private Map<String, String> metadata = Map.of();
        private Instant createdAt;

        private Builder() {}

        public Builder<S> id(CheckpointId id) {
            this.id = id;
            return this;
        }

        public Builder<S> parentId(CheckpointId parentId) {
            this.parentId = parentId;
            return this;
        }

        public Builder<S> coordinationId(String coordinationId) {
            this.coordinationId = coordinationId;
            return this;
        }

        public Builder<S> agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public Builder<S> state(S state) {
            this.state = state;
            return this;
        }

        public Builder<S> metadata(Map<String, String> metadata) {
            this.metadata = metadata != null ? metadata : Map.of();
            return this;
        }

        public Builder<S> createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public WorkflowSnapshot<S> build() {
            if (id == null) {
                id = CheckpointId.random();
            }
            if (createdAt == null) {
                createdAt = Instant.now();
            }
            return new WorkflowSnapshot<>(id, parentId, coordinationId, agentName, state, metadata, createdAt);
        }
    }
}
