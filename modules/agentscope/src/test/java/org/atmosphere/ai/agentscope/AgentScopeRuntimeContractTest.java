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
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.test.AbstractAgentRuntimeContractTest;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TCK-style contract test for {@link AgentScopeAgentRuntime}.
 *
 * <p>Mockito 5 mocks final classes/methods out of the box, so the test
 * stubs the real {@link ReActAgent#stream(List, StreamOptions)} return
 * value with a canned {@link Flux} of {@link Event} carrying a single
 * "Hello world" {@link Msg}. The runtime's subscriber forwards the text
 * to the contract test's session and signals completion — the inherited
 * assertions verify {@code session.send()} was called and
 * {@code session.complete()} fired exactly once.</p>
 */
class AgentScopeRuntimeContractTest extends AbstractAgentRuntimeContractTest {

    @Override
    protected AgentRuntime createRuntime() {
        var agent = mock(ReActAgent.class);
        var msg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .textContent("Hello world")
                .build();
        var terminal = new Event(EventType.AGENT_RESULT, msg, true);
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(terminal));
        return new TestableAgentScopeRuntime(agent);
    }

    @Override
    protected AgentExecutionContext createTextContext() {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "qwen-plus",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @Override
    protected AgentExecutionContext createToolCallContext() {
        return null; // AgentScope runtime does not declare TOOL_CALLING
    }

    @Override
    protected AgentExecutionContext createErrorContext() {
        return null;
    }

    @Override
    protected Set<AiCapability> expectedCapabilities() {
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.SYSTEM_PROMPT,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.CONVERSATION_MEMORY,
                AiCapability.TOKEN_USAGE);
    }

    static class TestableAgentScopeRuntime extends AgentScopeAgentRuntime {
        TestableAgentScopeRuntime(ReActAgent agent) {
            setNativeClient(agent);
        }
    }
}
