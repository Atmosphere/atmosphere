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
package org.atmosphere.ai.tape;

import java.util.List;
import java.util.Optional;

/**
 * Append-only, per-run persistence for the session tape — the typed event
 * stream crossing the {@link org.atmosphere.ai.StreamingSession} boundary,
 * recorded "as-produced at the session boundary, post-decorator." The tape
 * survives run completion (unlike the transient reattach {@code RunJournal})
 * and is a step log, not a compute re-drive journal (unlike
 * {@code EffectJournal}).
 *
 * <h2>Security posture</h2>
 *
 * Step content is pre-broadcast-filter (precedent: {@code EffectRecord.RecordedRound}
 * persisted raw in the effect journal's result payload). Any future non-admin
 * read surface MUST enforce principal ownership against {@link TapeRun#userId()}
 * and re-apply broadcast filters before re-emission (Correctness Invariant #6).
 *
 * <h2>Best-effort writes</h2>
 *
 * The tape must never fail a healthy stream: implementations may throw
 * {@link RuntimeException} on storage failure, but the tape writer contains
 * every store failure (logged once per run, counted, never propagated).
 * The per-run step cap is stop-record + {@link TapeRun#truncated() truncated}
 * flag — observability, NOT the effect journal's fail-the-run (that protects
 * replay correctness; the tape protects nothing on the live path).
 */
public interface TapeStore extends AutoCloseable {

    /**
     * Terminal counters carried into {@link #markTerminal}. {@code stepCount}
     * is the writer's count of successfully appended steps; stores that track
     * their own row counts may prefer their local truth.
     *
     * @param stepCount    steps the writer successfully appended
     * @param droppedSteps steps produced but not persisted
     * @param truncated    whether the per-run step cap stopped recording
     */
    record Counters(long stepCount, long droppedSteps, boolean truncated) {

        /** Zero counters — for terminals of runs that never recorded a step. */
        public static final Counters NONE = new Counters(0, 0, false);
    }

    /**
     * Begin (or re-open) a run. Idempotent upsert: a second {@code begin}
     * with the same {@code runId} — e.g. a crash-resume re-drive appending
     * to an existing tape run — updates identity metadata and MUST NOT
     * reset the run's status, steps, or counters. Durable stores continue
     * step sequencing at {@code MAX(seq)+1} for a re-opened run.
     *
     * @param run the run row; {@link TapeRun#status()} is normally {@link TapeStatus#OPEN}
     */
    void begin(TapeRun run);

    /**
     * Append steps to a run, in the given order. Contract: reject-or-ignore
     * (never insert) for runs this store has marked terminal — steps arriving
     * after {@link #markTerminal} are silently discarded.
     *
     * @param runId the run to append to
     * @param steps writer-sequenced steps, ascending {@link TapeStep#seq()}
     */
    void append(String runId, List<TapeStep> steps);

    /**
     * Mark a run terminal. Write-once: the first terminal wins; a later call
     * for an already-terminal run is ignored (never a status flip).
     *
     * @param runId    the run to close
     * @param status   the terminal status; must satisfy {@link TapeStatus#terminal()}
     * @param counters final writer counters for the run
     * @throws IllegalArgumentException if {@code status} is {@link TapeStatus#OPEN}
     */
    void markTerminal(String runId, TapeStatus status, Counters counters);

    /**
     * List runs matching the query, newest-first by {@link TapeRun#startedAt()}.
     */
    List<TapeRun> listRuns(TapeQuery query);

    /**
     * Read a run's steps as a cursor window: steps with
     * {@code seq >= fromSeq}, ascending, at most {@code max} rows
     * ({@code max <= 0} for no explicit cap — still bounded by the per-run
     * step cap).
     */
    List<TapeStep> readSteps(String runId, long fromSeq, int max);

    /**
     * Fork a run: mint a new run whose {@link TapeRun#parentRunId()} points
     * at the source and whose steps are copies of the source's steps. The
     * fork starts {@link TapeStatus#OPEN}.
     *
     * @return the new run's id, or empty when the source run is unknown
     */
    Optional<String> fork(String runId);

    /** Remove a run and its steps. */
    void removeRun(String runId);

    /** Retention cap on runs; the eviction policy is oldest-TERMINAL-first. */
    int maxRuns();

    /** Per-run step cap; beyond it the writer stops recording and flags {@code truncated}. */
    int maxStepsPerRun();

    /**
     * Whether this store survives a process restart (Correctness Invariant #5,
     * Runtime Truth — only a {@code durable() == true} store may back a
     * "durable tape" claim).
     */
    boolean durable();

    /** Diagnostic name of the store. */
    String name();

    /** Release store resources. Defaults to a no-op for in-memory stores. */
    @Override
    default void close() {
        // no resources by default
    }

    /**
     * Disabled store, selected via identity comparison ({@code store == NOOP})
     * so wiring code can express "explicitly off". {@link TapeSupport#install}
     * refuses it — leave the tape uninstalled instead of installing a recorder
     * that persists nothing.
     */
    TapeStore NOOP = new TapeStore() {
        @Override
        public void begin(TapeRun run) {
            // no-op
        }

        @Override
        public void append(String runId, List<TapeStep> steps) {
            // no-op
        }

        @Override
        public void markTerminal(String runId, TapeStatus status, Counters counters) {
            // no-op
        }

        @Override
        public List<TapeRun> listRuns(TapeQuery query) {
            return List.of();
        }

        @Override
        public List<TapeStep> readSteps(String runId, long fromSeq, int max) {
            return List.of();
        }

        @Override
        public Optional<String> fork(String runId) {
            return Optional.empty();
        }

        @Override
        public void removeRun(String runId) {
            // no-op
        }

        @Override
        public int maxRuns() {
            return 0;
        }

        @Override
        public int maxStepsPerRun() {
            return 0;
        }

        @Override
        public boolean durable() {
            return false;
        }

        @Override
        public String name() {
            return "noop";
        }
    };
}
