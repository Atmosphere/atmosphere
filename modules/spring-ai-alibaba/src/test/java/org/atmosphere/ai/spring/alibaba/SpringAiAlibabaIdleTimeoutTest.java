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
import org.atmosphere.ai.test.StreamIdleAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The stream idle bound on Spring AI Alibaba: a {@code ReactAgent.call} that
 * sends nothing (the call is not streamed, so the bound covers the whole call)
 * ends the turn with one timeout in both dispatch modes, and the worker running
 * it is interrupted. The stub honours the interrupt; the real call ignores it and
 * runs on, holding the application's model client (see the module README).
 */
class SpringAiAlibabaIdleTimeoutTest {

    @Test
    void aSilentAgentCallEndsTheTurnOnceAndItsWorkerIsInterrupted() throws Exception {
        var interrupts = new AtomicInteger();
        StreamIdleAssertions.assertSilentModelEndsTheTurnOnce(environment -> {
            var agent = mock(ReactAgent.class);
            try {
                when(agent.call(anyList())).thenAnswer(inv -> {
                    try {
                        Thread.sleep(Duration.ofMinutes(1));
                    } catch (InterruptedException e) {
                        interrupts.incrementAndGet();
                        Thread.currentThread().interrupt();
                    }
                    return new AssistantMessage("late answer");
                });
            } catch (GraphRunnerException neverThrownByMock) {
                throw new AssertionError(neverThrownByMock);
            }
            var runtime = new SpringAiAlibabaAgentRuntime();
            runtime.configureNativeClient(agent);
            return runtime;
        }, new AgentExecutionContext("Hello", "You are helpful", "qwen-plus",
                null, "session-1", "user-1", null, List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null), "worker interrupt", interrupts::get);
    }
}
