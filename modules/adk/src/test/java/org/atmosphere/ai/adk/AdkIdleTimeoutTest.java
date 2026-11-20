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
import org.atmosphere.ai.test.StreamIdleAssertions;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The stream idle bound on ADK: a per-request {@code Runner} whose event
 * stream goes silent ends the turn with one timeout in both dispatch modes, and
 * the runner is closed. The stub stands for Gemini: whether closing the runner
 * aborts the in-flight HTTP call is ADK's and genai's own (see the module README).
 */
class AdkIdleTimeoutTest {

    @Test
    void aSilentRunnerEndsTheTurnOnceAndIsClosed() throws Exception {
        var closes = new AtomicInteger();
        StreamIdleAssertions.assertSilentModelEndsTheTurnOnce(environment -> {
            var shared = runner(new AtomicInteger());
            var requestRunner = runner(closes);
            var runtime = new AdkAgentRuntime() {
                @Override
                Runner buildRequestRunner(AgentExecutionContext context, StreamingSession session) {
                    return requestRunner;
                }
            };
            runtime.configureNativeClient(shared);
            return runtime;
        }, contextWithTool(), "per-request Runner.close()", closes::get);
    }

    private static Runner runner(AtomicInteger closes) {
        var runner = mock(Runner.class);
        var sessionService = mock(BaseSessionService.class);
        var session = mock(Session.class);
        when(runner.sessionService()).thenReturn(sessionService);
        when(runner.appName()).thenReturn("test-app");
        when(sessionService.getSession(anyString(), anyString(), anyString(), any()))
                .thenReturn(Maybe.just(session));
        when(runner.runAsync(anyString(), anyString(), any(Content.class))).thenReturn(Flowable.<Event>never());
        when(runner.close()).thenAnswer(inv -> {
            closes.incrementAndGet();
            return Completable.complete();
        });
        return runner;
    }

    private static AgentExecutionContext contextWithTool() {
        // A tool forces the per-request Runner, the one a cancel closes.
        var noop = new ToolDefinition("noop", "no-op test tool", List.of(), "string", args -> null, null, 0);
        return new AgentExecutionContext("hello", "You are helpful", "gemini-2.5-flash",
                null, "session-1", "user-1", null, List.of(noop), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(), List.of(), ToolApprovalPolicy.annotated());
    }
}
