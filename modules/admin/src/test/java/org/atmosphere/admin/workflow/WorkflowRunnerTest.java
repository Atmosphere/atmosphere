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

import org.atmosphere.coordinator.fleet.AgentCall;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.fleet.AgentProxy;
import org.atmosphere.coordinator.fleet.AgentResult;
import org.atmosphere.coordinator.fleet.RoutingSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (registre#1): workflow manifests could be authored and saved
 * but nothing executed them. {@link WorkflowRunner} now dispatches manifest
 * nodes through an {@link AgentFleet}; these tests pin the per-node-type
 * contract with a scripted fake fleet.
 */
class WorkflowRunnerTest {

    /** Scripted fleet: agent name → response function over the "input" arg. */
    static final class FakeFleet implements AgentFleet {
        final Map<String, Function<String, AgentResult>> script = new LinkedHashMap<>();
        final List<AgentCall> dispatched = new CopyOnWriteArrayList<>();
        final List<List<String>> parallelBatches = new CopyOnWriteArrayList<>();

        void respond(String agent, Function<String, String> fn) {
            script.put(agent, input -> new AgentResult(agent, "run", fn.apply(input),
                    Map.of(), Duration.ZERO, true));
        }

        void failWith(String agent, String error) {
            script.put(agent, input -> AgentResult.failure(agent, "run", error, Duration.ZERO));
        }

        private AgentResult invoke(AgentCall call) {
            dispatched.add(call);
            var fn = script.get(call.agentName());
            if (fn == null) {
                return AgentResult.failure(call.agentName(), call.skill(),
                        "no script for " + call.agentName(), Duration.ZERO);
            }
            return fn.apply(String.valueOf(call.args().get("input")));
        }

        @Override public AgentProxy agent(String name) {
            throw new UnsupportedOperationException("not used by the runner");
        }
        @Override public List<AgentProxy> agents() { return List.of(); }
        @Override public List<AgentProxy> available() { return List.of(); }
        @Override public AgentCall call(String agentName, String skill, Map<String, Object> args) {
            return new AgentCall(agentName, skill, args);
        }
        @Override public Map<String, AgentResult> parallel(AgentCall... calls) {
            var batch = new ArrayList<String>();
            var results = new LinkedHashMap<String, AgentResult>();
            for (var call : calls) {
                batch.add(call.agentName());
                results.put(call.agentName(), invoke(call));
            }
            parallelBatches.add(batch);
            return results;
        }
        @Override public AgentResult pipeline(AgentCall... calls) {
            AgentResult last = null;
            for (var call : calls) {
                last = invoke(call);
            }
            return last;
        }
        @Override public AgentResult route(AgentResult input, Consumer<RoutingSpec> spec) {
            throw new UnsupportedOperationException("not used by the runner");
        }
    }

    private static WorkflowManifest.Node node(String id, String type, Map<String, Object> config) {
        return new WorkflowManifest.Node(id, type, id, config);
    }

    private static WorkflowManifest manifest(List<WorkflowManifest.Node> nodes,
                                             List<WorkflowManifest.Edge> edges) {
        return WorkflowManifest.create("wf-test", "test workflow", "", nodes, edges, "alice");
    }

    private static WorkflowRun.NodeResult resultOf(WorkflowRun run, String nodeId) {
        return run.nodeResults().stream()
                .filter(r -> r.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void linearAgentChainThreadsTextThroughTheFleet() {
        var fleet = new FakeFleet();
        fleet.respond("summarize", input -> "summary(" + input + ")");
        fleet.respond("translate", input -> "fr(" + input + ")");
        var runner = new WorkflowRunner(() -> Map.of("ops", fleet));

        var run = runner.run(manifest(
                List.of(node("summarize", WorkflowManifest.NodeType.AGENT, Map.of()),
                        node("translate", WorkflowManifest.NodeType.AGENT, Map.of()),
                        node("done", WorkflowManifest.NodeType.OUTPUT, Map.of())),
                List.of(new WorkflowManifest.Edge("summarize", "translate", null),
                        new WorkflowManifest.Edge("translate", "done", null))),
                "ops", Map.of("input", "raw-text"));

        assertEquals(WorkflowRun.Status.SUCCEEDED, run.status());
        assertEquals("fr(summary(raw-text))", run.output());
        assertEquals(2, fleet.dispatched.size());
        assertEquals("summarize", fleet.dispatched.get(0).agentName());
        assertEquals("raw-text", fleet.dispatched.get(0).args().get("input"));
    }

    @Test
    void agentNodeConfigSelectsAgentSkillAndArgs() {
        var fleet = new FakeFleet();
        fleet.respond("researcher", input -> "ok");
        var runner = new WorkflowRunner(() -> Map.of("ops", fleet));

        runner.run(manifest(
                List.of(node("step1", WorkflowManifest.NodeType.AGENT,
                        Map.of("agent", "researcher", "skill", "deep-dive",
                                "args", Map.of("depth", 3)))),
                List.of()), "ops", Map.of("input", "topic"));

        var call = fleet.dispatched.get(0);
        assertEquals("researcher", call.agentName());
        assertEquals("deep-dive", call.skill());
        assertEquals(3, call.args().get("depth"));
        assertEquals("topic", call.args().get("input"));
    }

    @Test
    void cyclicManifestIsRejectedBeforeAnyDispatch() {
        var fleet = new FakeFleet();
        var runner = new WorkflowRunner(() -> Map.of("ops", fleet));
        var cyclic = manifest(
                List.of(node("a", WorkflowManifest.NodeType.AGENT, Map.of()),
                        node("b", WorkflowManifest.NodeType.AGENT, Map.of())),
                List.of(new WorkflowManifest.Edge("a", "b", null),
                        new WorkflowManifest.Edge("b", "a", null)));

        assertThrows(IllegalArgumentException.class,
                () -> runner.run(cyclic, "ops", Map.of()));
        assertTrue(fleet.dispatched.isEmpty(), "cycle must be rejected before dispatch");
    }

    @Test
    void unknownCoordinatorIsRejected() {
        var runner = new WorkflowRunner(() -> Map.of("ops", new FakeFleet()));
        var wf = manifest(List.of(node("a", WorkflowManifest.NodeType.AGENT, Map.of())), List.of());

        assertThrows(IllegalArgumentException.class, () -> runner.run(wf, "nope", Map.of()));
    }

    @Test
    void conditionRoutesOnContainsAndPrunesTheOtherBranch() {
        var fleet = new FakeFleet();
        fleet.respond("classify", input -> "spam detected");
        fleet.respond("quarantine", input -> "quarantined");
        fleet.respond("deliver", input -> "delivered");
        var runner = new WorkflowRunner(() -> Map.of("ops", fleet));

        var run = runner.run(manifest(
                List.of(node("classify", WorkflowManifest.NodeType.AGENT, Map.of()),
                        node("gate", WorkflowManifest.NodeType.CONDITION, Map.of()),
                        node("quarantine", WorkflowManifest.NodeType.AGENT, Map.of()),
                        node("deliver", WorkflowManifest.NodeType.AGENT, Map.of())),
                List.of(new WorkflowManifest.Edge("classify", "gate", null),
                        new WorkflowManifest.Edge("gate", "quarantine", "contains:spam"),
                        new WorkflowManifest.Edge("gate", "deliver", "default"))),
                "ops", Map.of("input", "mail"));

        assertEquals(WorkflowRun.Status.SUCCEEDED, run.status());
        assertEquals(WorkflowRun.NodeStatus.SUCCEEDED, resultOf(run, "quarantine").status());
        assertEquals(WorkflowRun.NodeStatus.SKIPPED, resultOf(run, "deliver").status());
    }

    @Test
    void agentFailureFlowsToTheFailureBranchAndFailsTheRun() {
        var fleet = new FakeFleet();
        fleet.failWith("flaky", "boom");
        fleet.respond("recover", input -> "recovered");
        fleet.respond("celebrate", input -> "party");
        var runner = new WorkflowRunner(() -> Map.of("ops", fleet));

        var run = runner.run(manifest(
                List.of(node("flaky", WorkflowManifest.NodeType.AGENT, Map.of()),
                        node("gate", WorkflowManifest.NodeType.CONDITION, Map.of()),
                        node("recover", WorkflowManifest.NodeType.AGENT, Map.of()),
                        node("celebrate", WorkflowManifest.NodeType.AGENT, Map.of())),
                List.of(new WorkflowManifest.Edge("flaky", "gate", null),
                        new WorkflowManifest.Edge("gate", "recover", "failure"),
                        new WorkflowManifest.Edge("gate", "celebrate", "success"))),
                "ops", Map.of());

        assertEquals(WorkflowRun.Status.FAILED, run.status(),
                "a failed node must surface in the overall status even when routed");
        assertEquals(WorkflowRun.NodeStatus.SUCCEEDED, resultOf(run, "recover").status());
        assertEquals(WorkflowRun.NodeStatus.SKIPPED, resultOf(run, "celebrate").status());
        assertEquals("boom", resultOf(run, "flaky").error());
    }

    @Test
    void fanOutDispatchesAgentSuccessorsInOneParallelBatchAndJoinConcatenates() {
        var fleet = new FakeFleet();
        fleet.respond("east", input -> "E:" + input);
        fleet.respond("west", input -> "W:" + input);
        var runner = new WorkflowRunner(() -> Map.of("ops", fleet));

        var run = runner.run(manifest(
                List.of(node("split", WorkflowManifest.NodeType.FAN_OUT, Map.of()),
                        node("east", WorkflowManifest.NodeType.AGENT, Map.of()),
                        node("west", WorkflowManifest.NodeType.AGENT, Map.of()),
                        node("merge", WorkflowManifest.NodeType.JOIN, Map.of("separator", "|"))),
                List.of(new WorkflowManifest.Edge("split", "east", null),
                        new WorkflowManifest.Edge("split", "west", null),
                        new WorkflowManifest.Edge("east", "merge", null),
                        new WorkflowManifest.Edge("west", "merge", null))),
                "ops", Map.of("input", "q"));

        assertEquals(WorkflowRun.Status.SUCCEEDED, run.status());
        assertEquals(1, fleet.parallelBatches.size(), "fan-out must use one parallel batch");
        assertEquals(List.of("east", "west"), fleet.parallelBatches.get(0));
        assertEquals("E:q|W:q", resultOf(run, "merge").output());
    }

    @Test
    void approvedGatePassesInputThrough() throws Exception {
        var fleet = new FakeFleet();
        fleet.respond("deploy", input -> "deployed:" + input);
        var runner = new WorkflowRunner(() -> Map.of("ops", fleet));

        var approver = Thread.ofVirtual().start(() -> {
            try {
                List<org.atmosphere.ai.approval.PendingApproval> pending = List.of();
                for (int i = 0; i < 200 && pending.isEmpty(); i++) {
                    TimeUnit.MILLISECONDS.sleep(25);
                    pending = runner.pendingApprovals();
                }
                assertFalse(pending.isEmpty(), "gate must publish its pending approval");
                assertTrue(runner.resolveApproval(pending.get(0).approvalId(), true));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        var run = runner.run(manifest(
                List.of(node("gate", WorkflowManifest.NodeType.APPROVAL,
                                Map.of("message", "ship it?", "timeoutSeconds", 30)),
                        node("deploy", WorkflowManifest.NodeType.AGENT, Map.of())),
                List.of(new WorkflowManifest.Edge("gate", "deploy", null))),
                "ops", Map.of("input", "v2"));
        approver.join();

        assertEquals(WorkflowRun.Status.SUCCEEDED, run.status());
        assertEquals("deployed:v2", run.output());
        assertTrue(runner.pendingApprovals().isEmpty(), "resolved approvals must not linger");
    }

    @Test
    void deniedGatePrunesEverythingBehindIt() throws Exception {
        var fleet = new FakeFleet();
        fleet.respond("deploy", input -> "deployed");
        var runner = new WorkflowRunner(() -> Map.of("ops", fleet));

        var denier = Thread.ofVirtual().start(() -> {
            try {
                List<org.atmosphere.ai.approval.PendingApproval> pending = List.of();
                for (int i = 0; i < 200 && pending.isEmpty(); i++) {
                    TimeUnit.MILLISECONDS.sleep(25);
                    pending = runner.pendingApprovals();
                }
                if (!pending.isEmpty()) {
                    runner.resolveApproval(pending.get(0).approvalId(), false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        var run = runner.run(manifest(
                List.of(node("gate", WorkflowManifest.NodeType.APPROVAL,
                                Map.of("timeoutSeconds", 30)),
                        node("deploy", WorkflowManifest.NodeType.AGENT, Map.of())),
                List.of(new WorkflowManifest.Edge("gate", "deploy", null))),
                "ops", Map.of());
        denier.join();

        assertEquals(WorkflowRun.Status.FAILED, run.status());
        assertEquals(WorkflowRun.NodeStatus.FAILED, resultOf(run, "gate").status());
        assertEquals(WorkflowRun.NodeStatus.SKIPPED, resultOf(run, "deploy").status(),
                "a denied gate must fail closed — nothing behind it may run");
        assertTrue(fleet.dispatched.isEmpty());
    }

    @Test
    void unknownNodeTypePassesInputThroughWithoutFailingTheRun() {
        var runner = new WorkflowRunner(() -> Map.of("ops", new FakeFleet()));

        var run = runner.run(manifest(
                List.of(node("mystery", "hologram", Map.of()),
                        node("done", WorkflowManifest.NodeType.OUTPUT, Map.of())),
                List.of(new WorkflowManifest.Edge("mystery", "done", null))),
                "ops", Map.of("input", "payload"));

        assertEquals(WorkflowRun.Status.SUCCEEDED, run.status());
        assertEquals("payload", run.output());
    }

    @Test
    void everyManifestNodeAppearsExactlyOnceInTheRunRecord() {
        var fleet = new FakeFleet();
        fleet.respond("a", input -> "A");
        var runner = new WorkflowRunner(() -> Map.of("ops", fleet));

        var run = runner.run(manifest(
                List.of(node("a", WorkflowManifest.NodeType.AGENT, Map.of()),
                        node("gate", WorkflowManifest.NodeType.CONDITION, Map.of()),
                        node("never", WorkflowManifest.NodeType.AGENT, Map.of())),
                List.of(new WorkflowManifest.Edge("a", "gate", null),
                        new WorkflowManifest.Edge("gate", "never", "failure"))),
                "ops", Map.of());

        assertEquals(3, run.nodeResults().size());
        assertNotNull(run.startedAt());
        assertNotNull(run.completedAt());
    }
}
