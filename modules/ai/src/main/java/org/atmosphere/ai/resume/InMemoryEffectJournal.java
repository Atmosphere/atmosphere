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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * In-memory reference {@link EffectJournal}. Holds each run's effect history in
 * heap and demonstrates the full two-phase append/commit, ordered fold,
 * single-writer lease, and terminal-only retention contract — which a durable
 * backend (the bundled {@code SqliteEffectJournal}, or an external
 * provider-supplied event history) replaces by storing the same shape
 * out-of-process.
 *
 * <h2>Not crash-durable</h2>
 *
 * This implementation dies with the JVM, so {@link #durable()} returns
 * {@code false}. It is the SPI reference and the non-durable fallback; a
 * deployment must supply a {@code durable()==true} backend before advertising
 * crash-durable / deterministic-replay (Correctness Invariant #5).
 *
 * <h2>Backpressure (Correctness Invariant #3)</h2>
 *
 * Per-run effects are capped at {@link #maxEffectsPerRun()}: exceeding it throws
 * {@link RejectedExecutionException} from {@link #appendPending} rather than
 * dropping early effects (which would corrupt replay). The run count is capped at
 * {@code maxRuns}; when exceeded, the oldest <em>terminal</em> run is evicted —
 * an in-flight (non-terminal) run is never evicted, so a resume anchor cannot be
 * lost under write pressure.
 */
public final class InMemoryEffectJournal implements EffectJournal {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryEffectJournal.class);

    /** Default cap on concurrently retained runs. */
    public static final int DEFAULT_MAX_RUNS = 10_000;

    /** Default hard per-run effect cap, matching {@code durable-runs.max-effects-per-run}. */
    public static final int DEFAULT_MAX_EFFECTS_PER_RUN = 2_000;

    private final int maxRuns;
    private final int maxEffectsPerRun;
    private final Clock clock;
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();
    private final Map<String, Lease> leases = new ConcurrentHashMap<>();

    public InMemoryEffectJournal() {
        this(DEFAULT_MAX_RUNS, DEFAULT_MAX_EFFECTS_PER_RUN, Clock.systemUTC());
    }

    public InMemoryEffectJournal(int maxRuns, int maxEffectsPerRun) {
        this(maxRuns, maxEffectsPerRun, Clock.systemUTC());
    }

    /** Test/diagnostic constructor with an injectable clock for lease-expiry control. */
    public InMemoryEffectJournal(int maxRuns, int maxEffectsPerRun, Clock clock) {
        if (maxRuns <= 0) {
            throw new IllegalArgumentException("maxRuns must be > 0, got " + maxRuns);
        }
        if (maxEffectsPerRun <= 0) {
            throw new IllegalArgumentException("maxEffectsPerRun must be > 0, got " + maxEffectsPerRun);
        }
        this.maxRuns = maxRuns;
        this.maxEffectsPerRun = maxEffectsPerRun;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public long appendPending(String runId, EffectKind kind,
                              String idempotencyKey, String requestDigest) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        var created = new boolean[]{false};
        var state = runs.computeIfAbsent(runId, k -> {
            created[0] = true;
            return new RunState(clock.instant());
        });
        long seq;
        synchronized (state) {
            var existingIdx = state.keyIndex.get(idempotencyKey);
            if (existingIdx != null) {
                // Idempotent re-append: return the existing seq, no duplicate row.
                return state.bySeq.get(existingIdx).seq();
            }
            if (state.bySeq.size() >= maxEffectsPerRun) {
                throw new RejectedExecutionException("Effect cap exceeded for run " + runId
                        + " (maxEffectsPerRun=" + maxEffectsPerRun + "); failing the run rather "
                        + "than dropping recorded effects");
            }
            seq = state.nextSeq++;
            var record = new EffectRecord(runId, seq, kind, idempotencyKey,
                    EffectStatus.PENDING, requestDigest, null, clock.instant());
            state.bySeq.add(record);
            state.keyIndex.put(idempotencyKey, state.bySeq.size() - 1);
        }
        if (created[0]) {
            evictOldestTerminalIfOverCapacity();
        }
        return seq;
    }

    @Override
    public void commit(String runId, String idempotencyKey, String resultPayload) {
        var state = runs.get(runId);
        if (state == null) {
            throw new IllegalStateException("commit for unknown run " + runId);
        }
        synchronized (state) {
            var idx = state.keyIndex.get(idempotencyKey);
            if (idx == null) {
                throw new IllegalStateException("commit without appendPending for key "
                        + idempotencyKey + " in run " + runId);
            }
            var existing = state.bySeq.get(idx);
            state.bySeq.set(idx, new EffectRecord(existing.runId(), existing.seq(),
                    existing.kind(), existing.idempotencyKey(), EffectStatus.COMMITTED,
                    existing.requestDigest(), resultPayload, existing.recordedAt()));
        }
    }

    @Override
    public void markFailed(String runId, String idempotencyKey, String reason) {
        var state = runs.get(runId);
        if (state == null) {
            // Best-effort: never mask the original failure with a journal error.
            logger.trace("markFailed for unknown run {} (key {})", runId, idempotencyKey);
            return;
        }
        synchronized (state) {
            var idx = state.keyIndex.get(idempotencyKey);
            if (idx == null) {
                logger.trace("markFailed without appendPending for key {} in run {}",
                        idempotencyKey, runId);
                return;
            }
            var existing = state.bySeq.get(idx);
            state.bySeq.set(idx, new EffectRecord(existing.runId(), existing.seq(),
                    existing.kind(), existing.idempotencyKey(), EffectStatus.FAILED,
                    existing.requestDigest(), reason, existing.recordedAt()));
        }
    }

    @Override
    public Optional<EffectRecord> lookupCommitted(String runId, String idempotencyKey) {
        var state = runs.get(runId);
        if (state == null) {
            return Optional.empty();
        }
        synchronized (state) {
            var idx = state.keyIndex.get(idempotencyKey);
            if (idx == null) {
                return Optional.empty();
            }
            var record = state.bySeq.get(idx);
            return record.status() == EffectStatus.COMMITTED ? Optional.of(record) : Optional.empty();
        }
    }

    @Override
    public List<EffectRecord> fold(String runId) {
        var state = runs.get(runId);
        if (state == null) {
            return List.of();
        }
        synchronized (state) {
            // bySeq is maintained in append (seq) order.
            return List.copyOf(state.bySeq);
        }
    }

    @Override
    public boolean claimLease(String runId, String owner, Duration ttl) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(ttl, "ttl");
        var now = clock.instant();
        var result = leases.compute(runId, (k, cur) -> {
            boolean reclaimable = cur == null
                    || !cur.expiresAt().isAfter(now)   // expired (expiresAt <= now)
                    || cur.owner().equals(owner);      // re-entrant renew
            return reclaimable ? new Lease(owner, now.plus(ttl)) : cur;
        });
        return result.owner().equals(owner);
    }

    @Override
    public void releaseLease(String runId, String owner) {
        leases.computeIfPresent(runId, (k, cur) -> cur.owner().equals(owner) ? null : cur);
    }

    @Override
    public void markTerminal(String runId, EffectStatus terminal) {
        if (terminal == EffectStatus.PENDING) {
            throw new IllegalArgumentException("terminal status must be COMMITTED or FAILED");
        }
        var state = runs.get(runId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.terminal = terminal;
        }
        evictOldestTerminalIfOverCapacity();
    }

    @Override
    public void removeRun(String runId) {
        runs.remove(runId);
        leases.remove(runId);
    }

    @Override
    public boolean durable() {
        // In-memory: dies with the JVM. The SPI reference, not a crash-durable store.
        return false;
    }

    @Override
    public String name() {
        return "in-memory";
    }

    @Override
    public int maxEffectsPerRun() {
        return maxEffectsPerRun;
    }

    /** Visible for tests / admin: number of runs currently retained. */
    public int runCount() {
        return runs.size();
    }

    private void evictOldestTerminalIfOverCapacity() {
        while (runs.size() > maxRuns) {
            var oldestTerminal = runs.entrySet().stream()
                    .filter(e -> {
                        synchronized (e.getValue()) {
                            return e.getValue().terminal != null;
                        }
                    })
                    .min(Comparator.comparing(e -> e.getValue().createdAt()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldestTerminal == null) {
                // All runs are in-flight; never evict a non-terminal run's history.
                return;
            }
            removeRun(oldestTerminal);
        }
    }

    /** Per-run effect history + terminal flag, mutated under its own monitor. */
    private static final class RunState {
        private final Instant createdAt;
        private final List<EffectRecord> bySeq = new ArrayList<>();
        private final Map<String, Integer> keyIndex = new HashMap<>();
        private long nextSeq;
        private EffectStatus terminal; // null = in-flight

        private RunState(Instant createdAt) {
            this.createdAt = createdAt;
        }

        private Instant createdAt() {
            return createdAt;
        }
    }

    /** A single-writer lease: who holds it and when it expires. */
    private record Lease(String owner, Instant expiresAt) {
    }
}
