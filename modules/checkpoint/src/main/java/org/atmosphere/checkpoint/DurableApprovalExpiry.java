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

import org.atmosphere.ai.approval.ApprovalExpiry;
import org.atmosphere.ai.resume.EffectJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * The durable wall-clock backstop for journaled approvals — the production
 * consumer of {@link DurableTimerService} (registre#26). The durable-approval
 * seam arms a timer when it appends the {@code PENDING} approval effect and
 * cancels it once the live decision commits; when a timer fires — including
 * after a restart, because {@link DurableTimerService#start()} re-arms from
 * the persisted store — the still-undecided effect is marked {@code FAILED},
 * so a resumed run re-prompts instead of trusting a lapsed gate. This matches
 * the live-timeout semantics exactly: an expiry is the absence of a human
 * decision, not a decision to replay (Correctness Invariant #7).
 *
 * <p>A fired timer never demotes a decision: {@code EffectJournal.markFailed}
 * refuses to flip a {@code COMMITTED} effect, so the race between a firing
 * timer and a landing decision always resolves in the decision's favour.</p>
 *
 * <p>Ownership: this class registers a callback on a timer service it did not
 * create and closes nothing — the wiring that builds the store and service
 * also shuts them down (Correctness Invariant #1).</p>
 *
 * @since 4.0
 */
public final class DurableApprovalExpiry implements ApprovalExpiry {

    /** Timer {@code kind} under which approval-expiry timers are armed. */
    public static final String TIMER_KIND = "approval-expiry";

    private static final Logger logger = LoggerFactory.getLogger(DurableApprovalExpiry.class);

    private final DurableTimerService timers;
    private final EffectJournal journal;

    public DurableApprovalExpiry(DurableTimerService timers, EffectJournal journal) {
        this.timers = Objects.requireNonNull(timers, "timers");
        this.journal = Objects.requireNonNull(journal, "journal");
        timers.onFire(TIMER_KIND, this::expire);
    }

    @Override
    public void arm(String runId, String effectKey, String toolName, Instant expiresAt) {
        timers.schedule(new DurableTimer(timerId(runId, effectKey), expiresAt, TIMER_KIND,
                Map.of("runId", runId, "effectKey", effectKey, "toolName", toolName)));
    }

    @Override
    public void cancel(String runId, String effectKey) {
        timers.cancel(timerId(runId, effectKey));
    }

    private void expire(DurableTimer timer) {
        var runId = timer.payload().get("runId");
        var effectKey = timer.payload().get("effectKey");
        if (runId == null || effectKey == null) {
            logger.warn("approval-expiry timer {} fired without runId/effectKey payload — ignored",
                    timer.id());
            return;
        }
        if (journal.lookupCommitted(runId, effectKey).isPresent()) {
            return; // the human decided before the deadline — nothing to expire
        }
        journal.markFailed(runId, effectKey,
                "approval expired at " + timer.fireAt() + " without a decision (durable backstop)");
        logger.info("Approval effect {} in run {} expired without a decision (tool {}) — "
                + "marked FAILED; a resumed run will re-prompt",
                effectKey, runId, timer.payload().get("toolName"));
    }

    private static String timerId(String runId, String effectKey) {
        return TIMER_KIND + "/" + runId + "/" + effectKey;
    }
}
