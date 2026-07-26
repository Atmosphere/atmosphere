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
package org.atmosphere.a2a.runtime;

import org.atmosphere.a2a.annotation.AgentSkill;
import org.atmosphere.a2a.annotation.AgentSkillHandler;
import org.atmosphere.a2a.annotation.AgentSkillParam;
import org.atmosphere.a2a.registry.A2aRegistry;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.a2a.types.TaskState;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundary-admission regression for <b>raw</b> {@code @AgentSkill} handlers.
 *
 * <p>Framework A2A skills reach the governance plane through
 * {@code PipelineBackedSkill} → {@code AiPipeline}'s pre-admission loop (pinned
 * by {@code A2aGovernanceDenyTest}). A user-written raw handler is invoked
 * reflectively by {@link A2aProtocolHandler} and used to escape the policy
 * chain entirely; this test pins the message-level gate that closes it —
 * including that pipeline-backed skills are <em>not</em> double-admitted.</p>
 */
class A2aRawSkillAdmissionTest {

    private TaskManager taskManager;

    @AfterEach
    void tearDown() {
        if (taskManager != null) {
            taskManager.shutdown();
        }
    }

    /** A plain user-written handler — no pipeline, invoked reflectively. */
    static class RawAgent {
        static final AtomicBoolean EXECUTED = new AtomicBoolean();

        @AgentSkill(id = "raw-echo", name = "Raw Echo", description = "Echo the message")
        @AgentSkillHandler
        public void echo(TaskContext task, @AgentSkillParam(name = "message") String message) {
            EXECUTED.set(true);
            task.addArtifact(Artifact.text("echo: " + message));
            task.complete("done");
        }
    }

    /**
     * A handler whose instance is {@link PipelineBackedSkill} — the marker for
     * "my dispatch already runs the policy chain inside AiPipeline".
     */
    static class PipelineAgent implements PipelineBackedSkill {
        static final AtomicBoolean EXECUTED = new AtomicBoolean();

        @AgentSkill(id = "pipeline-echo", name = "Pipeline Echo", description = "Echo")
        @AgentSkillHandler
        public void echo(TaskContext task, @AgentSkillParam(name = "message") String message) {
            EXECUTED.set(true);
            task.addArtifact(Artifact.text("echo: " + message));
            task.complete("done");
        }
    }

    @Test
    void denyPolicyRefusesRawSkillDispatchAndNeverInvokesTheHandler() {
        RawAgent.EXECUTED.set(false);
        var policy = new CountingPolicy(PolicyDecision.deny("blocked by test policy"));
        var handler = handlerFor(new RawAgent(), policy);

        var response = handler.handleMessage(sendMessage("raw-echo", "hello"));

        assertFalse(RawAgent.EXECUTED.get(),
                "a denied raw @AgentSkill handler must NOT be invoked — admission "
                        + "runs before the reflective dispatch");
        assertEquals(1, policy.evaluations(),
                "the raw path must consult the policy chain exactly once");
        assertTrue(response.contains("denied by policy"),
                "the denial reason must surface to the caller; got: " + response);
        assertTrue(response.contains(TaskState.FAILED.wire()),
                "a denied dispatch must report the task in the " + TaskState.FAILED.wire()
                        + " state; got: " + response);
        assertTrue(response.contains("counting"),
                "the denial must name the policy that refused it; got: " + response);
    }

    @Test
    void admitPolicyLetsRawSkillDispatchThrough() {
        RawAgent.EXECUTED.set(false);
        var policy = new CountingPolicy(PolicyDecision.admit());
        var handler = handlerFor(new RawAgent(), policy);

        var response = handler.handleMessage(sendMessage("raw-echo", "hello"));

        assertTrue(RawAgent.EXECUTED.get(),
                "an admitted raw @AgentSkill handler must run normally");
        assertEquals(1, policy.evaluations(),
                "an admitted raw dispatch still consults the chain exactly once");
        assertFalse(response.contains("denied by policy"), response);
    }

