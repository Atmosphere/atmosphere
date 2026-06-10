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

import java.util.Map;

/**
 * The rich outcome of a human-in-the-loop approval. Extends the bare
 * {@link ApprovalStrategy.ApprovalOutcome} decision with two optional payloads
 * the reviewer can attach when approving:
 *
 * <ul>
 *   <li><strong>modified arguments</strong> — approve, but run the tool with
 *       these arguments instead of the ones the model proposed
 *       (approve-with-edited-args);</li>
 *   <li><strong>response payload</strong> — approve, but do <em>not</em> run the
 *       tool; return this structured / free-form value to the model as the tool
 *       result (the reviewer answers on the tool's behalf).</li>
 * </ul>
 *
 * <p>A plain approve/deny carries neither payload, so existing callers that only
 * look at {@link #outcome()} / {@link #approved()} behave exactly as before.</p>
 *
 * <p><strong>Scope:</strong> this is <em>session-scoped, in-memory</em> HITL —
 * the reviewer's payload lives on a parked virtual thread, not in durable
 * storage. It is not crash-durable; a restart drops pending approvals. Do not
 * conflate with durable approvals.</p>
 *
 * @param outcome           the approve / deny / timeout decision
 * @param modifiedArguments replacement tool arguments, or {@code null} when the
 *                          model's arguments stand
 * @param responsePayload   a substitute tool result, or {@code null} when the
 *                          tool should actually run
 */
public record ApprovalResolution(
        ApprovalStrategy.ApprovalOutcome outcome,
        Map<String, Object> modifiedArguments,
        Object responsePayload) {

    public ApprovalResolution {
        modifiedArguments = modifiedArguments == null ? null : Map.copyOf(modifiedArguments);
    }

    /** @return {@code true} when the decision was to approve. */
    public boolean approved() {
        return outcome == ApprovalStrategy.ApprovalOutcome.APPROVED;
    }

    /** @return {@code true} when the reviewer supplied replacement arguments. */
    public boolean hasModifiedArguments() {
        return modifiedArguments != null && !modifiedArguments.isEmpty();
    }

    /** @return {@code true} when the reviewer answered on the tool's behalf. */
    public boolean hasResponsePayload() {
        return responsePayload != null;
    }

    /** Plain approve — run the tool with the model's arguments. */
    public static ApprovalResolution approve() {
        return new ApprovalResolution(ApprovalStrategy.ApprovalOutcome.APPROVED, null, null);
    }

    /** Approve, but run the tool with these arguments instead. */
    public static ApprovalResolution approveWithArguments(Map<String, Object> arguments) {
        return new ApprovalResolution(ApprovalStrategy.ApprovalOutcome.APPROVED, arguments, null);
    }

    /** Approve, but return this value as the tool result without running the tool. */
    public static ApprovalResolution respond(Object payload) {
        return new ApprovalResolution(ApprovalStrategy.ApprovalOutcome.APPROVED, null, payload);
    }

    /** Deny — the tool does not run. */
    public static ApprovalResolution deny() {
        return new ApprovalResolution(ApprovalStrategy.ApprovalOutcome.DENIED, null, null);
    }

    /** The approval expired before the reviewer responded. */
    public static ApprovalResolution timedOut() {
        return new ApprovalResolution(ApprovalStrategy.ApprovalOutcome.TIMED_OUT, null, null);
    }
}
