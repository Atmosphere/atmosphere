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

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Append-only, per-run effect history — the Temporal/DBOS event-history store
 * that makes an agent run deterministically replayable. It is a deliberate
 * <em>sibling</em> of {@link RunJournal} (which replays the wire output a client
 * saw) rather than a reuse of it: this journal records the run's <em>compute /
 * decision</em> history (LLM rounds, tool calls, approvals) keyed by
 * {@code runId} and short-circuits a recorded effect on replay so the side
 * effect runs at most once.
 *
 * <h2>Two-phase write</h2>
 *
 * Every effect is recorded in two steps: {@link #appendPending appendPending}
 * <em>before</em> the side effect runs, then {@link #commit commit} with the
 * recorded result <em>after</em>. A crash between the two leaves the effect
 * {@code PENDING}; on resume a {@code PENDING} (or {@code FAILED}) effect
 * re-runs, an at-least-once boundary that non-idempotent tools must dedup
 * internally. Only {@link #lookupCommitted lookupCommitted} (a {@code COMMITTED}
 * result) is a replay hit.
 *
 * <h2>Runtime truth (Correctness Invariant #5)</h2>
 *
 * {@link #durable()} reports whether this implementation actually survives a
 * process restart. The bundled {@link InMemoryEffectJournal} returns
 * {@code false} (SPI reference + non-durable fallback). Capabilities and
 * discovery endpoints MUST gate "crash-durable / deterministic-replay" claims on
 * {@code durable()} of the <em>resolved</em> journal, never on config intent.
 *
 * <h2>Backpressure (Correctness Invariant #3)</h2>
 *
 * Per-run effects are bounded by {@link #maxEffectsPerRun()}: exceeding it
 * <em>fails the run</em> (a {@link java.util.concurrent.RejectedExecutionException}
 * from {@link #appendPending}) rather than silently dropping early effects,
 * which would corrupt replay. Retention across runs evicts only
 * <em>terminal</em> runs (see {@link #markTerminal}); a non-terminal run's
 * history is never evicted.
 *
 * <h2>Single writer (Correctness Invariant #2)</h2>
 *
 * {@link #claimLease claimLease} grants an atomic single-writer lease on a
 * {@code runId} so a rolling redeploy or a manual resume cannot double-drive a
 * run. A resume may claim a run only after the prior owner's lease has expired.
 *
 * @since 4.0
 */
public interface EffectJournal {

    /**
     * Record an effect as {@code PENDING} before its side effect runs, assigning
     * the next per-run {@code seq}. Idempotent: if {@code (runId, idempotencyKey)}
     * already exists its existing {@code seq} is returned and no duplicate row is
     * written, so a re-driven append is safe.
     *
     * @return the per-run sequence number assigned to (or already held by) this effect
     * @throws java.util.concurrent.RejectedExecutionException if recording this
     *         effect would exceed {@link #maxEffectsPerRun()} for the run
     */
    long appendPending(String runId, EffectKind kind, String idempotencyKey, String requestDigest);

    /**
     * Flip a previously-appended effect to {@code COMMITTED} with its recorded
     * result. Requires a prior {@link #appendPending} for the same
     * {@code (runId, idempotencyKey)}; committing an unknown key is a contract
     * violation ({@link IllegalStateException}).
     */
    void commit(String runId, String idempotencyKey, String resultPayload);

    /**
     * Mark a previously-appended effect {@code FAILED}, retaining the reason for
     * audit. A {@code FAILED} effect is not a replay hit and re-runs on resume.
     */
    void markFailed(String runId, String idempotencyKey, String reason);

    /**
     * The {@code COMMITTED} effect for {@code (runId, idempotencyKey)}, or empty
     * if absent, still {@code PENDING}, or {@code FAILED}. A present result is a
     * replay hit: the caller skips the side effect and returns the recorded value.
     */
    Optional<EffectRecord> lookupCommitted(String runId, String idempotencyKey);

    /** All effects for a run, oldest first by {@code seq} (never by wall-clock). */
    List<EffectRecord> fold(String runId);

    /**
     * Atomically claim the single-writer lease on {@code runId} for {@code owner}
     * with the given {@code ttl}. Succeeds when the run is unleased, the existing
     * lease has expired, or it is already held by {@code owner} (re-entrant
     * renew). Returns {@code false} when another live owner holds the lease.
     */
    boolean claimLease(String runId, String owner, Duration ttl);

    /** Release the lease on {@code runId} iff currently held by {@code owner}. */
    void releaseLease(String runId, String owner);

    /**
     * Mark the run terminal so its history becomes eligible for retention
     * eviction (closing the terminal path, Correctness Invariant #2). Pass
     * {@link EffectStatus#COMMITTED} for success or {@link EffectStatus#FAILED}
     * for failure; {@link EffectStatus#PENDING} is rejected.
     */
    void markTerminal(String runId, EffectStatus terminal);

    /** Remove a run's effects, metadata, and lease entirely. */
    void removeRun(String runId);

    /**
     * Whether this journal survives a process restart. {@code false} for purely
     * in-memory implementations. Only a {@code true} backend may back an
     * advertised "crash-durable" or "deterministic-replay" capability.
     */
    boolean durable();

    /** Stable backend name for discovery endpoints (e.g. {@code sqlite}, {@code in-memory}). */
    String name();

    /** Hard per-run effect cap; {@link #appendPending} rejects past this bound. */
    int maxEffectsPerRun();

    /**
     * No-op journal — the default when durable runs are not configured. Records
     * nothing, folds nothing, reports {@link #durable()} as {@code false}, and is
     * selected by identity on the hot path so the common (non-durable) case adds
     * zero work. {@link #claimLease} returns {@code true} so a non-durable drive
     * always proceeds.
     */
    EffectJournal NOOP = new EffectJournal() {
        @Override
        public long appendPending(String runId, EffectKind kind,
                                  String idempotencyKey, String requestDigest) {
            return 0L;
        }

        @Override
        public void commit(String runId, String idempotencyKey, String resultPayload) {
            // no-op
        }

        @Override
        public void markFailed(String runId, String idempotencyKey, String reason) {
            // no-op
        }

        @Override
        public Optional<EffectRecord> lookupCommitted(String runId, String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public List<EffectRecord> fold(String runId) {
            return List.of();
        }

        @Override
        public boolean claimLease(String runId, String owner, Duration ttl) {
            return true;
        }

        @Override
        public void releaseLease(String runId, String owner) {
            // no-op
        }

        @Override
        public void markTerminal(String runId, EffectStatus terminal) {
            // no-op
        }

        @Override
        public void removeRun(String runId) {
            // no-op
        }

        @Override
        public boolean durable() {
            return false;
        }

        @Override
        public String name() {
            return "noop";
        }

        @Override
        public int maxEffectsPerRun() {
            return Integer.MAX_VALUE;
        }
    };
}
