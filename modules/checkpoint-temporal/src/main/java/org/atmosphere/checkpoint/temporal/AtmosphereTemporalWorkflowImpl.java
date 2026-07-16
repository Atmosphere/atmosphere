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

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * Deterministic Temporal workflow driving an Atmosphere workflow's steps.
 * Control flow mirrors the in-tree step engine exactly: start at the resolved
 * resume index, run steps in order, and stop on the first Hibernate / Done /
 * Fail outcome. Retries are Temporal's: each step gets its own activity stub
 * whose {@link RetryOptions} translate the step's {@code maxRetries()} and
 * {@code retryDelay()} (fixed backoff). A step whose retry budget is
 * exhausted surfaces as a {@code FAILED} result — never as a thrown error —
 * matching {@code Workflow.runLocal} semantics.
 */
public final class AtmosphereTemporalWorkflowImpl implements AtmosphereTemporalWorkflow {

    @Override
    public TemporalWorkflowResult execute(TemporalWorkflowRequest request) {
        var resolver = Workflow.newActivityStub(StepExecutionActivities.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofMillis(request.stepTimeoutMillis()))
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                        .build());
        var start = resolver.resolveStartIndex(request.executionId());

        var steps = request.steps();
        var lastStepName = start > 0 && start <= steps.size() ? steps.get(start - 1).name() : "";
        for (var i = start; i < steps.size(); i++) {
            var spec = steps.get(i);
            lastStepName = spec.name();
            var stepStub = Workflow.newActivityStub(StepExecutionActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMillis(request.stepTimeoutMillis()))
                            .setRetryOptions(RetryOptions.newBuilder()
                                    .setMaximumAttempts(spec.maxAttempts())
                                    .setInitialInterval(Duration.ofMillis(Math.max(1L, spec.retryDelayMillis())))
                                    .setBackoffCoefficient(1.0)
                                    .build())
                            .build());
            StepReport report;
            try {
                report = stepStub.executeStep(request.executionId(), spec.name());
            } catch (ActivityFailure e) {
                var cause = e.getCause();
                return new TemporalWorkflowResult(TemporalWorkflowResult.Kind.FAILED, spec.name(),
                        "unhandled exception: " + (cause != null ? cause.getMessage() : e.getMessage()));
            }
            switch (report.kind()) {
                case ADVANCE -> { }
                case HIBERNATED -> {
                    return new TemporalWorkflowResult(TemporalWorkflowResult.Kind.HIBERNATED, spec.name(), null);
                }
                case COMPLETED -> {
                    return new TemporalWorkflowResult(TemporalWorkflowResult.Kind.COMPLETED, spec.name(), null);
                }
                case FAILED -> {
                    return new TemporalWorkflowResult(TemporalWorkflowResult.Kind.FAILED, spec.name(),
                            report.reason());
                }
            }
        }

        // Ran through every step without a Done — the last state is the
        // completion, same as the in-tree engine.
        return new TemporalWorkflowResult(TemporalWorkflowResult.Kind.COMPLETED, lastStepName, null);
    }
}
