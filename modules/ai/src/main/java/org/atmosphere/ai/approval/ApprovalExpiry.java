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
package org.atmosphere.ai.approval;

import java.time.Instant;

/**
 * Wall-clock backstop for journaled approval effects. The durable-approval
 * seam arms an expiry when it appends the {@code PENDING}
 * {@code EffectKind.APPROVAL} entry and cancels it once the live decision
 * lands, so an approval that outlives the process (crash, restart, nobody
 * looking) is marked {@code FAILED} by the scheduler when its deadline
 * passes — instead of expiring only "when someone happens to look at it".
 *
 * <p>The shipped implementation is {@code DurableApprovalExpiry} in the
 * {@code atmosphere-checkpoint} module, backed by {@code DurableTimerService}
 * (timers persist and re-arm across restarts). It is installed via
 * {@link ApprovalExpiryHolder} by the Spring / Quarkus durable-run wiring
 * when the crash-durable SQLite journal is active; without an installed
 * instance the live {@code future.get(timeout)} deadline remains the only
 * enforcement, exactly as before.</p>
 *
 * @since 4.0
 */
public interface ApprovalExpiry {

    /**
     * Arm the backstop for one pending approval effect.
     *
     * @param runId     the durable run
     * @param effectKey the {@code EffectKeys#approval} idempotency key
     * @param toolName  the gated tool, for diagnostics
     * @param expiresAt when the approval lapses
     */
    void arm(String runId, String effectKey, String toolName, Instant expiresAt);

    /** Disarm after the live decision (or live timeout) has been recorded. */
    void cancel(String runId, String effectKey);
}