    @Test
    void pipelineBackedSkillIsNotAdmittedTwice() {
        PipelineAgent.EXECUTED.set(false);
        // Deny-everything: if the handler wrongly gated the pipeline-backed
        // path too, this dispatch would be blocked here instead of inside
        // AiPipeline — and the policy would be evaluated a second time for
        // the same turn (double-charging stateful policies).
        var policy = new CountingPolicy(PolicyDecision.deny("blocked by test policy"));
        var handler = handlerFor(new PipelineAgent(), policy);

        handler.handleMessage(sendMessage("pipeline-echo", "hello"));

        assertEquals(0, policy.evaluations(),
                "a PipelineBackedSkill must NOT be admitted at the A2A boundary — "
                        + "AiPipeline already runs the same chain for that dispatch");
        assertTrue(PipelineAgent.EXECUTED.get(),
                "the pipeline-backed path is unchanged by the boundary gate");
    }

    /**
     * A policy whose {@code evaluate} throws is caught and converted to a deny
     * by {@code PolicyAdmissionGate} itself; this pins that the resulting deny
     * actually reaches — and stops — the A2A raw dispatch.
     */
    @Test
    void throwingPolicyDeniesRawDispatch() {
        RawAgent.EXECUTED.set(false);
        var handler = handlerFor(new RawAgent(), new ThrowingPolicy());

        var response = handler.handleMessage(sendMessage("raw-echo", "hello"));

        assertFalse(RawAgent.EXECUTED.get(),
                "a policy that throws must DENY the raw dispatch (fail-closed), "
                        + "never admit it");
        assertTrue(response.contains("denied by policy"),
                "the fail-closed denial must surface; got: " + response);
    }

    /**
     * Exercises {@link A2aPolicyGateway}'s <em>own</em> catch branch: when the
     * admission machinery itself blows up (here, a corrupt policy-chain object
     * that throws before any policy is evaluated), the gateway must DENY.
     * Admitting on an infrastructure fault would turn a broken governance
     * plane into a silent policy bypass — the fail-open bug this adapter was
     * explicitly written not to reproduce.
     */
    @Test
    void gatewayFailureDeniesRatherThanAdmits() {
        RawAgent.EXECUTED.set(false);
        var registry = new A2aRegistry();
        registry.scan(new RawAgent());
        taskManager = new TaskManager();
        var card = registry.buildAgentCard("test-agent", "Test", "1.0", "/a2a");
        var handler = new A2aProtocolHandler(registry, taskManager, card);

        var framework = new AtmosphereFramework();
        framework.getAtmosphereConfig().properties()
                .put(GovernancePolicy.POLICIES_PROPERTY, new BrokenPolicyList());
        handler.setFramework(framework);

        var response = handler.handleMessage(sendMessage("raw-echo", "hello"));

        assertFalse(RawAgent.EXECUTED.get(),
                "an admission-machinery failure must DENY the raw dispatch — "
                        + "fail-open here would be a policy bypass");
        assertTrue(response.contains("denied by policy"),
                "the gateway-level denial must surface; got: " + response);
        assertTrue(response.contains("a2a-policy-gateway"),
                "the denial must be attributed to the gateway so operators can "
                        + "tell it from a real policy deny; got: " + response);
    }

    @Test
    void withoutAFrameworkNoChainIsResolvableAndDispatchProceeds() {
        RawAgent.EXECUTED.set(false);
        var registry = new A2aRegistry();
        registry.scan(new RawAgent());
        taskManager = new TaskManager();
        var card = registry.buildAgentCard("test-agent", "Test", "1.0", "/a2a");
        // No setFramework(...) — the same posture as a deployment with no
        // policies installed. Runtime truth: nothing to enforce, so admit.
        var handler = new A2aProtocolHandler(registry, taskManager, card);

        handler.handleMessage(sendMessage("raw-echo", "hello"));

        assertTrue(RawAgent.EXECUTED.get(),
                "with no framework wired there is no policy chain to consult; "
                        + "dispatch must proceed rather than hard-fail");
    }

