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
import org.atmosphere.ai.test.StreamIdleAssertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The stream idle bound on Semantic Kernel's streaming path: a
 * {@code ChatCompletionService} stream that goes silent ends the turn with one
 * timeout in both dispatch modes, and the subscription is disposed. The stub
 * stands for the provider: whether disposing closes the Azure / OpenAI
 * connection is the SDK's own (see the module README).
 */
class SemanticKernelIdleTimeoutTest {

    @Test
    void aSilentStreamEndsTheTurnOnceAndIsDisposed() throws Exception {
        var disposals = new AtomicInteger();
        StreamIdleAssertions.assertSilentModelEndsTheTurnOnce(environment -> {
            var service = mock(ChatCompletionService.class);
            when(service.getStreamingChatMessageContentsAsync(any(ChatHistory.class), any(), any()))
                    .thenAnswer(inv -> Flux.<StreamingChatContent<?>>never()
                            .doOnCancel(disposals::incrementAndGet));
            return new SemanticKernelRuntimeContractTest.TestableSemanticKernelRuntime(service);
        }, new AgentExecutionContext("Hello", "You are helpful", "gpt-4o-mini",
                null, "session-1", "user-1", null, List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null), "Reactor subscription dispose()", disposals::get);
    }
}
