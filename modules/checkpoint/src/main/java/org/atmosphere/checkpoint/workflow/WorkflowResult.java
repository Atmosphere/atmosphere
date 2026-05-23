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
 * Terminal status of a {@link Workflow#run} call. Three cases, mutually
 * exclusive:
 *
 * <ul>
 *   <li>{@link Completed} — the workflow ran to a terminal
 *       {@link StepOutcome#done} state.</li>
 *   <li>{@link Hibernated} — a step returned
 *       {@link StepOutcome#hibernate}; the state is persisted and a
 *       later {@code run} will resume from this point. The current call
 *       returns without holding any thread.</li>
 *   <li>{@link Failed} — a step returned {@link StepOutcome#fail} or
 *       exhausted its retry budget on an exception.</li>
 * </ul>
 */
public sealed interface WorkflowResult<S> {

    record Completed<S>(S finalState, String coordinationId) implements WorkflowResult<S> { }

    record Hibernated<S>(S savedState, String coordinationId, String lastStepName)
            implements WorkflowResult<S> { }

    record Failed<S>(S lastState, String coordinationId, String lastStepName, String reason)
            implements WorkflowResult<S> { }
}
