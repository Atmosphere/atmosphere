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

/**
 * Filter used by {@link CheckpointStore#list(CheckpointQuery)}. Any non-null
 * field narrows the result set; null fields are unconstrained.
 *
 * @param coordinationId restrict to a single coordination, or {@code null}
 * @param agentName      restrict to a single agent, or {@code null}
 * @param since          only snapshots with {@code createdAt >= since}, or {@code null}
 * @param until          only snapshots with {@code createdAt <= until}, or {@code null}
 * @param limit          maximum results; {@code <= 0} means unlimited
 */
public record CheckpointQuery(
        String coordinationId,
        String agentName,
        Instant since,
        Instant until,
        int limit
) {

    /** Unconstrained query — matches every snapshot in the store. */
    public static CheckpointQuery all() {
        return new CheckpointQuery(null, null, null, null, 0);
    }

    /** All snapshots for a single coordination. */
    public static CheckpointQuery forCoordination(String coordinationId) {
        return new CheckpointQuery(coordinationId, null, null, null, 0);
    }

    /** All snapshots for a single agent across all coordinations. */
    public static CheckpointQuery forAgent(String agentName) {
        return new CheckpointQuery(null, agentName, null, null, 0);
    }

    /** Fluent builder for incrementally composing queries. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String coordinationId;
        private String agentName;
        private Instant since;
        private Instant until;
        private int limit;

        private Builder() {}

        public Builder coordinationId(String coordinationId) {
            this.coordinationId = coordinationId;
            return this;
        }

        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public Builder since(Instant since) {
            this.since = since;
            return this;
        }

        public Builder until(Instant until) {
            this.until = until;
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public CheckpointQuery build() {
            return new CheckpointQuery(coordinationId, agentName, since, until, limit);
        }
    }
}
