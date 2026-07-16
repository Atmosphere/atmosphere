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

import io.temporal.client.WorkflowOptions;
import org.atmosphere.checkpoint.workflow.DurableExecutionProvider;
import org.atmosphere.checkpoint.workflow.Workflow;
import org.atmosphere.checkpoint.workflow.WorkflowResult;

import java.util.UUID;

/**
 * {@link DurableExecutionProvider} running {@code Workflow<S>} on a Temporal
 * service: add this module to the classpath and {@code Workflow.run()}
 * resolves it via ServiceLoader whenever a Temporal server is actually
 * reachable — no caller changes ("swap one Maven dep").
 *
 * <p>Execution model: one generic Temporal workflow
 * ({@link AtmosphereTemporalWorkflow}) drives the run; each step executes as
 * a Temporal activity <em>in the JVM that called {@code run()}</em>, against
 * the live step lambdas — application state never crosses the Temporal
 * payload boundary, so no serialization constraints are added on {@code S}.
 * Temporal owns per-step retries (translated from {@code maxRetries()} /
 * {@code retryDelay()}), timeouts, and execution history/visibility; the
 * {@code CheckpointStore} keeps the exact snapshot trail the in-tree engine
 * writes, so hibernation and cross-restart resume behave identically on both
 * engines (a fresh {@code run()} picks up from the last snapshot).</p>
 *
 * <p>Because steps need the live session, a run orphaned by a JVM restart
 * fails its next activity fast ({@code SessionNotFound}) rather than
 * hanging; the application resumes by calling {@code run()} again — the same
 * restart contract as the in-tree engine. Connection settings are documented
 * on {@link TemporalRuntime}.</p>
 */
public final class TemporalDurableExecutionProvider implements DurableExecutionProvider {

    @Override
    public String name() {
        return "temporal";
    }

    @Override
    public boolean isAvailable() {
        return TemporalRuntime.available();
    }

    @Override
    public <S> WorkflowResult<S> run(Workflow<S> workflow, S initialState) {
        if (!TemporalRuntime.available()) {
            throw new IllegalStateException(
                    "Temporal backend is not reachable; Workflow.run() only routes here when isAvailable() is true");
        }
        var executionId = UUID.randomUUID().toString();
        var session = new ExecutionSession<>(workflow, initialState);
        ExecutionSessionRegistry.register(executionId, session);
        try {
            var specs = workflow.steps().stream()
                    .map(s -> new StepSpec(s.name(), s.maxRetries() + 1, s.retryDelay().toMillis()))
                    .toList();
            var request = new TemporalWorkflowRequest(executionId, workflow.name(), specs,
                    TemporalRuntime.stepTimeoutMillis());
            var handle = TemporalRuntime.client().newWorkflowStub(AtmosphereTemporalWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setTaskQueue(TemporalRuntime.taskQueue())
                            .setWorkflowId("atmosphere-" + workflow.name() + "-" + executionId)
                            .build());
            var outcome = handle.execute(request);
            return switch (outcome.kind()) {
                case COMPLETED -> new WorkflowResult.Completed<>(
                        session.currentState(), workflow.coordinationId());
                case HIBERNATED -> new WorkflowResult.Hibernated<>(
                        session.currentState(), workflow.coordinationId(), outcome.lastStepName());
                case FAILED -> new WorkflowResult.Failed<>(
                        session.currentState(), workflow.coordinationId(),
                        outcome.lastStepName(), outcome.reason());
            };
        } finally {
            ExecutionSessionRegistry.remove(executionId);
        }
    }
}
