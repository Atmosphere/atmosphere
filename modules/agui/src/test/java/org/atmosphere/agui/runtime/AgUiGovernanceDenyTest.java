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
package org.atmosphere.agui.runtime;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cross-surface governance regression: proves a deny-everything
 * {@link GovernancePolicy} refuses an AG-UI run and never reaches the runtime.
 * An AG-UI run flows {@code ResourceAgUiStreamingSession.stream()} →
 * {@code AiPipeline.execute()} → the pre-admission policy loop, which on a
 * {@code Deny} calls {@code session.error(SecurityException)} <em>before</em>
 * any runtime execution; the session emits an {@code AgUiEvent.RunError}
 * ({@code RUN_ERROR}) frame to the client.
 *
 * <p>This pins the bridge-governance parity established structurally (every
 * AG-UI run funnels through the shared pipeline) so a future refactor of the
 * AG-UI wiring cannot silently reintroduce the governance bypass — the
 * project's regression-test gate for the governance-on-all-surfaces fix.</p>
 */
class AgUiGovernanceDenyTest {

    @Test
    void denyPolicyRefusesAgUiRunWithRunErrorAndDoesNotReachRuntime() {
        var executed = new AtomicBoolean(false);
        AgentRuntime runtime = new RecordingRuntime(executed);
        var pipeline = new AiPipeline(runtime, null, null, null, null,
                List.of(), List.of(new DenyAllPolicy()), List.of(), null, null);

        // Capture the SSE frames the session writes to the client.
        var captured = new StringWriter();
        var sseWriter = new AgUiHandler.SseWriter(new PrintWriter(captured));
        var runContext = new RunContext("thread-1", "run-1", null, null, null, null);
        var delegate = mock(StreamingSession.class);
        when(delegate.sessionId()).thenReturn("s1");
        var session = new AgUiHandler.ResourceAgUiStreamingSession(
                delegate, sseWriter, runContext, pipeline);

        session.stream("please run the agent");

        assertFalse(executed.get(),
                "runtime must NOT execute when a governance policy denies the AG-UI run");
        var out = captured.toString();
        assertTrue(out.contains("RUN_ERROR"),
                "AG-UI deny must emit a RUN_ERROR event to the client; got: " + out);
        assertTrue(out.contains("denied by policy"),
                "the RUN_ERROR frame must carry the governance denial reason; got: " + out);
    }

    /** Records whether the runtime was reached; it must not be on a deny. */
    private static final class RecordingRuntime implements AgentRuntime {
        private final AtomicBoolean executed;

        RecordingRuntime(AtomicBoolean executed) {
            this.executed = executed;
        }

        @Override public String name() { return "recording"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() { return Set.of(AiCapability.TEXT_STREAMING); }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            executed.set(true);
            session.complete();
        }
    }

    /** Denies every request at pre-admission. */
    private static final class DenyAllPolicy implements GovernancePolicy {
        @Override public String name() { return "deny-all"; }
        @Override public String source() { return "code:test"; }
        @Override public String version() { return "test"; }
        @Override public PolicyDecision evaluate(PolicyContext context) {
            return PolicyDecision.deny("blocked by test policy");
        }
    }
}
