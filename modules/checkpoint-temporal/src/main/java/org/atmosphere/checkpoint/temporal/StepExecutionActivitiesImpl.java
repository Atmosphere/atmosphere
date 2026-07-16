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
package org.atmosphere.checkpoint.temporal;

import io.temporal.failure.ApplicationFailure;

/**
 * Activity implementation delegating to the run's live
 * {@link ExecutionSession}. A missing session is terminal (non-retryable):
 * steps execute in the JVM that started the run, so a run orphaned by a JVM
 * restart fails fast here — the application resumes by calling
 * {@code Workflow.run()} again, which picks up from the checkpoint store
 * exactly like the in-tree engine.
 */
public final class StepExecutionActivitiesImpl implements StepExecutionActivities {

    @Override
    public int resolveStartIndex(String executionId) {
        return session(executionId).resolveStartIndex();
    }

    @Override
    public StepReport executeStep(String executionId, String stepName) {
        return session(executionId).executeStep(stepName);
    }

    private static ExecutionSession<?> session(String executionId) {
        return ExecutionSessionRegistry.get(executionId)
                .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                        "no live execution session for id " + executionId
                                + " — steps run in the JVM that started the workflow; after a restart,"
                                + " resume by calling Workflow.run() again", "SessionNotFound"));
    }
}
