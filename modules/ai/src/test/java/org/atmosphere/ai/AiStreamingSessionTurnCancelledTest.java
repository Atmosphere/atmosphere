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

import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An operator's cancel, the prompter's departure or the idle sweep may land
 * while a {@code @Prompt} method is between model calls — a coordinator
 * dispatching its team, a tool loop between rounds. No model call is in
 * flight, so cancelling the call cuts nothing; the turn itself must stop:
 * the next {@code stream()} starts no model call and ends the turn
 * cancelled, and no tool is dispatched — through the turn's session or any
 * decorator of it.
 */
class AiStreamingSessionTurnCancelledTest {

    private final String uuid = "uuid-turn-cancelled-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        AiStreamingSession.removeAllForResource(uuid);
    }

    private static final class CountingRuntime implements AgentRuntime {
        final AtomicInteger calls = new AtomicInteger();

        @Override public String name() { return "counting"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            calls.incrementAndGet();
            session.complete();
        }

        @Override
        public ExecutionHandle executeWithHandle(AgentExecutionContext context, StreamingSession session) {
            calls.incrementAndGet();
            session.complete();
            return ExecutionHandle.completed();
        }
    }

    private AiStreamingSession session(CollectingSession client, AgentRuntime runtime) {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn(uuid);
        when(resource.getRequest()).thenReturn(mock(AtmosphereRequest.class));
        var session = new AiStreamingSession(client, runtime, "", null, List.of(), resource);
        AiStreamingSession.registerActive(session);
        return session;
    }

    private static ToolDefinition recordingTool(AtomicInteger ran) {
        return ToolDefinition.builder("commit_budget", "Commit the budget")
                .parameter("amount", "amount", "string")
                .executor(args -> {
                    ran.incrementAndGet();
                    return "committed";
                })
                .build();
    }

    @Test
    void anOperatorCancelBetweenModelCallsStopsTheNextModelCall() {
        var client = new CollectingSession("turn-" + UUID.randomUUID());
        var runtime = new CountingRuntime();
        var session = session(client, runtime);

        // The coordinator is dispatching its team: no model call in flight.
        AiStreamingSession.cancelInflightForResource(uuid);
        session.stream("write the executive briefing");

        assertEquals(0, runtime.calls.get(), "a model call started after the cancel");
        assertInstanceOf(RunCancelledException.class, client.failure(),
                "the turn ends in the cancelled terminal: " + client.failure());
        assertEquals(RunCancelledException.Reason.OPERATOR,
                ((RunCancelledException) client.failure()).reason());
    }

    @Test
    void noToolIsDispatchedThroughTheTurnOrADecoratorOfItAfterTheCancel() {
        var client = new CollectingSession("turn-" + UUID.randomUUID());
        var session = session(client, new CountingRuntime());
        var decorator = new DelegatingStreamingSession(session) { };
        // A runtime bridge's own session that only shares the turn's id.
        var bridgeSession = new CollectingSession(client.sessionId());
        var ran = new AtomicInteger();
        var tool = recordingTool(ran);
        ApprovalStrategy approve = (approval, s) -> ApprovalStrategy.ApprovalOutcome.APPROVED;

        assertEquals("committed", ToolExecutionHelper.executeWithApproval(
                "commit_budget", tool, Map.of("amount", "1"), decorator, approve));
        assertEquals(1, ran.get());

        session.cancelInflight(RunCancelledException.Reason.OPERATOR);

        assertTrue(session.isTurnCancelled());
        assertTrue(decorator.isTurnCancelled());
        assertTrue(bridgeSession.isTurnCancelled());
        for (var s : List.<StreamingSession>of(session, decorator, bridgeSession)) {
            assertEquals(ToolExecutionHelper.TURN_CANCELLED_RESULT, ToolExecutionHelper.executeWithApproval(
                    "commit_budget", tool, Map.of("amount", "50000"), s, approve));
        }
        assertEquals(1, ran.get(), "a tool ran after the cancel");
    }

    @Test
    void aDisconnectCancelsTheTurn() {
        var client = new CollectingSession("turn-" + UUID.randomUUID());
        var session = session(client, new CountingRuntime());

        AiStreamingSession.removeAllForResource(uuid);

        assertTrue(session.isTurnCancelled());
        assertTrue(AiStreamingSession.isTurnCancelled(client.sessionId()));
    }

    @Test
    void aCallerCancelOnlyCutsTheModelCallShort() {
        var client = new CollectingSession("turn-" + UUID.randomUUID());
        var session = session(client, new CountingRuntime());

        session.cancelInflight(RunCancelledException.Reason.CALLER);

        assertFalse(session.isTurnCancelled());
        assertFalse(AiStreamingSession.isTurnCancelled(client.sessionId()));
    }

    @Test
    void anotherTurnIsNotCancelled() {
        var cancelled = new CollectingSession("turn-" + UUID.randomUUID());
        var session = session(cancelled, new CountingRuntime());
        session.cancelInflight(RunCancelledException.Reason.OPERATOR);

        assertFalse(new CollectingSession("turn-" + UUID.randomUUID()).isTurnCancelled());
        assertFalse(AiStreamingSession.isTurnCancelled(null));
    }
}