    @Test
    void gatewayIsActiveWhenTheAiModuleIsPresent() {
        // atmosphere-ai is a test-scope dependency here, so the reflective
        // linkage must resolve — otherwise every other assertion in this
        // class would pass vacuously through the "ai absent" admit branch.
        assertTrue(A2aPolicyGateway.isActive(),
                "PolicyAdmissionGate must be linkable in this module's test "
                        + "classpath, or these tests prove nothing");
    }

    // Helpers --------------------------------------------------------------

    private A2aProtocolHandler handlerFor(Object agent, GovernancePolicy policy) {
        var registry = new A2aRegistry();
        registry.scan(agent);
        taskManager = new TaskManager();
        var card = registry.buildAgentCard("test-agent", "Test", "1.0", "/a2a");
        var handler = new A2aProtocolHandler(registry, taskManager, card);
        handler.setFramework(frameworkWith(policy));
        return handler;
    }

    /** A framework whose config carries the installed policy chain. */
    private static AtmosphereFramework frameworkWith(GovernancePolicy policy) {
        var framework = new AtmosphereFramework();
        framework.getAtmosphereConfig().properties()
                .put(GovernancePolicy.POLICIES_PROPERTY, List.of(policy));
        return framework;
    }

    private static String sendMessage(String skillId, String text) {
        return """
                {"jsonrpc":"2.0","id":1,"method":"SendMessage","params":{"message":{
                  "messageId":"m1","role":"user","parts":[{"kind":"text","text":"%s"}],
                  "metadata":{"skillId":"%s"}}}}
                """.formatted(text, skillId);
    }

    /** Records how many times the chain was consulted, then returns a fixed decision. */
    private static final class CountingPolicy implements GovernancePolicy {
        private final PolicyDecision decision;
        private final AtomicInteger evaluations = new AtomicInteger();

        CountingPolicy(PolicyDecision decision) {
            this.decision = decision;
        }

        int evaluations() {
            return evaluations.get();
        }

        @Override public String name() { return "counting"; }
        @Override public String source() { return "code:test"; }
        @Override public String version() { return "test"; }

        @Override
        public PolicyDecision evaluate(PolicyContext context) {
            evaluations.incrementAndGet();
            return decision;
        }
    }

    /**
     * A corrupt policy-chain object: non-empty, but explodes the moment the
     * admission gate sizes it. Simulates a governance plane that is present
     * but unusable, which must deny rather than admit.
     */
    private static final class BrokenPolicyList extends java.util.AbstractList<Object> {
        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public int size() {
            throw new IllegalStateException("policy chain is corrupt");
        }

        @Override
        public Object get(int index) {
            throw new IllegalStateException("policy chain is corrupt");
        }
    }

    /** Simulates a broken policy — the gate must deny, not admit. */
    private static final class ThrowingPolicy implements GovernancePolicy {
        @Override public String name() { return "throwing"; }
        @Override public String source() { return "code:test"; }
        @Override public String version() { return "test"; }

        @Override
        public PolicyDecision evaluate(PolicyContext context) {
            throw new IllegalStateException("policy backend unavailable");
        }
    }

    @Test
    void admittedRawDispatchReportsSuccessNotFailure() {
        RawAgent.EXECUTED.set(false);
        var handler = handlerFor(new RawAgent(), new CountingPolicy(PolicyDecision.admit()));

        var response = handler.handleMessage(sendMessage("raw-echo", "hello"));

        assertNotEquals(-1, response.indexOf("echo: hello"),
                "the admitted handler's artifact must reach the caller; got: " + response);
        assertFalse(response.contains(TaskState.FAILED.wire()),
                "an admitted dispatch must not be reported as failed; got: " + response);
    }
}
