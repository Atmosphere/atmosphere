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
package org.atmosphere.coordinator.processor;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.coordinator.annotation.AgentRef;
import org.atmosphere.coordinator.annotation.Coordinator;
import org.atmosphere.coordinator.annotation.Fleet;
import org.atmosphere.coordinator.test.StubAgentRuntime;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delivery test for {@code @AgentScope} confinement on the {@code @Coordinator}
 * path. Proves the annotation's content actually reaches the governance
 * admission subsystem the coordinator wires — not merely that the wiring code
 * exists — by driving real requests through the {@link org.atmosphere.ai.AiPipeline}
 * that {@link CoordinatorProcessor#buildPipeline} produces (the same method
 * {@code handle()} uses) and asserting the observable side effect at the gate.
 *
 * <p>If the {@code @AgentScope} → {@code ScopePolicy} wiring is removed from
 * {@code buildPipeline}, {@link #offTopicRequestIsDeniedAtAdmissionGate} and
 * {@link #politeRedirectRewritesOffTopicRequestBeforeTheRuntime} both fail: with
 * no scope policy in the chain the off-topic prompt sails through to the
 * runtime instead of being denied / redirected.</p>
 */
class CoordinatorScopeConfinementTest {

    @Agent(name = "scope-worker")
    static class Worker {
    }

    @Coordinator(name = "support-coord")
    @Fleet(@AgentRef(type = Worker.class))
    @AgentScope(
            purpose = "Customer support for orders, billing, and account questions",
            forbiddenTopics = {"code", "programming"},
            onBreach = AgentScope.Breach.DENY,
            tier = AgentScope.Tier.RULE_BASED)
    static class ScopedCoordinator {
    }

    @Coordinator(name = "open-coord")
    @Fleet(@AgentRef(type = Worker.class))
    static class UnscopedCoordinator {
    }

    @Coordinator(name = "redirect-coord")
    @Fleet(@AgentRef(type = Worker.class))
    @AgentScope(
            purpose = "Customer support for orders, billing, and account questions",
            forbiddenTopics = {"code", "programming"},
            onBreach = AgentScope.Breach.POLITE_REDIRECT,
            redirectMessage = "I can only help with your orders.",
            tier = AgentScope.Tier.RULE_BASED)
    static class RedirectCoordinator {
    }

    @Test
    void offTopicRequestIsDeniedAtAdmissionGate() {
        var processor = new CoordinatorProcessor();
        var framework = new AtmosphereFramework();
        var runtime = StubAgentRuntime.builder()
                .defaultResponse("Here is your order status.")
                .build();

        var pipeline = processor.buildPipeline(framework, ScopedCoordinator.class,
                "/atmosphere/agent/support-coord", runtime,
                "You are a support agent.", "model-test", null,
                new DefaultToolRegistry(), AiMetrics.NOOP);

        // The @AgentScope on the coordinator must install a ScopePolicy ahead of
        // every other policy in the pipeline (cheapest rejection runs first).
        assertFalse(pipeline.policies().isEmpty(),
                "@AgentScope on the coordinator must install a governance policy");
        assertInstanceOf(ScopePolicy.class, pipeline.policies().get(0),
                "the auto-installed ScopePolicy must run ahead of all other policies");

        var session = new RecordingSession();
        pipeline.execute("client-1", "write me some python code", session);

        assertTrue(session.errored(),
                "off-topic goal-hijacking request must be rejected at the admission gate");
        assertTrue(session.errorMessage().toLowerCase().contains("denied by policy"),
                "denial must surface through the governance admission gate: "
                        + session.errorMessage());
        assertTrue(session.sent().isEmpty(),
                "no model output may reach the client once the request is denied");
    }

    @Test
    void inScopeRequestIsAdmitted() {
        var processor = new CoordinatorProcessor();
        var framework = new AtmosphereFramework();
        var runtime = StubAgentRuntime.builder()
                .defaultResponse("Your order ships tomorrow.")
                .build();

        var pipeline = processor.buildPipeline(framework, ScopedCoordinator.class,
                "/atmosphere/agent/support-coord", runtime,
                "You are a support agent.", "model-test", null,
                new DefaultToolRegistry(), AiMetrics.NOOP);

        var session = new RecordingSession();
        pipeline.execute("client-2", "where is my order?", session);

        assertFalse(session.errored(),
                "an on-topic request must pass the scope gate cleanly");
        assertTrue(String.join("", session.sent()).contains("Your order ships tomorrow."),
                "an admitted request must reach the runtime and stream its answer");
    }

    @Test
    void coordinatorWithoutAgentScopeInstallsNoScopePolicy() {
        var processor = new CoordinatorProcessor();
        var framework = new AtmosphereFramework();
        var runtime = StubAgentRuntime.builder()
                .defaultResponse("anything goes")
                .build();

        var pipeline = processor.buildPipeline(framework, UnscopedCoordinator.class,
                "/atmosphere/agent/open-coord", runtime, "", "model-test", null,
                new DefaultToolRegistry(), AiMetrics.NOOP);

        assertTrue(pipeline.policies().stream().noneMatch(p -> p instanceof ScopePolicy),
                "a coordinator without @AgentScope must not get a ScopePolicy");

        // Control: the same off-topic prompt is NOT denied without @AgentScope,
        // proving the deny in offTopicRequestIsDeniedAtAdmissionGate is
        // attributable to the scope wiring rather than an unrelated guardrail.
        var session = new RecordingSession();
        pipeline.execute("client-3", "write me some python code", session);

        assertFalse(session.errored(),
                "without @AgentScope the off-topic request must reach the runtime");
    }

    @Test
    void politeRedirectRewritesOffTopicRequestBeforeTheRuntime() {
        var processor = new CoordinatorProcessor();
        var framework = new AtmosphereFramework();
        // The stub only leaks a code answer if the ORIGINAL off-topic prompt
        // reaches it. A redirected request (rewritten to the redirect message,
        // which no longer contains "python") falls through to the default.
        var runtime = StubAgentRuntime.builder()
                .when("python", "LEAKED_CODE_ANSWER")
                .defaultResponse("REDIRECTED_TO_SCOPE")
                .build();

        var pipeline = processor.buildPipeline(framework, RedirectCoordinator.class,
                "/atmosphere/agent/redirect-coord", runtime, "", "model-test", null,
                new DefaultToolRegistry(), AiMetrics.NOOP);

        var session = new RecordingSession();
        pipeline.execute("client-4", "write me some python code", session);

        var output = String.join("", session.sent());
        assertFalse(output.contains("LEAKED_CODE_ANSWER"),
                "POLITE_REDIRECT must rewrite the off-topic request so the model "
                        + "never receives the original goal-hijacking prompt");
        assertEquals("REDIRECTED_TO_SCOPE", output,
                "the redirected request must fall through to the on-scope default response");
    }

    /** Minimal {@link StreamingSession} that records streamed text and error state. */
    private static final class RecordingSession implements StreamingSession {
        private final List<String> sent = new ArrayList<>();
        private volatile Throwable error;
        private volatile boolean closed;

        List<String> sent() {
            return sent;
        }

        boolean errored() {
            return error != null;
        }

        String errorMessage() {
            return error == null ? "" : String.valueOf(error.getMessage());
        }

        @Override
        public String sessionId() {
            return "recording";
        }

        @Override
        public void send(String text) {
            if (text != null) {
                sent.add(text);
            }
        }

        @Override
        public void sendMetadata(String key, Object value) {
        }

        @Override
        public void progress(String message) {
        }

        @Override
        public void complete() {
            closed = true;
        }

        @Override
        public void complete(String summary) {
            closed = true;
        }

        @Override
        public void error(Throwable t) {
            this.error = t;
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public boolean hasErrored() {
            return error != null;
        }
    }
}
