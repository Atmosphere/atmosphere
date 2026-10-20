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
package org.atmosphere.verifier.execute;

import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * {@link ToolDispatcher} decorator that runs each tool call past an
 * {@link ApprovalGate} before delegating to the wrapped dispatcher — the
 * human-in-the-loop counterpart to {@link RegistryToolDispatcher}. The two
 * are drop-in interchangeable, so an application chooses its execution
 * posture (auto-dispatch vs. gated) at wiring time without touching the
 * {@link WorkflowExecutor} or the verifier chain.
 *
 * <p><strong>Fail closed (Correctness Invariant #6).</strong> The tool is
 * dispatched only on an explicit {@code true} from the gate. A gate that
 * returns {@code false} <em>or throws</em> denies the call with an
 * {@link ApprovalDeniedException}; an unreachable approver never results in
 * a tool firing.</p>
 *
 * <p><strong>Ownership (Correctness Invariant #1).</strong> The decorator
 * does not own the delegate dispatcher or the gate; it never closes them.</p>
 *
 * <p>Static verification still runs first and independently: a plan that
 * fails any {@link org.atmosphere.verifier.spi.PlanVerifier} is refused
 * before execution, so the gate only ever sees calls from an
 * already-verified plan. Gating is defense in depth on top of the static
 * proof, not a substitute for it.</p>
 */
public final class GatedToolDispatcher implements ToolDispatcher {

    private final ToolDispatcher delegate;
    private final ApprovalGate gate;
    private final BooleanSupplier cancelled;

    public GatedToolDispatcher(ToolDispatcher delegate, ApprovalGate gate) {
        this(delegate, gate, () -> false);
    }

    /**
     * A gated dispatcher for a run that can be cancelled: once
     * {@code cancelled} answers {@code true} a call is denied without asking
     * the gate, and a call the gate approved is not dispatched when the run
     * was cancelled while the approver decided. Pass the turn's
     * {@code StreamingSession::isTurnCancelled} from a {@code @Prompt} method.
     *
     * @param delegate  the dispatcher that runs approved calls
     * @param gate      the approval gate
     * @param cancelled whether the run was cancelled
     */
    public GatedToolDispatcher(ToolDispatcher delegate, ApprovalGate gate, BooleanSupplier cancelled) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
    }

    @Override
    public String dispatch(String toolName, Map<String, Object> args) {
        if (cancelled.getAsBoolean()) {
            throw new ApprovalDeniedException(toolName, "the run was cancelled");
        }
        boolean approved;
        try {
            approved = gate.approve(toolName, args);
        } catch (RuntimeException ex) {
            // Could not obtain a verdict — fail closed rather than fall
            // through to the tool.
            throw new ApprovalDeniedException(toolName,
                    "approval gate raised " + ex.getClass().getSimpleName()
                            + ": " + ex.getMessage(), ex);
        }
        if (!approved) {
            throw new ApprovalDeniedException(toolName, "denied by approval gate");
        }
        if (cancelled.getAsBoolean()) {
            throw new ApprovalDeniedException(toolName, "the run was cancelled");
        }
        return delegate.dispatch(toolName, args);
    }
}
