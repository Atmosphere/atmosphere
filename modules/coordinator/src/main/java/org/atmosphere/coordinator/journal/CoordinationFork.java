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
package org.atmosphere.coordinator.journal;

import org.atmosphere.coordinator.fleet.AgentCall;
import org.atmosphere.coordinator.fleet.AgentResult;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * "What-if" branching primitive for event-sourced coordinations. Forks a new
 * coordination off an existing one at a chosen event, runs an alternate
 * {@link AgentCall} under a fresh coordination id, and stamps the connection
 * with a {@link CoordinationEvent.ForkCreated} envelope.
 *
 * <p>Forks do NOT copy or rewrite historical events: they record a link.
 * The original coordination is immutable; the forked coordination is a
 * peer with its own id and its own future. Cross-coord traversal goes
 * through the {@code parentCoordinationId} / {@code parentEventId} fields
 * on the {@code ForkCreated} record.</p>
 *
 * <p>Typical use case — eval/routing: after a coordination completes, re-run
 * a decision point with an alternate agent or args to compare outcomes
 * without polluting the original journal entry.</p>
 *
 * <pre>{@code
 * var fork = new CoordinationFork(journal);
 * var result = fork
 *     .from("dispatch-2026-05-26", parentEventId)
 *     .reason("try beta instead of alpha")
 *     .with(new AgentCall("beta", "answer", Map.of(...)))
 *     .execute(fleet);
 * // result.newCoordinationId() identifies the forked branch in the journal.
 * }</pre>
 */
public final class CoordinationFork {

    private final CoordinationJournal journal;

    public CoordinationFork(CoordinationJournal journal) {
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    /** Begin a fork from {@code parentEventId} in {@code parentCoordinationId}. */
    public ForkBuilder from(String parentCoordinationId, String parentEventId) {
        Objects.requireNonNull(parentCoordinationId, "parentCoordinationId");
        Objects.requireNonNull(parentEventId, "parentEventId");
        return new ForkBuilder(parentCoordinationId, parentEventId);
    }

    /** Builder for fork metadata (reason) and the alternate dispatch. */
    public final class ForkBuilder {
        private final String parentCoordinationId;
        private final String parentEventId;
        private String reason;

        private ForkBuilder(String parentCoordinationId, String parentEventId) {
            this.parentCoordinationId = parentCoordinationId;
            this.parentEventId = parentEventId;
        }

        /** Human-readable reason recorded on the {@code ForkCreated} event. */
        public ForkBuilder reason(String reason) {
            this.reason = reason;
            return this;
        }

        /** Set the alternate dispatch to execute under the forked coordination. */
        public ForkPlan with(AgentCall alternate) {
            Objects.requireNonNull(alternate, "alternate");
            return new ForkPlan(parentCoordinationId, parentEventId, reason, alternate);
        }
    }

    /** A configured fork ready to execute. */
    public final class ForkPlan {
        private final String parentCoordinationId;
        private final String parentEventId;
        private final String reason;
        private final AgentCall alternate;

        private ForkPlan(String parentCoordinationId, String parentEventId,
                         String reason, AgentCall alternate) {
            this.parentCoordinationId = parentCoordinationId;
            this.parentEventId = parentEventId;
            this.reason = reason;
            this.alternate = alternate;
        }

        /**
         * Execute the fork under a generated coordination id. Verifies the
         * parent event exists in the journal before recording the fork, so
         * a typo'd {@code parentEventId} fails fast rather than silently
         * creating a dangling fork link.
         */
        public ForkResult execute(JournalingAgentFleet fleet) {
            return execute(fleet, generateForkedCoordinationId());
        }

        /**
         * Execute the fork under an explicit {@code newCoordinationId}.
         * Same fail-fast contract as {@link #execute(JournalingAgentFleet)}.
         */
        public ForkResult execute(JournalingAgentFleet fleet, String newCoordinationId) {
            Objects.requireNonNull(fleet, "fleet");
            Objects.requireNonNull(newCoordinationId, "newCoordinationId");

            var projection = CoordinationProjection.from(journal, parentCoordinationId);
            if (projection.event(parentEventId).isEmpty()) {
                throw new IllegalArgumentException(
                        "Parent event " + parentEventId + " not found in coordination "
                                + parentCoordinationId);
            }

            var forkEventId = journal.recordEnveloped(EventEnvelope.root(
                    new CoordinationEvent.ForkCreated(
                            newCoordinationId,
                            parentCoordinationId,
                            parentEventId,
                            reason,
                            Instant.now())));

            var forkedFleet = fleet.withCoordinationId(newCoordinationId);
            var result = forkedFleet.agent(alternate.agentName())
                    .call(alternate.skill(), alternate.args());

            return new ForkResult(newCoordinationId, forkEventId, result);
        }

        private String generateForkedCoordinationId() {
            return parentCoordinationId + "/fork-" + UUID.randomUUID();
        }
    }

    /**
     * Result of executing a fork. {@link #newCoordinationId} identifies the
     * forked branch in the journal; {@link #forkEventId} is the id of the
     * {@code ForkCreated} envelope; {@link #result} is the outcome of the
     * alternate dispatch.
     */
    public record ForkResult(
            String newCoordinationId,
            String forkEventId,
            AgentResult result) {
    }
}
