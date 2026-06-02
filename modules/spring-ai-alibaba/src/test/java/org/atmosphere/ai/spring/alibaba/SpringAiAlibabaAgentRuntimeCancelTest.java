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
package org.atmosphere.ai.spring.alibaba;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins Spring AI Alibaba's <em>cooperative</em>
 * {@link org.atmosphere.ai.AiCapability#CANCELLATION}. Because
 * {@code ReactAgent.call} is a blocking, uninterruptible graph run, the
 * contract this runtime can honestly offer is: {@code cancel()} frees the
 * client and settles {@code whenDone()} immediately, <em>without</em> waiting
 * for the upstream call to return. This test holds the upstream call blocked
 * (and uninterruptible, swallowing the interrupt the way the real ReAct graph
 * does) and asserts the handle still settles on cancel — guarding against a
 * regression to the synchronous {@code ExecutionHandle.completed()} default,
 * which would block {@code executeWithHandle} until the call returns and defeat
 * disconnect-driven cancellation.
 */
class SpringAiAlibabaAgentRuntimeCancelTest {

    @Test
    void cancelFreesClientWithoutWaitingForBlockingUpstream() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new AtomicBoolean();

        var agent = mock(ReactAgent.class);
        try {
            when(agent.call(anyList())).thenAnswer(inv -> {
                started.countDown();
                // Faithfully simulate ReactAgent.call's uninterruptible blocking:
                // swallow interrupts and keep waiting until the test releases it,
                // so cancel() cannot rely on interruption to settle the handle.
                boolean interruptedSeen = false;
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ie) {
                        interruptedSeen = true;
                    }
                }
                if (interruptedSeen) {
                    Thread.currentThread().interrupt();
                }
                finished.set(true);
                return new AssistantMessage("late answer");
            });
        } catch (GraphRunnerException neverThrownByMock) {
            throw new AssertionError(neverThrownByMock);
        }

        var session = mock(StreamingSession.class);
        when(session.isClosed()).thenReturn(false);

        var runtime = new SpringAiAlibabaAgentRuntime();
        ExecutionHandle handle = runtime.doExecuteWithHandle(agent, textContext(), session);

        assertTrue(started.await(5, TimeUnit.SECONDS),
                "the blocking ReactAgent.call must start on the worker thread");
        assertFalse(handle.isDone(), "handle must stay live while the upstream call blocks");

        handle.cancel();

        // The defining best-effort property: whenDone() settles on cancel even
        // though the uninterruptible upstream call is still parked.
        handle.whenDone().get(5, TimeUnit.SECONDS);
        assertTrue(handle.isDone(), "cancel() must settle whenDone() without the upstream returning");
        assertFalse(finished.get(),
                "upstream call must still be blocked when cancel resolves the handle");
        verify(session, times(1)).complete();

        // CAS-guarded idempotency.
        handle.cancel();
        verify(session, times(1)).complete();

        // Let the orphaned worker unwind so the test JVM doesn't leak a parked VT.
        release.countDown();
    }

    private static AgentExecutionContext textContext() {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "qwen-plus",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }
}
