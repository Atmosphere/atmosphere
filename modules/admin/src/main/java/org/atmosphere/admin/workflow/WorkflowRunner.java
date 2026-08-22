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

import org.atmosphere.ai.approval.ApprovalRegistry;
import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.coordinator.fleet.AgentCall;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.fleet.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Executes a saved {@link WorkflowManifest} against an
 * {@link AgentFleet} — the runtime the authoring surface previously
 * lacked. Nodes run in topological order; cyclic manifests are rejected
 * before any dispatch.
 *
 * <h2>Node semantics</h2>
 * <ul>
 *   <li>{@code agent} — dispatches one {@link AgentCall} through the fleet.
 *       Config keys: {@code agent} (defaults to the node id), {@code skill}
 *       (defaults to {@value #DEFAULT_SKILL}), {@code args} (map merged into
 *       the call arguments). The upstream text arrives as the {@code input}
 *       argument. A failed call marks the node FAILED but execution
 *       continues — downstream condition nodes can route on the failure.</li>
 *   <li>{@code condition} — passes its input through and activates only the
 *       outgoing edges whose condition matches: {@code success} /
 *       {@code failure} (all active upstream nodes succeeded, or not),
 *       {@code contains:<needle>} (substring of the input text),
 *       {@code default} (taken only when no other edge matched), blank/null
 *       (always taken). An unrecognized condition string deactivates the
 *       edge and warns — fail closed, never route on a typo.</li>
 *   <li>{@code fan-out} — passes its input to every successor; direct
 *       {@code agent} successors are dispatched concurrently in a single
 *       {@link AgentFleet#parallel} batch.</li>
 *   <li>{@code join} — concatenates the outputs of its active upstream
 *       nodes ({@code separator} config, default newline).</li>
 *   <li>{@code approval} — registers a {@link PendingApproval} with this
 *       runner's {@link ApprovalRegistry} and parks the executing (virtual)
 *       thread until it is resolved via {@link #resolveApproval} or the
 *       {@code timeoutSeconds} config (default {@value #DEFAULT_APPROVAL_TIMEOUT_SECONDS})
 *       expires. Denial and timeout prune every downstream node — an
 *       approval gate fails closed (Correctness Invariant #6).</li>
 *   <li>{@code output} — captures its input as the run's output.</li>
 *   <li>Unrecognized types pass their input through with a warning, per the
 *       documented {@link WorkflowManifest.NodeType} contract.</li>
 * </ul>
 *
 * <p>Runs execute synchronously on the caller's thread and always return a
 * complete {@link WorkflowRun}: every manifest node is accounted for as
 * SUCCEEDED, FAILED, or SKIPPED (Correctness Invariant #2). The runner
 * owns its {@link ApprovalRegistry} — it creates every approval it awaits
 * and no external lifecycle can complete or cancel them out from under a
 * run (Correctness Invariant #1).</p>
 */
public final class WorkflowRunner {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowRunner.class);

    /** Skill dispatched by {@code agent} nodes that do not configure one. */
    public static final String DEFAULT_SKILL = "run";

    /** Approval-gate wait bound when the node does not configure {@code timeoutSeconds}. */
    public static final int DEFAULT_APPROVAL_TIMEOUT_SECONDS = 300;

    /** Upper bound on manifest size accepted for execution (DoS guard, Invariant #3). */
    public static final int MAX_NODES = 500;

    private final Supplier<Map<String, AgentFleet>> fleetsSupplier;
    private final ApprovalRegistry approvals = new ApprovalRegistry();
    private final Map<String, PendingApproval> awaiting = new ConcurrentHashMap<>();

    /**
     * @param fleetsSupplier live view of the registered coordinator fleets,
     *                       read per run (e.g. the framework property bag
     *                       under {@code CoordinatorController.FLEETS_PROPERTY})
     */
    public WorkflowRunner(Supplier<Map<String, AgentFleet>> fleetsSupplier) {
        this.fleetsSupplier = fleetsSupplier != null ? fleetsSupplier : Map::of;
    }

    private Map<String, AgentFleet> fleets() {
        var fleets = fleetsSupplier.get();
        return fleets != null ? fleets : Map.of();
    }

    /**
     * The coordinator to run against when the caller names none: the single
     * registered fleet, or {@code null} when zero or several are registered
     * (the caller must then name one explicitly).
     */
    public String defaultCoordinator() {
        var fleets = fleets();
        return fleets.size() == 1 ? fleets.keySet().iterator().next() : null;
    }

    /** Approvals currently parked in {@link #run}, for the admin surface. */
    public List<PendingApproval> pendingApprovals() {
        return List.copyOf(awaiting.values());
    }

    /**
     * Resolve a parked approval gate.
     *
     * @param approvalId id from {@link #pendingApprovals}
     * @param approve    {@code true} to open the gate, {@code false} to deny
     * @return {@code true} when a pending approval was resolved,
     *         {@code false} for an unknown / already-resolved id
     */
    public boolean resolveApproval(String approvalId, boolean approve) {
        if (approvalId == null || !approvalId.matches("[A-Za-z0-9_\\-]+")) {
            return false;
        }
        var message = ApprovalRegistry.APPROVAL_PREFIX + approvalId
                + (approve ? "/approve" : "/deny");
        return approvals.resolve(message) == ApprovalRegistry.ResolveResult.RESOLVED;
    }

    /**
     * Execute the manifest against the named coordinator's fleet.
     *
     * @param manifest        the saved workflow
     * @param coordinatorName a coordinator registered in the fleet roster
     * @param input           run input; {@code input.get("input")} seeds the
     *                        root nodes' text
     * @return the completed run record — never {@code null}
     * @throws IllegalArgumentException for an unknown coordinator, a cyclic
     *                                  manifest, or one larger than {@link #MAX_NODES}
     */
    public WorkflowRun run(WorkflowManifest manifest, String coordinatorName, Map<String, Object> input) {
        var fleet = fleets().get(coordinatorName);
        if (fleet == null) {
            throw new IllegalArgumentException("unknown coordinator: " + coordinatorName
                    + " (registered: " + fleets().keySet() + ")");
        }
        if (manifest.nodes().size() > MAX_NODES) {
            throw new IllegalArgumentException("workflow has " + manifest.nodes().size()
                    + " nodes, limit is " + MAX_NODES);
        }
        var order = topologicalOrder(manifest);
        var startedAt = Instant.now();
        var runId = UUID.randomUUID().toString();
        var initialInput = String.valueOf(input != null ? input.getOrDefault("input", "") : "");

        var exec = new Execution(manifest, fleet, runId, initialInput);
        for (var node : order) {
            exec.execute(node);
        }

        var failed = exec.results.values().stream()
                .anyMatch(r -> r.status() == WorkflowRun.NodeStatus.FAILED);
        return new WorkflowRun(
                runId,
                manifest.id(),
                coordinatorName,
                failed ? WorkflowRun.Status.FAILED : WorkflowRun.Status.SUCCEEDED,
                List.copyOf(exec.results.values()),
                exec.runOutput(),
                startedAt,
                Instant.now());
    }

    /** Kahn's algorithm; rejects cycles before any dispatch. */
    private static List<WorkflowManifest.Node> topologicalOrder(WorkflowManifest manifest) {
        var inDegree = new HashMap<String, Integer>();
        var byId = new LinkedHashMap<String, WorkflowManifest.Node>();
        for (var node : manifest.nodes()) {
            byId.put(node.id(), node);
            inDegree.put(node.id(), 0);
        }
        for (var edge : manifest.edges()) {
            inDegree.merge(edge.to(), 1, Integer::sum);
        }
        var ready = new ArrayDeque<String>();
        for (var e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }
        var order = new ArrayList<WorkflowManifest.Node>(byId.size());
        while (!ready.isEmpty()) {
            var id = ready.poll();
            order.add(byId.get(id));
            for (var edge : manifest.edges()) {
                if (edge.from().equals(id) && inDegree.merge(edge.to(), -1, Integer::sum) == 0) {
                    ready.add(edge.to());
                }
            }
        }
        if (order.size() != byId.size()) {
            var cyclic = new HashSet<>(byId.keySet());
            order.forEach(n -> cyclic.remove(n.id()));
            throw new IllegalArgumentException("workflow contains a cycle through nodes " + cyclic);
        }
        return order;
    }

    /** Per-run mutable state; confined to the calling thread. */
    private final class Execution {

        private final WorkflowManifest manifest;
        private final AgentFleet fleet;
        private final String runId;
        private final String initialInput;
        private final Map<String, WorkflowRun.NodeResult> results = new LinkedHashMap<>();
        private final Set<WorkflowManifest.Edge> inactiveEdges = new HashSet<>();
        private final List<String> outputs = new ArrayList<>();
        private String lastOutput = "";

        Execution(WorkflowManifest manifest, AgentFleet fleet, String runId, String initialInput) {
            this.manifest = manifest;
            this.fleet = fleet;
            this.runId = runId;
            this.initialInput = initialInput;
        }

        void execute(WorkflowManifest.Node node) {
            if (results.containsKey(node.id())) {
                return; // dispatched early by a fan-out batch
            }
            var incoming = incomingEdges(node.id());
            if (!incoming.isEmpty() && incoming.stream().allMatch(this::isInactive)) {
                skip(node, "all upstream branches pruned");
                return;
            }
            var start = Instant.now();
            try {
                switch (node.type()) {
                    case WorkflowManifest.NodeType.AGENT -> runAgent(node, start);
                    case WorkflowManifest.NodeType.CONDITION -> runCondition(node, start);
                    case WorkflowManifest.NodeType.FAN_OUT -> runFanOut(node, start);
                    case WorkflowManifest.NodeType.JOIN -> runJoin(node, start);
                    case WorkflowManifest.NodeType.APPROVAL -> runApproval(node, start);
                    case WorkflowManifest.NodeType.OUTPUT -> runOutput(node, start);
                    default -> {
                        logger.warn("workflow {} node {}: unrecognized type '{}' — passing input through",
                                manifest.id(), node.id(), node.type());
                        succeed(node, inputText(node), start);
                    }
                }
            } catch (ApprovalRegistry.ApprovalTimeoutException e) {
                fail(node, "approval timed out: " + e.getMessage(), start);
                pruneDownstream(node);
            } catch (RuntimeException e) {
                logger.warn("workflow {} node {} failed: {}", manifest.id(), node.id(), e.toString());
                fail(node, e.toString(), start);
            }
        }

        private void runAgent(WorkflowManifest.Node node, Instant start) {
            var result = dispatch(node, inputText(node));
            recordAgentResult(node, result, start);
        }

        private AgentResult dispatch(WorkflowManifest.Node node, String inputText) {
            return fleet.pipeline(toCall(node, inputText));
        }

        private AgentCall toCall(WorkflowManifest.Node node, String inputText) {
            var config = node.config();
            var agentName = config.get("agent") instanceof String s && !s.isBlank() ? s : node.id();
            var skill = config.get("skill") instanceof String s && !s.isBlank() ? s : DEFAULT_SKILL;
            var args = new LinkedHashMap<String, Object>();
            if (config.get("args") instanceof Map<?, ?> configured) {
                configured.forEach((k, v) -> args.put(String.valueOf(k), v));
            }
            args.put("input", inputText);
            return fleet.call(agentName, skill, args);
        }

        private void recordAgentResult(WorkflowManifest.Node node, AgentResult result, Instant start) {
            if (result != null && result.success()) {
                succeed(node, result.text() != null ? result.text() : "", start);
            } else {
                var error = result != null && result.text() != null && !result.text().isBlank()
                        ? result.text()
                        : "agent call failed";
                // Failure flows downstream (edges stay active) so condition
                // nodes can route on it; the run's overall status is FAILED.
                fail(node, error, start);
            }
        }

        private void runCondition(WorkflowManifest.Node node, Instant start) {
            var text = inputText(node);
            var upstreamOk = upstreamSucceeded(node);
            var outgoing = manifest.edges().stream().filter(e -> e.from().equals(node.id())).toList();
            var matchedNonDefault = false;
            var defaults = new ArrayList<WorkflowManifest.Edge>();
            for (var edge : outgoing) {
                var condition = edge.condition();
                if (condition == null || condition.isBlank()) {
                    continue; // always active
                }
                var normalized = condition.strip().toLowerCase(Locale.ROOT);
                if (normalized.equals("default")) {
                    defaults.add(edge);
                    continue;
                }
                var taken = switch (normalized) {
                    case "success" -> upstreamOk;
                    case "failure" -> !upstreamOk;
                    default -> {
                        if (normalized.startsWith("contains:")) {
                            yield text.contains(condition.strip().substring("contains:".length()));
                        }
                        logger.warn("workflow {} node {}: unrecognized condition '{}' — edge not taken",
                                manifest.id(), node.id(), condition);
                        yield false;
                    }
                };
                if (taken) {
                    matchedNonDefault = true;
                } else {
                    inactiveEdges.add(edge);
                }
            }
            if (matchedNonDefault) {
                inactiveEdges.addAll(defaults);
            }
            succeed(node, text, start);
        }

        private void runFanOut(WorkflowManifest.Node node, Instant start) {
            var text = inputText(node);
            succeed(node, text, start);
            var agentSuccessors = manifest.edges().stream()
                    .filter(e -> e.from().equals(node.id()) && !isInactive(e))
                    .map(e -> nodeById(e.to()))
                    .filter(n -> WorkflowManifest.NodeType.AGENT.equals(n.type()))
                    .filter(n -> !results.containsKey(n.id()))
                    .toList();
            if (agentSuccessors.size() < 2) {
                return; // 0 or 1 — the normal topological pass handles it
            }
            var calls = agentSuccessors.stream()
                    .map(n -> toCall(n, text))
                    .toArray(AgentCall[]::new);
            var distinctAgents = new HashSet<String>();
            for (var call : calls) {
                distinctAgents.add(call.agentName());
            }
            if (distinctAgents.size() < calls.length) {
                // parallel() keys results by agent name — duplicate targets
                // would collide, so those successors run in topological order.
                logger.debug("workflow {} fan-out {}: duplicate agent targets, dispatching sequentially",
                        manifest.id(), node.id());
                return;
            }
            var batchStart = Instant.now();
            var byAgent = fleet.parallel(calls);
            for (int i = 0; i < agentSuccessors.size(); i++) {
                var successor = agentSuccessors.get(i);
                recordAgentResult(successor, byAgent.get(calls[i].agentName()), batchStart);
            }
        }

        private void runJoin(WorkflowManifest.Node node, Instant start) {
            var separator = node.config().get("separator") instanceof String s ? s : "\n";
            var joined = new StringBuilder();
            for (var edge : incomingEdges(node.id())) {
                if (isInactive(edge)) {
                    continue;
                }
                var upstream = results.get(edge.from());
                if (upstream != null && upstream.status() != WorkflowRun.NodeStatus.SKIPPED) {
                    if (!joined.isEmpty()) {
                        joined.append(separator);
                    }
                    joined.append(upstream.output());
                }
            }
            succeed(node, joined.toString(), start);
        }

        private void runApproval(WorkflowManifest.Node node, Instant start) {
            var config = node.config();
            var message = config.get("message") instanceof String s && !s.isBlank()
                    ? s
                    : "Approve workflow step " + node.id() + " of " + manifest.name() + "?";
            var timeoutSeconds = config.get("timeoutSeconds") instanceof Number n
                    ? Math.max(1, n.intValue())
                    : DEFAULT_APPROVAL_TIMEOUT_SECONDS;
            var pending = new PendingApproval(
                    ApprovalRegistry.generateId(),
                    "workflow:" + manifest.id() + ":" + node.id(),
                    Map.of("workflowId", manifest.id(), "nodeId", node.id(), "runId", runId),
                    message,
                    runId,
                    Instant.now().plusSeconds(timeoutSeconds));
            var future = approvals.register(pending);
            awaiting.put(pending.approvalId(), pending);
            boolean approved;
            try {
                approved = approvals.awaitApproval(pending, future);
            } finally {
                awaiting.remove(pending.approvalId());
            }
            if (approved) {
                succeed(node, inputText(node), start);
            } else {
                fail(node, "approval denied", start);
                pruneDownstream(node);
            }
        }

        private void runOutput(WorkflowManifest.Node node, Instant start) {
            var text = inputText(node);
            outputs.add(text);
            succeed(node, text, start);
        }

        /** Denied / timed-out gates block everything behind them (fail closed). */
        private void pruneDownstream(WorkflowManifest.Node node) {
            for (var edge : manifest.edges()) {
                if (edge.from().equals(node.id())) {
                    inactiveEdges.add(edge);
                }
            }
        }

        private String inputText(WorkflowManifest.Node node) {
            var incoming = incomingEdges(node.id());
            if (incoming.isEmpty()) {
                return initialInput;
            }
            var text = new StringBuilder();
            for (var edge : incoming) {
                if (isInactive(edge)) {
                    continue;
                }
                var upstream = results.get(edge.from());
                if (upstream != null && upstream.status() != WorkflowRun.NodeStatus.SKIPPED) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(upstream.output());
                }
            }
            return text.toString();
        }

        private boolean upstreamSucceeded(WorkflowManifest.Node node) {
            for (var edge : incomingEdges(node.id())) {
                if (isInactive(edge)) {
                    continue;
                }
                var upstream = results.get(edge.from());
                if (upstream != null && upstream.status() == WorkflowRun.NodeStatus.FAILED) {
                    return false;
                }
            }
            return true;
        }

        private List<WorkflowManifest.Edge> incomingEdges(String nodeId) {
            return manifest.edges().stream().filter(e -> e.to().equals(nodeId)).toList();
        }

        private boolean isInactive(WorkflowManifest.Edge edge) {
            if (inactiveEdges.contains(edge)) {
                return true;
            }
            var upstream = results.get(edge.from());
            return upstream != null && upstream.status() == WorkflowRun.NodeStatus.SKIPPED;
        }

        private WorkflowManifest.Node nodeById(String id) {
            for (var node : manifest.nodes()) {
                if (node.id().equals(id)) {
                    return node;
                }
            }
            throw new IllegalStateException("edge references unknown node " + id);
        }

        private void succeed(WorkflowManifest.Node node, String output, Instant start) {
            lastOutput = output;
            results.put(node.id(), new WorkflowRun.NodeResult(node.id(), node.type(),
                    WorkflowRun.NodeStatus.SUCCEEDED, output, null,
                    Duration.between(start, Instant.now())));
        }

        private void fail(WorkflowManifest.Node node, String error, Instant start) {
            results.put(node.id(), new WorkflowRun.NodeResult(node.id(), node.type(),
                    WorkflowRun.NodeStatus.FAILED, "", error,
                    Duration.between(start, Instant.now())));
        }

        private void skip(WorkflowManifest.Node node, String reason) {
            results.put(node.id(), new WorkflowRun.NodeResult(node.id(), node.type(),
                    WorkflowRun.NodeStatus.SKIPPED, "", reason, Duration.ZERO));
        }

        private String runOutput() {
            return outputs.isEmpty() ? lastOutput : String.join("\n", outputs);
        }
    }
}
