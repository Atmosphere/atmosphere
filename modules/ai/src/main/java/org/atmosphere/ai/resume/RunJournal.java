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
package org.atmosphere.ai.resume;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Durable backing store for in-flight runs so a reconnecting client can
 * replay what a run produced even across a process restart. The in-memory
 * {@link RunRegistry} and {@link RunEventReplayBuffer} lose all state when
 * the JVM dies; a {@code RunJournal} persists run metadata and captured
 * events so a fresh registry can {@linkplain RunRegistry#rehydrate()
 * rehydrate} them after a crash or rolling redeploy.
 *
 * <h2>Runtime truth (Correctness Invariant #5)</h2>
 *
 * Not every journal survives a crash. {@link #durable()} reports whether a
 * given implementation actually persists across process restarts — the
 * bundled {@link InMemoryRunJournal} returns {@code false} (it is the SPI
 * reference and the rehydration-wiring proof, not a crash-durable store).
 * Callers MUST consult {@link #durable()} before advertising "crash-durable
 * resume"; a backend that writes to Redis/Postgres/disk returns
 * {@code true}.
 *
 * <h2>Best-effort writes (Correctness Invariant #3)</h2>
 *
 * Journal writes happen on the streaming hot path. Implementations and
 * callers treat {@link #appendEvent} / {@link #recordRun} as best-effort:
 * a journal failure is logged at TRACE and never thrown into the live
 * stream. A run whose journaling failed simply falls back to in-memory-only
 * replay — the same behaviour as before durability existed.
 *
 * <h2>Backpressure (Correctness Invariant #3)</h2>
 *
 * The set of journaled runs and per-run events MUST be bounded — see
 * {@link InMemoryRunJournal} for the reference eviction policy. An unbounded
 * journal fed by external request volume is a memory/disk DoS vector.
 *
 * @since 4.0
 */
public interface RunJournal {

    /**
     * Immutable metadata for one journaled run. The {@code userId} is
     * retained so {@code RunReattachSupport} can re-apply its
     * caller-owns-run authorization check (Correctness Invariant #6) after
     * a rehydrated run is looked up — a reconnecting client may only drain a
     * run it originally owned.
     *
     * @param runId     stable run identifier
     * @param agentId   the agent that produced the run
     * @param userId    the user that initiated the run (authorization key)
     * @param sessionId the durable session the run belongs to
     * @param createdAt when the run started
     */
    record RunRecord(String runId, String agentId, String userId,
                     String sessionId, Instant createdAt) {

        public RunRecord {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** Persist run metadata. Called once when a run registers. */
    void recordRun(RunRecord run);

    /**
     * Append one captured event for a run. Called for every event the run
     * emits; must be cheap and best-effort.
     */
    void appendEvent(String runId, RunEvent event);

    /**
     * Remove a run and its events. Called when the run reaches a terminal
     * state (complete/error/cancel) or is swept — closing the terminal path
     * so finished runs do not accumulate (Correctness Invariant #2).
     */
    void removeRun(String runId);

    /** All persisted run metadata; used by {@link RunRegistry#rehydrate()}. */
    List<RunRecord> loadAll();

    /**
     * Events persisted for a run, oldest first, preserving their original
     * sequence numbers so replay ordering survives the restart.
     */
    List<RunEvent> loadEvents(String runId);

    /**
     * Whether this journal survives a process restart. {@code false} for
     * purely in-memory implementations. Only when this returns {@code true}
     * may a deployment advertise "crash-durable" run resume.
     */
    boolean durable();

    /**
     * No-op journal — the default when durability is not configured.
     * Records nothing, rehydrates nothing, and reports {@link #durable()}
     * as {@code false}. Selected via identity comparison on the hot path so
     * the common (non-durable) case adds zero work.
     */
    RunJournal NOOP = new RunJournal() {
        @Override
        public void recordRun(RunRecord run) {
            // no-op
        }

        @Override
        public void appendEvent(String runId, RunEvent event) {
            // no-op
        }

        @Override
        public void removeRun(String runId) {
            // no-op
        }

        @Override
        public List<RunRecord> loadAll() {
            return List.of();
        }

        @Override
        public List<RunEvent> loadEvents(String runId) {
            return List.of();
        }

        @Override
        public boolean durable() {
            return false;
        }
    };
}
