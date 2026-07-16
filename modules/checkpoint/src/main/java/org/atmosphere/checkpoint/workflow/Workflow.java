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

import org.atmosphere.checkpoint.CheckpointQuery;
import org.atmosphere.checkpoint.CheckpointStore;
import org.atmosphere.checkpoint.WorkflowSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable hibernating workflow primitive composing the
 * {@link CheckpointStore} SPI. A workflow is an ordered sequence of
 * {@link WorkflowStep} instances applied to an application-owned state
 * value of type {@code S}. After each step, the orchestrator persists a
 * {@link WorkflowSnapshot} so the next {@link #run} call can resume from
 * the same point — including across JVM restarts when the store is
 * persistent ({@code SqliteCheckpointStore}, etc.).
 *
 * <p>"Hibernation" here is a return-not-park primitive: a step returning
 * {@link StepOutcome#hibernate(Object)} causes {@link #run} to persist
 * and return {@link WorkflowResult.Hibernated}. No platform thread is
 * held while the workflow is dormant; the next invocation picks up where
 * the last one left off. This matches the shape of Cloudflare's
 * Dynamic Workflows ({@code ~300-line, MIT-licensed}) and Temporal's
 * activity primitive, scaled to the JVM and Atmosphere's existing
 * checkpoint plumbing.</p>
 *
 * <p>Resumability requires <em>step names</em> to be stable and unique
 * within the workflow — the snapshot metadata records the name of the
 * last completed step, and {@link #run} resumes from the next step
 * after that name. If steps reorder between runs the resume picks up
 * incorrectly; treat names as part of the workflow's persistent
 * contract.</p>
 *
 * <p>Steps must be idempotent. Two paths in {@link #run} can re-apply
 * work: (a) transient-failure <em>retries</em> re-invoke the
 * <em>same</em> step until it succeeds or the retry budget is exhausted
 * — this is what the "replay the last step" phrasing refers to;
 * (b) <em>resumes</em> after a restart pick up at the step
 * <em>after</em> the last successfully persisted one, so the resumed
 * step does not re-execute — but the prior step's external effect may
 * have already landed before the snapshot was written, so the step
 * still needs to be safe under repetition. In both cases, operations
 * the step performs must be safe to repeat (or the step must guard
 * against double-effect internally).</p>
 *
 * @param <S> the application-owned state type
 */
public final class Workflow<S> {

    private static final Logger logger = LoggerFactory.getLogger(Workflow.class);

    /** Metadata key recording the last step that completed successfully. */
    public static final String META_LAST_STEP = "workflow.lastStep";

    /** Metadata key recording the workflow's logical name (informational). */
    public static final String META_WORKFLOW_NAME = "workflow.name";

    private final String name;
    private final String coordinationId;
    private final List<WorkflowStep<S>> steps;
    private final CheckpointStore store;

    public Workflow(String name, String coordinationId,
                    List<WorkflowStep<S>> steps, CheckpointStore store) {
        this.name = Objects.requireNonNull(name, "name");
        this.coordinationId = Objects.requireNonNull(coordinationId, "coordinationId");
        this.steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        this.store = Objects.requireNonNull(store, "store");
        if (this.steps.isEmpty()) {
            throw new IllegalArgumentException("workflow must declare at least one step");
        }
        // Step names must be unique — they're the resume key.
        var seen = new java.util.HashSet<String>();
        for (var s : this.steps) {
            if (!seen.add(s.name())) {
                throw new IllegalArgumentException(
                        "duplicate step name '" + s.name() + "' in workflow " + name);
            }
        }
    }

    /**
     * Run (or resume) the workflow on the durable-execution backend
     * resolved via {@link DurableExecutionProvider#resolve()} — an external
     * engine adapter (Temporal, DBOS, Restate) registered through
     * {@code ServiceLoader} takes over when available; otherwise the
     * in-tree step engine executes. If a snapshot exists for this
     * coordination the run picks up at the step <em>after</em> the last
     * recorded {@code META_LAST_STEP}; otherwise it starts at the first
     * step with the provided {@code initialState}.
     */
    public WorkflowResult<S> run(S initialState) {
        return DurableExecutionProvider.resolve().run(this, initialState);
    }

    /**
     * Execute on the in-tree step engine — the path behind
     * {@link InMemoryDurableExecutionProvider}. External providers run the
     * steps on their own engine and must not call back into {@link #run},
     * which re-resolves the provider.
     */
    WorkflowResult<S> runLocal(S initialState) {
        var resume = loadLatest();
        S state;
        int startIndex;
        if (resume.isPresent()) {
            var snap = resume.get();
            state = snap.state();
            var lastStep = snap.metadata().getOrDefault(META_LAST_STEP, "");
            startIndex = indexAfter(lastStep);
            logger.info("Resuming workflow {} from after step '{}' (snapshot {})",
                    name, lastStep, snap.id());
        } else {
            state = initialState;
            startIndex = 0;
            // Seed an initial snapshot so external observers can see the
            // workflow is in flight before any step completes.
            persistAfterStep(state, "", false);
            logger.info("Starting workflow {} (coordination {})", name, coordinationId);
        }

        for (int i = startIndex; i < steps.size(); i++) {
            var step = steps.get(i);
            StepOutcome<S> outcome;
            try {
                outcome = executeWithRetry(step, state);
            } catch (Exception e) {
                logger.warn("Step {} of workflow {} exhausted retries: {}",
                        step.name(), name, e.toString());
                return new WorkflowResult.Failed<>(state, coordinationId, step.name(),
                        "unhandled exception: " + e);
            }

            switch (outcome) {
                case StepOutcome.Advance<S> a -> {
                    state = a.state();
                    persistAfterStep(state, step.name(), false);
                }
                case StepOutcome.Hibernate<S> h -> {
                    state = h.state();
                    persistAfterStep(state, step.name(), false);
                    return new WorkflowResult.Hibernated<>(state, coordinationId, step.name());
                }
                case StepOutcome.Done<S> d -> {
                    state = d.state();
                    persistAfterStep(state, step.name(), true);
                    return new WorkflowResult.Completed<>(state, coordinationId);
                }
                case StepOutcome.Fail<S> f -> {
                    return new WorkflowResult.Failed<>(state, coordinationId, step.name(),
                            f.reason());
                }
            }
        }

        // Ran through every step without a Done — treat the last state as
        // the completion. Callers that need explicit termination should
        // return Done from their final step.
        return new WorkflowResult.Completed<>(state, coordinationId);
    }

    private StepOutcome<S> executeWithRetry(WorkflowStep<S> step, S state) throws Exception {
        var attempt = 0;
        Exception last = null;
        while (attempt <= step.maxRetries()) {
            try {
                return step.execute(state);
            } catch (Exception e) {
                last = e;
                logger.warn("Step {} attempt {} failed: {}", step.name(), attempt, e.toString());
                attempt++;
                if (attempt <= step.maxRetries()) {
                    try {
                        Thread.sleep(step.retryDelay().toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ie;
                    }
                }
            }
        }
        throw last != null ? last : new IllegalStateException("retry loop exited without exception");
    }

    private Optional<WorkflowSnapshot<S>> loadLatest() {
        var matching = store.list(CheckpointQuery.builder()
                .coordinationId(coordinationId)
                .build());
        return matching.stream()
                .max(Comparator.comparing(WorkflowSnapshot::createdAt))
                .map(s -> {
                    @SuppressWarnings("unchecked")
                    var typed = (WorkflowSnapshot<S>) s;
                    return typed;
                });
    }

    private int indexAfter(String stepName) {
        if (stepName == null || stepName.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).name().equals(stepName)) {
                return i + 1;
            }
        }
        // Step not found — workflow definition changed between runs.
        // Restart from the beginning rather than skip silently.
        logger.warn("Last step '{}' not in workflow {} — restarting from step 0",
                stepName, name);
        return 0;
    }

    private void persistAfterStep(S state, String lastStepName, boolean done) {
        var snap = WorkflowSnapshot.<S>builder()
                .coordinationId(coordinationId)
                .agentName(name)
                .state(state)
                .metadata(Map.of(
                        META_LAST_STEP, lastStepName,
                        META_WORKFLOW_NAME, name,
                        "workflow.done", String.valueOf(done)))
                .build();
        store.save(snap);
    }

    public String name() {
        return name;
    }

    public String coordinationId() {
        return coordinationId;
    }

    public List<WorkflowStep<S>> steps() {
        return steps;
    }

    /** Delete every snapshot for this workflow's coordination. */
    public int deleteAllSnapshots() {
        return store.deleteCoordination(coordinationId);
    }
}
