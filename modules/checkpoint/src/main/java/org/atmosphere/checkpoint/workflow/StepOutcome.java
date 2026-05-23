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
package org.atmosphere.checkpoint.workflow;

/**
 * What a {@link WorkflowStep} tells the {@link Workflow} orchestrator
 * after it runs. Sealed: every outcome is one of four cases, exhaustive
 * pattern matching gives the orchestrator a single switch.
 *
 * @param <S> the workflow's application-owned state type
 */
public sealed interface StepOutcome<S> {

    /** Advance to the next step with the given state. */
    static <S> StepOutcome<S> advance(S nextState) {
        return new Advance<>(nextState);
    }

    /**
     * Hibernate: persist the state and stop. The workflow can be resumed
     * later (same JVM or a fresh one) and will re-execute this step. This
     * is the durable-pause primitive — it does not park a thread; it
     * returns control to the caller.
     */
    static <S> StepOutcome<S> hibernate(S savedState) {
        return new Hibernate<>(savedState);
    }

    /** Complete the workflow successfully with this final state. */
    static <S> StepOutcome<S> done(S finalState) {
        return new Done<>(finalState);
    }

    /** Fail the workflow with a non-retryable error. */
    static <S> StepOutcome<S> fail(String reason) {
        return new Fail<>(reason);
    }

    record Advance<S>(S state) implements StepOutcome<S> { }

    record Hibernate<S>(S state) implements StepOutcome<S> { }

    record Done<S>(S state) implements StepOutcome<S> { }

    record Fail<S>(String reason) implements StepOutcome<S> { }
}
