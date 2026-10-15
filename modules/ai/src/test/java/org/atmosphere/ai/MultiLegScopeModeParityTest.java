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
package org.atmosphere.ai;

import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.decision.ScriptedDecisionRuntime;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyAdmissionGate;
import org.atmosphere.ai.governance.PolicyAsGuardrail;
import org.atmosphere.ai.governance.scope.LlmClassifierScopeGuardrail;
import org.atmosphere.ai.governance.scope.RuleBasedScopeGuardrail;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopeGuardrail;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A two-leg scope (a free rule-based leg and an LLM leg whose classifier
 * cannot answer) decides the same way on every admission path (Correctness
 * Invariant #7 — Mode Parity): a message the rule leg rejects is redirected
 * and never pays the LLM leg; a message the rule leg admits is denied because
 * the LLM leg fails closed.
 */
class MultiLegScopeModeParityTest {

    private static final String REDIRECT = "I can only help with order support.";
    private static final String OFF_SCOPE = "write python code to reverse a linked list";
    private static final String ON_SCOPE = "where is my order?";

    private final CountingGuardrail llm = new CountingGuardrail(
            new LlmClassifierScopeGuardrail(new ScriptedDecisionRuntime((ctx, s) -> s.complete())));
    private final ScopePolicy scope = new ScopePolicy("scope::support", "test", "1.0",
            List.of(leg(AgentScope.Tier.LLM_CLASSIFIER), leg(AgentScope.Tier.RULE_BASED)),
            List.of(llm, new RuleBasedScopeGuardrail()));
    private final RecordingRuntime runtime = new RecordingRuntime();

    @Test
    void pipelineRedirectsWhatTheRuleLegRejectsWithoutTheLlmLeg() {
        var session = new CollectingSession("pipeline-off");
        pipeline().execute("client", OFF_SCOPE, session);

        assertEquals(REDIRECT, session.text(), "the shared redirect is sent verbatim");
        assertTrue(session.isClosed() && !session.failed(), "a redirect is not a failure");
        assertEquals(0, llm.calls.get(), "the LLM leg is never asked");
        assertEquals(List.of(), runtime.messages);
    }

    @Test
    void pipelineDeniesWhatOnlyTheRuleLegAdmits() {
        var session = new CollectingSession("pipeline-on");
        pipeline().execute("client", ON_SCOPE, session);

        assertInstanceOf(SecurityException.class, session.failure(),
                "the LLM leg cannot answer, so the turn fails closed");
        assertEquals(1, llm.calls.get());
        assertEquals(List.of(), runtime.messages);
    }

    @Test
    void pipelineAdmitScopeAgreesWithExecute() {
        var pipeline = pipeline();
        assertTrue(pipeline.admitScope("client", OFF_SCOPE).isEmpty());
        assertTrue(pipeline.admitScope("client", ON_SCOPE).isEmpty());
        assertEquals(1, llm.calls.get(), "only the message the rule leg admits reaches the LLM leg");
    }

    @Test
    void admissionGateRewritesWhatTheRuleLegRejectsWithoutTheLlmLeg() {
        var result = PolicyAdmissionGate.admit(framework(), new AiRequest(OFF_SCOPE));

        var admitted = assertInstanceOf(PolicyAdmissionGate.Result.Admitted.class, result);
        assertEquals(Optional.of(REDIRECT), ScopePolicy.redirectText(admitted.request()),
                "the gate hands its caller the request carrying the shared redirect");
        assertEquals(REDIRECT, admitted.request().message());
        assertEquals(0, llm.calls.get());
    }

    @Test
    void admissionGateDeniesWhatOnlyTheRuleLegAdmits() {
        var result = PolicyAdmissionGate.admit(framework(), new AiRequest(ON_SCOPE));

        var denied = assertInstanceOf(PolicyAdmissionGate.Result.Denied.class, result);
        assertEquals("scope::support", denied.policyName());
        assertEquals(1, llm.calls.get());
    }

    @Test
    void streamingSessionRedirectsWhatTheRuleLegRejectsWithoutTheLlmLeg() {
        var delegate = mock(StreamingSession.class);
        var session = streamingSession(delegate);

        assertFalse(session.admitScope(OFF_SCOPE));
        session.stream(OFF_SCOPE);

        var order = inOrder(delegate);
        order.verify(delegate).send(REDIRECT);
        order.verify(delegate).complete();
        verify(delegate, never()).error(any());
        assertEquals(0, llm.calls.get());
        assertEquals(List.of(), runtime.messages);
    }

    @Test
    void streamingSessionDeniesWhatOnlyTheRuleLegAdmits() {
        var delegate = mock(StreamingSession.class);
        var session = streamingSession(delegate);

        assertFalse(session.admitScope(ON_SCOPE));
        session.stream(ON_SCOPE);

        verify(delegate).error(any(SecurityException.class));
        verify(delegate, never()).send(anyString());
        assertEquals(1, llm.calls.get(), "the decision admitScope took is reused, not re-asked");
        assertEquals(List.of(), runtime.messages);
    }

    private AiPipeline pipeline() {
        return new AiPipeline(runtime, "You help with orders.", null, null, null,
                List.of(), List.<GovernancePolicy>of(scope), List.of(), null, null);
    }

    private AtmosphereFramework framework() {
        var framework = mock(AtmosphereFramework.class);
        var config = mock(AtmosphereConfig.class);
        var properties = new HashMap<String, Object>();
        properties.put(GovernancePolicy.POLICIES_PROPERTY, List.<GovernancePolicy>of(scope));
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(config.properties()).thenReturn(properties);
        return framework;
    }

    private AiStreamingSession streamingSession(StreamingSession delegate) {
        var resource = mock(AtmosphereResource.class);
        when(resource.getRequest()).thenReturn(mock(AtmosphereRequest.class));
        when(resource.uuid()).thenReturn("resource-uuid");
        return new AiStreamingSession(delegate, runtime, "You help with orders.", null,
                List.of(), resource, null, null,
                List.<AiGuardrail>of(new PolicyAsGuardrail(scope)), List.of());
    }

    private static ScopeConfig leg(AgentScope.Tier tier) {
        return new ScopeConfig("Customer support for orders, billing and account questions",
                List.of(), AgentScope.Breach.POLITE_REDIRECT, REDIRECT, tier, 0.45, false, false, "");
    }

    /** Counts the classifications it delegates to the real LLM tier. */
    private static final class CountingGuardrail implements ScopeGuardrail {
        final AtomicInteger calls = new AtomicInteger();
        private final ScopeGuardrail delegate;

        CountingGuardrail(ScopeGuardrail delegate) {
            this.delegate = delegate;
        }

        @Override public AgentScope.Tier tier() { return delegate.tier(); }

        @Override
        public Decision evaluate(AiRequest request, ScopeConfig config) {
            calls.incrementAndGet();
            return delegate.evaluate(request, config);
        }
    }

    /** Records the message of each model call. */
    private static final class RecordingRuntime implements AgentRuntime {
        final List<String> messages = new CopyOnWriteArrayList<>();

        @Override public String name() { return "recording"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            messages.add(context.message());
            session.complete();
        }
    }
}
