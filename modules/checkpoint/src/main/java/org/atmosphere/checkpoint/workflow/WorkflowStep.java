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

import java.time.Duration;

/**
 * One step in a {@link Workflow}. A step receives the current workflow
 * state, does work, and returns a {@link StepOutcome} that drives the
 * orchestration (advance, hibernate, complete, fail).
 *
 * @param <S> the workflow's application-owned state type
 */
public interface WorkflowStep<S> {

    /** Stable identifier; used as the step's marker in the checkpoint. */
    String name();

    /** Run the step. Implementations should be idempotent — the same step
     *  may execute multiple times when retries fire or after a resume. */
    StepOutcome<S> execute(S state) throws Exception;

    /** Maximum retry attempts for this step (0 = no retry, default = 0). */
    default int maxRetries() {
        return 0;
    }

    /** Delay between retries (default = 100ms). */
    default Duration retryDelay() {
        return Duration.ofMillis(100);
    }
}
