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
package org.atmosphere.ai.adk;

import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link AdkAgentRuntime#doExecuteWithHandle} closes a
 * per-request {@link Runner} when the returned {@link
 * org.atmosphere.ai.ExecutionHandle#cancel} is invoked, and leaves the
 * shared runner alone.
 *
 * <p>ADK has no {@code Runner.cancel(invocationId)} primitive, but
 * {@code Runner.close()} tears down the whole Runner — which is safe when
 * the runner has no other owners. The tool-bearing path in
 * {@code doExecuteWithHandle} allocates a fresh Runner per request
 * specifically so this close is safe. This test pins the contract so a
 * future refactor that accidentally shares the request runner cannot
 * regress the compute-reclamation guarantee.</p>
 */
class AdkAgentRuntimeCancelTest {

    @Test
    void perRequestRunnerIsClosedOnCancel() {
        var requestRunner = mockRunner();
        var shared = mockRunner();
        var streamingSession = mock(StreamingSession.class);
        when(streamingSession.isClosed()).thenReturn(false);

        var runtime = new AdkAgentRuntime() {
            @Override
            Runner buildRequestRunner(AgentExecutionContext context, StreamingSession session) {
                return requestRunner;
            }
        };

        var handle = runtime.doExecuteWithHandle(shared, contextWithTool(), streamingSession);
        assertFalse(handle.isDone(), "handle should still be live before cancel");

        handle.cancel();

        verify(requestRunner).close();
        verify(shared, never()).close();
    }

    @Test
    void sharedRunnerIsNotClosedOnCancel() {
        var shared = mockRunner();
        var streamingSession = mock(StreamingSession.class);
        when(streamingSession.isClosed()).thenReturn(false);

        var runtime = new AdkAgentRuntime() {
            @Override
            Runner buildRequestRunner(AgentExecutionContext context, StreamingSession session) {
                throw new AssertionError(
                        "no-tools, no-hint context must not trigger per-request runner build");
            }
        };

        var handle = runtime.doExecuteWithHandle(shared, contextNoTools(), streamingSession);
        handle.cancel();

        // Invariant #1 (Ownership): we must NOT close the shared runner because
        // concurrent callers still own it. The RxJava subscription gets disposed
        // (adapter.cancel()), but the backend compute on the shared runner keeps
        // running until it naturally terminates or ADK ships a per-invocation
        // cancel primitive.
        verify(shared, never()).close();
    }

    @Test
    void doubleCancelIsIdempotent() {
        var requestRunner = mockRunner();
        var streamingSession = mock(StreamingSession.class);
        when(streamingSession.isClosed()).thenReturn(false);

        var runtime = new AdkAgentRuntime() {
            @Override
            Runner buildRequestRunner(AgentExecutionContext context, StreamingSession session) {
                return requestRunner;
            }
        };

        var handle = runtime.doExecuteWithHandle(requestRunner, contextWithTool(), streamingSession);
        handle.cancel();
        handle.cancel();
        handle.cancel();

        // CAS-guarded: Runner.close() must fire exactly once even if the
        // caller calls cancel() three times. Guards against telemetry
        // double-counting and against Runner.close() being a non-idempotent
        // teardown on upstream changes.
        verify(requestRunner).close();
    }

    private static Runner mockRunner() {
        var runner = mock(Runner.class);
        var sessionService = mock(BaseSessionService.class);
        var session = mock(Session.class);
        when(runner.sessionService()).thenReturn(sessionService);
        when(runner.appName()).thenReturn("test-app");
        // Return a non-null session so ensureSession() skips the createSession
        // path — we only care about the cancel wiring here, not ADK's session
        // lifecycle.
        when(sessionService.getSession(anyString(), anyString(), anyString(), any()))
                .thenReturn(Maybe.just(session));
        // Never-ending flowable so the adapter stays subscribed and the handle
        // stays live until the test triggers cancel.
        when(runner.runAsync(anyString(), anyString(), any(Content.class)))
                .thenReturn(Flowable.<Event>never());
        // Runner.close() returns Completable — make it complete immediately so
        // the subscribe() callback in cancel() can fire its debug log.
        when(runner.close()).thenReturn(Completable.complete());
        return runner;
    }

    private static AgentExecutionContext contextWithTool() {
        // Tool-bearing context forces the per-request Runner path in
        // doExecuteWithHandle (needsPerRequestRunner == true).
        var noop = new ToolDefinition(
                "noop", "no-op test tool", List.of(), "string",
                args -> null, null, 0);
        return new AgentExecutionContext(
                "hello", "You are helpful", "gemini-2.5-flash",
                null, "session-1", "user-1", "conv-1",
                List.of(noop), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(), List.of(),
                ToolApprovalPolicy.annotated());
    }

    private static AgentExecutionContext contextNoTools() {
        return new AgentExecutionContext(
                "hello", "You are helpful", "gemini-2.5-flash",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(), List.of(),
                ToolApprovalPolicy.annotated());
    }

}
