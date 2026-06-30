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

import java.util.Objects;
import java.util.Optional;

/**
 * The entry point that turns the durable-execution machinery from dormant code
 * into a live production consumer of the {@link EffectJournal}. The endpoint
 * handler asks the spine to {@linkplain #beginDrive begin driving} a run right
 * after it assigns the {@code runId}; the spine claims the single-writer lease
 * and installs a {@link DurableRunContext} in {@link DurableRunScopeHolder}
 * <em>before</em> the {@code @Prompt} body dispatches. From that point every
 * journaled seam — the cross-runtime tool memo in
 * {@code ToolExecutionHelper.executeWithApproval} and the BuiltIn LLM-round
 * replay in {@code OpenAiCompatibleClient} — resolves the scope from
 * {@code session.runId()} and records (or, on a re-drive, replays) its effects.
 * On the run's terminal path the handler calls {@link #completeDrive} exactly
 * once, which marks the run terminal, prunes or retains its history per
 * {@link DurableRunConfig#retainOnSuccess()}, and releases the lease.
 *
 * <h2>Default-off, fail-safe</h2>
 *
 * When durable runs are {@linkplain DurableRunConfig#disabled() disabled} — the
 * default — {@link #beginDrive} returns {@link Optional#empty()}, no scope is
 * installed, and the seams take their byte-identical non-durable fast path.
 * Resolving a journal is the operator's explicit opt-in (Correctness
 * Invariant&nbsp;#6).
 *
 * <h2>Ownership &amp; terminal completeness (Invariants&nbsp;#1/#2)</h2>
 *
 * The spine owns exactly two resources per run: the lease and the installed
 * scope. {@link #completeDrive} releases both on <em>every</em> terminal path —
 * success, failure, cancel, timeout — even if marking the run terminal throws,
 * so a finished run never strands a lease that would block a later re-drive.
 *
 * @since 4.0
 */
public final class DurableRunSpine {

    private static final Logger logger = LoggerFactory.getLogger(DurableRunSpine.class);

    private final EffectJournal journal;
    private final DurableRunConfig config;
    private final String owner;

    /**
     * @param journal the resolved effect journal (may be {@link EffectJournal#NOOP})
     * @param config  the operator-facing durable-run knobs
     * @param owner   the single-writer lease owner string identifying this
     *                process; a crash-recovery re-drive in another process
     *                claims the run under its own owner after the lease expires
     */
    public DurableRunSpine(EffectJournal journal, DurableRunConfig config, String owner) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.config = Objects.requireNonNull(config, "config");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /** A spine that never drives a run durably — the process-wide default. */
    public static DurableRunSpine disabled() {
        return new DurableRunSpine(EffectJournal.NOOP, DurableRunConfig.disabled(), "disabled");
    }

    /** Whether this spine will actually install a scope (opt-in and a real journal). */
    public boolean enabled() {
        return config.enabled() && journal != EffectJournal.NOOP;
    }

    /** The journal this spine drives against. Never {@code null}. */
    public EffectJournal journal() {
        return journal;
    }

    /**
     * Begin driving {@code runId} durably: claim the single-writer lease and
     * install the run scope so the journaled seams record against it. Returns
     * the installed {@link DurableRunContext}, or {@link Optional#empty()} when
     * durable runs are disabled (the fast path) or the lease could not be
     * claimed (another writer holds this run — never the case for a freshly
     * registered first-drive runId, but guarded so a duplicate id can never
     * double-drive).
     *
     * @param runId        the freshly assigned run id (the journal partition key)
     * @param userId       the run's principal, bound into each tool effect's digest
     *                     so a different principal cannot inherit this run's
     *                     recorded (possibly human-approved) tool outcomes on a
     *                     re-drive (Inv #6)
     * @param endpointPath the {@code @AiEndpoint} path the run is driven against,
     *                     recorded in the seed so a crash-resume can resolve the
     *                     live tool set / runtime; may be {@code null}
     */
    public Optional<DurableRunContext> beginDrive(String runId, String userId, String endpointPath) {
        if (!enabled()) {
            return Optional.empty();
        }
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(userId, "userId");
        if (!journal.claimLease(runId, owner, config.leaseTtl())) {
            logger.warn("Durable run {}: lease already held; driving non-durably this turn", runId);
            return Optional.empty();
        }
        var ctx = new DurableRunContext(runId, journal, false, owner, userId, endpointPath);
        DurableRunScopeHolder.install(runId, ctx);
        logger.debug("Durable run {}: scope installed, lease held by {}", runId, owner);
        return Optional.of(ctx);
    }

