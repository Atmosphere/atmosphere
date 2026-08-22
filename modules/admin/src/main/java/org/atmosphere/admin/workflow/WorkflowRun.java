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
package org.atmosphere.admin.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Completed execution record of a {@link WorkflowManifest} produced by
 * {@link WorkflowRunner}. Every node in the manifest appears exactly once
 * in {@link #nodeResults} — executed, failed, or skipped — so an operator
 * can reconstruct what ran, what was pruned by a condition branch, and
 * what an approval gate blocked (Correctness Invariant #2 — every run
 * reaches a terminal, fully-described state).
 *
 * @param runId       unique identifier for this execution
 * @param workflowId  the manifest that was executed
 * @param coordinator the coordinator whose fleet dispatched the agent nodes
 * @param status      overall outcome — {@link Status#FAILED} when any node failed
 * @param nodeResults per-node outcomes in execution (topological) order
 * @param output      text captured by {@code output} nodes, or the last
 *                    executed node's output when the manifest has none
 * @param startedAt   when execution began
 * @param completedAt when execution reached its terminal state
 */
public record WorkflowRun(
        String runId,
        String workflowId,
        String coordinator,
        Status status,
        List<NodeResult> nodeResults,
        String output,
        Instant startedAt,
        Instant completedAt
) {

    public WorkflowRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(status, "status");
        nodeResults = nodeResults != null ? List.copyOf(nodeResults) : List.of();
        output = output != null ? output : "";
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
    }

    /** Overall run outcome. */
    public enum Status { SUCCEEDED, FAILED }

    /** Terminal state of a single node. */
    public enum NodeStatus { SUCCEEDED, FAILED, SKIPPED }

    /**
     * Outcome of one node.
     *
     * @param nodeId   the manifest node id
     * @param type     the manifest node type
     * @param status   terminal state
     * @param output   text the node produced (empty for skipped/failed nodes
     *                 unless the failure carries diagnostic text)
     * @param error    failure description, or {@code null} when the node
     *                 succeeded or was skipped
     * @param duration wall-clock execution time ({@link Duration#ZERO} for
     *                 skipped nodes)
     */
    public record NodeResult(
            String nodeId,
            String type,
            NodeStatus status,
            String output,
            String error,
            Duration duration
    ) {
        public NodeResult {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(status, "status");
            output = output != null ? output : "";
            duration = duration != null ? duration : Duration.ZERO;
        }
    }
}
