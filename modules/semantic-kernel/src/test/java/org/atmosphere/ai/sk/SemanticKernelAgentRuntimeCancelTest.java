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
package org.atmosphere.ai.sk;

import com.microsoft.semantickernel.services.chatcompletion.ChatCompletionService;
import com.microsoft.semantickernel.services.chatcompletion.ChatHistory;
import com.microsoft.semantickernel.services.chatcompletion.StreamingChatContent;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link SemanticKernelAgentRuntime#doExecuteWithHandle} returns
 * a live {@link org.atmosphere.ai.ExecutionHandle} whose {@code cancel()}
 * disposes the in-flight Reactor subscription — propagating an upstream cancel
 * to the Azure / OpenAI streaming call so a client disconnect aborts the
 * completion (Correctness Invariant #2). Pins the {@code CANCELLATION}
 * capability the runtime now advertises so a refactor that drops back to the
 * blocking {@code blockLast()} path (no disposable, no cancel) breaks the build.
 */
class SemanticKernelAgentRuntimeCancelTest {

    @Test
    void cancelDisposesInflightSubscriptionAndSettlesHandle() {
        var cancelled = new AtomicBoolean();
        // Never-emitting source so the subscription stays open until the test
        // triggers cancel; doOnCancel observes the upstream cancel that
        // disposable.dispose() must propagate.
        Flux<StreamingChatContent<?>> never =
                Flux.<StreamingChatContent<?>>never().doOnCancel(() -> cancelled.set(true));

        var service = mock(ChatCompletionService.class);
        when(service.getStreamingChatMessageContentsAsync(
                any(ChatHistory.class), any(), any())).thenAnswer(inv -> never);

        var session = mock(StreamingSession.class);
        when(session.isClosed()).thenReturn(false);

        var runtime = new SemanticKernelAgentRuntime();
        var handle = runtime.doExecuteWithHandle(service, textContext(), session);

        assertFalse(handle.isDone(), "handle must stay live while the SK stream is open");

        handle.cancel();

        assertTrue(cancelled.get(),
                "cancel() must dispose the Reactor subscription (upstream cancel)");
        assertTrue(handle.isDone(), "whenDone() must settle once cancelled");

        // CAS-guarded idempotency: a second/third cancel must not re-close the
        // session or re-dispose.
        handle.cancel();
        handle.cancel();
        verify(session, times(1)).complete();
    }

    private static AgentExecutionContext textContext() {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "gpt-4o-mini",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }
}