    /**
     * Complete a run begun via {@link #beginDrive}: mark it terminal, prune or
     * retain its history per {@link DurableRunConfig#retainOnSuccess()}, and
     * release the lease and scope. Idempotent-safe and exception-safe — the
     * lease and scope are always released even if marking terminal throws, so no
     * finished run strands a lease (Invariants&nbsp;#1/#2).
     *
     * @param context the scope returned by {@link #beginDrive}
     * @param success {@code true} when the run completed normally
     */
    public void completeDrive(DurableRunContext context, boolean success) {
        Objects.requireNonNull(context, "context");
        var runId = context.runId();
        try {
            journal.markTerminal(runId, success ? EffectStatus.COMMITTED : EffectStatus.FAILED);
            if (success && !config.retainOnSuccess()) {
                // Successful runs need no resume anchor; drop their history. A
                // failed run is retained so a re-drive can replay its effects.
                journal.removeRun(runId);
            }
        } catch (RuntimeException e) {
            logger.warn("Durable run {}: error finalizing journal; releasing lease anyway", runId, e);
        } finally {
            journal.releaseLease(runId, context.owner());
            DurableRunScopeHolder.remove(runId);
            logger.debug("Durable run {}: terminal ({}), lease released", runId, success ? "ok" : "failed");
        }
    }

    /**
     * Take over a crashed run for a re-drive. Claims the (now free or expired)
     * lease, reads the recorded {@link EffectRecord.RunSeed}, and — fail-closed —
     * refuses if the requester is not the run's principal (Inv #6). On success it
     * installs a <em>replay-mode</em> scope so the re-drive replays every
     * committed round and tool from the journal (zero provider HTTP, side effects
     * run at most once) and only the uncommitted tail executes live. The caller
     * reconstructs the request from the returned seed plus its live tool set,
     * drives the runtime, and calls {@link #completeDrive} on the terminal path.
     *
     * @param runId             the run to resume
     * @param requesterPrincipal the principal requesting the resume
     * @return {@link ResumeOutcome.Resume} with the installed scope and seed,
     *         {@link ResumeOutcome.Refused} for a foreign principal, or
     *         {@link ResumeOutcome.None} when there is nothing to resume
     */
    public ResumeOutcome beginResume(String runId, String requesterPrincipal) {
        Objects.requireNonNull(requesterPrincipal, "requesterPrincipal");
        return beginResume(runId, seed -> requesterPrincipal.equals(seed.userId()), true);
    }

    /**
     * Take over a crashed run for an <em>admin</em> re-drive. Identical to
     * {@link #beginResume} but the per-run owner check is bypassed: the caller is
     * authorized by an admin role at the endpoint's authz gate, not by run
     * ownership (Correctness Invariant #6 is enforced there). Use only behind an
     * authenticated, role-checked admin surface.
     *
     * @param runId the run to resume
     */
    public ResumeOutcome beginResumeAsAdmin(String runId) {
        return beginResume(runId, seed -> true, false);
    }

    private ResumeOutcome beginResume(String runId,
                                      java.util.function.Predicate<EffectRecord.RunSeed> principalOk,
                                      boolean ownerChecked) {
        Objects.requireNonNull(runId, "runId");
        if (!enabled()) {
            return new ResumeOutcome.None("durable runs disabled");
        }
        if (!journal.claimLease(runId, owner, config.leaseTtl())) {
            logger.info("Durable run {}: still leased by a live driver; not resuming", runId);
            return new ResumeOutcome.None("run still leased");
        }
        var seedRecord = journal.lookupCommitted(runId, EffectKeys.runInput(runId));
        if (seedRecord.isEmpty()) {
            // No input seed → nothing to re-drive (a completed+pruned run, or one
            // that crashed before its first round committed a seed). Give the
            // lease back so we never strand it (Inv #1/#2).
            journal.releaseLease(runId, owner);
            return new ResumeOutcome.None("no run seed");
        }
        EffectRecord.RunSeed seed;
        try {
            seed = RunSeeds.deserialize(seedRecord.get().resultPayload());
        } catch (RuntimeException e) {
            journal.releaseLease(runId, owner);
            logger.warn("Durable run {}: unreadable seed; not resuming", runId, e);
            return new ResumeOutcome.None("unreadable seed");
        }
        if (!principalOk.test(seed)) {
            journal.releaseLease(runId, owner);
            logger.warn("Durable run {}: resume refused — requester is not the run principal", runId);
            return new ResumeOutcome.Refused(runId);
        }
        var ctx = new DurableRunContext(runId, journal, true, owner, seed.userId(), seed.endpointPath());
        DurableRunScopeHolder.install(runId, ctx);
        logger.info("Durable run {}: resuming in replay mode ({})", runId,
                ownerChecked ? "owner" : "admin");
        return new ResumeOutcome.Resume(ctx, seed);
    }

    /**
     * The result of a {@link #beginResume} attempt: a resumable run with its
     * installed scope and seed, a security refusal, or nothing to resume.
     */
    public sealed interface ResumeOutcome {

        /** The run is resumable: drive it from {@code seed}, then {@code completeDrive(context, …)}. */
        record Resume(DurableRunContext context, EffectRecord.RunSeed seed) implements ResumeOutcome {
        }

        /** Nothing to resume (disabled, no seed, lease still live, or unreadable seed). */
        record None(String reason) implements ResumeOutcome {
        }

        /** A run exists but the requester is not its principal — refused (Inv #6). */
        record Refused(String runId) implements ResumeOutcome {
        }
    }
}
