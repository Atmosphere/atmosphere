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
package org.atmosphere.ai.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.test.StreamIdleAssertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The stream idle bound on AgentScope: an agent whose event stream goes silent
 * ends the turn with one timeout in both dispatch modes, and the agent is
 * interrupted. The stub is the agent bean: with no tools or plan, the turn runs
 * on that shared bean, whose model call the dispose does not reach (see the
 * module README).
 */
class AgentScopeIdleTimeoutTest {

    @Test
    void aSilentAgentEndsTheTurnOnceAndIsInterrupted() throws Exception {
        var interrupts = new AtomicInteger();
        StreamIdleAssertions.assertSilentModelEndsTheTurnOnce(environment -> {
            var agent = mock(ReActAgent.class);
            when(agent.stream(anyList(), any(StreamOptions.class))).thenAnswer(inv -> Flux.<Event>never());
            doAnswer(inv -> {
                interrupts.incrementAndGet();
                return null;
            }).when(agent).interrupt();
            return new AgentScopeRuntimeContractTest.TestableAgentScopeRuntime(agent);
        }, new AgentExecutionContext("Hello", "You are helpful", "qwen-plus",
                null, "session-1", "user-1", null, List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null), "ReActAgent.interrupt()", interrupts::get);
    }
}
