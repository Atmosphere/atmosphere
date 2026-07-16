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

import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import org.atmosphere.checkpoint.CheckpointQuery;
import org.atmosphere.checkpoint.WorkflowSnapshot;
import org.atmosphere.checkpoint.workflow.StepOutcome;
import org.atmosphere.checkpoint.workflow.Workflow;
import org.atmosphere.checkpoint.workflow.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Live, in-JVM side of one Temporal-driven run: holds the {@link Workflow}
 * object, its current state, and the checkpoint bookkeeping. Resume and
 * persistence semantics deliberately mirror the in-tree step engine
 * ({@code Workflow.runLocal}) — same snapshot metadata keys, same seed
 * snapshot on fresh start, same "restart from 0 when the last step name is
 * unknown" rule — so both engines leave an identical snapshot trail (Mode
 * Parity, Correctness Invariant #7). The parity is pinned by
 * {@code TemporalDurableExecutionProviderTest}.
 */
final class ExecutionSession<S> {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionSession.class);

    private final Workflow<S> workflow;
    private final Map<String, WorkflowStep<S>> stepsByName;
    private S state;

    ExecutionSession(Workflow<S> workflow, S initialState) {
        this.workflow = workflow;
        this.state = initialState;
        this.stepsByName = workflow.steps().stream()
                .collect(Collectors.toUnmodifiableMap(WorkflowStep::name, Function.identity()));
    }

    synchronized int resolveStartIndex() {
        var resume = loadLatest();
        if (resume.isPresent()) {
            var snap = resume.get();
            state = snap.state();
            var lastStep = snap.metadata().getOrDefault(Workflow.META_LAST_STEP, "");
            logger.info("Resuming workflow {} on Temporal from after step '{}' (snapshot {})",
                    workflow.name(), lastStep, snap.id());
            return indexAfter(lastStep);
        }
        // Seed an initial snapshot so external observers can see the
        // workflow is in flight before any step completes.
        persistAfterStep("", false);
        logger.info("Starting workflow {} on Temporal (coordination {})",
                workflow.name(), workflow.coordinationId());
        return 0;
    }

    synchronized StepReport executeStep(String stepName) {
        var step = stepsByName.get(stepName);
        if (step == null) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "step '" + stepName + "' not found in workflow " + workflow.name(), "StepNotFound");
        }
        StepOutcome<S> outcome;
        try {
            outcome = step.execute(state);
        } catch (Exception e) {
            // Temporal owns the retry budget (the per-step RetryOptions built
            // by the workflow impl) — fail the activity and let it decide.
            throw Activity.wrap(e);
        }
        return switch (outcome) {
            case StepOutcome.Advance<S> a -> {
                state = a.state();
                persistAfterStep(stepName, false);
                yield new StepReport(StepReport.Kind.ADVANCE, null);
            }
            case StepOutcome.Hibernate<S> h -> {
                state = h.state();
                persistAfterStep(stepName, false);
                yield new StepReport(StepReport.Kind.HIBERNATED, null);
            }
            case StepOutcome.Done<S> d -> {
                state = d.state();
                persistAfterStep(stepName, true);
                yield new StepReport(StepReport.Kind.COMPLETED, null);
            }
            // No snapshot on Fail — parity with the in-tree engine.
            case StepOutcome.Fail<S> f -> new StepReport(StepReport.Kind.FAILED, f.reason());
        };
    }

    synchronized S currentState() {
        return state;
    }

    private Optional<WorkflowSnapshot<S>> loadLatest() {
        var matching = workflow.store().list(CheckpointQuery.builder()
                .coordinationId(workflow.coordinationId())
                .build());
        return matching.stream()
                .max(Comparator.comparing(WorkflowSnapshot::createdAt))
                .map(s -> {
                    // Same unavoidable erased-store cast as the in-tree engine's loadLatest.
                    @SuppressWarnings("unchecked")
                    var typed = (WorkflowSnapshot<S>) s;
                    return typed;
                });
    }

    private int indexAfter(String stepName) {
        if (stepName == null || stepName.isEmpty()) {
            return 0;
        }
        var steps = workflow.steps();
        for (var i = 0; i < steps.size(); i++) {
            if (steps.get(i).name().equals(stepName)) {
                return i + 1;
            }
        }
        // Step not found — workflow definition changed between runs.
        // Restart from the beginning rather than skip silently.
        logger.warn("Last step '{}' not in workflow {} — restarting from step 0",
                stepName, workflow.name());
        return 0;
    }

    private void persistAfterStep(String lastStepName, boolean done) {
        var snap = WorkflowSnapshot.<S>builder()
                .coordinationId(workflow.coordinationId())
                .agentName(workflow.name())
                .state(state)
                .metadata(Map.of(
                        Workflow.META_LAST_STEP, lastStepName,
                        Workflow.META_WORKFLOW_NAME, workflow.name(),
                        Workflow.META_DONE, String.valueOf(done)))
                .build();
        workflow.store().save(snap);
    }
}
