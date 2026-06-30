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

/**
 * The kind of side effect recorded in an {@link EffectJournal}. Each kind
 * carries a different {@code resultPayload} shape (see {@link EffectRecord})
 * and a different idempotency-key derivation (see {@link EffectKeys}).
 *
 * @since 4.0
 */
public enum EffectKind {

    /**
     * The run's input seed, recorded once at the runtime dispatch boundary so a
     * crash-resume can re-drive the runtime directly without the original HTTP
     * request in memory. Payload is a {@link EffectRecord.RunSeed}.
     */
    RUN_INPUT,

    /**
     * One LLM round in the BuiltIn tool loop — the assistant text, the tool
     * calls it emitted, and the token usage. Payload is a
     * {@link EffectRecord.RecordedRound}. Lets BuiltIn replay a completed round
     * with no provider HTTP call.
     */
    LLM_ROUND,

    /**
     * One tool execution at the cross-runtime {@code executeWithApproval} choke
     * point. Payload is the exact result string the executor returned (already
     * encoding approved/denied/timed-out/governance-deny). Memoized so a replay
     * skips the executor and its approval gates.
     */
    TOOL_CALL,

    /**
     * A human-in-the-loop approval outcome bound to the originating user.
     * Payload is the approval decision string. Replay does not re-prompt.
     */
    APPROVAL
}
