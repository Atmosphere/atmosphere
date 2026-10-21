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
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.ApprovalTimedOutException;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A tool round that ended the turn (an approval that ran out with the turn's
 * time limit ends the session with an error) calls the model no more. Spring
 * AI Alibaba's ReAct graph owns the loop; the runtime builds its per-request
 * {@link ReactAgent} over a model that asks for a tool on its first call.
 */
class SpringAiAlibabaToolRoundTurnEndTest {

    private final AiEnvironment environment = AiEnvironment.create(key -> null);

    @AfterEach
    void close() {
        environment.close();
    }

    @Test
    void toolRoundThatEndedTheTurnMakesNoFollowUpModelCall() throws Exception {
        var model = new ToolThenTextChatModel();
        SpringAiAlibabaAgentRuntime.setChatModel(environment, model);
        var runtime = new SpringAiAlibabaAgentRuntime();
        runtime.attach(environment);
        var session = new CollectingSession();
        var tool = ToolDefinition.builder("add", "Add numbers")
                .parameter("a", "First", "integer")
                .executor(args -> {
                    session.error(new ApprovalTimedOutException(
                            "Approval for tool 'add' was not given before the turn's time limit", "apr_1", "add"));
                    return "{\"status\":\"timeout\"}";
                })
                .build();
        var context = new AgentExecutionContext(
                "add", "You are helpful", "qwen-plus", null, "session-1", "user-1", "conv-1",
                List.of(tool), null, null, List.of(), Map.of(), List.of(), null, null);

        runtime.doExecuteWithHandle(mock(ReactAgent.class), context, session)
                .whenDone().get(10, TimeUnit.SECONDS);

        assertTrue(session.isClosed(), "the tool round ended the turn");
        assertInstanceOf(ApprovalTimedOutException.class, session.failure(),
                "the turn ends with the approval that ran out, not a later error");
        assertEquals(1, model.calls.get(), "no follow-up model call once the turn ended");
    }

    /** A tool round that leaves the turn live still gets the model's answer. */
    @Test
    void liveTurnStillCallsTheModelAfterItsTools() throws Exception {
        var model = new ToolThenTextChatModel();
        SpringAiAlibabaAgentRuntime.setChatModel(environment, model);
        var runtime = new SpringAiAlibabaAgentRuntime();
        runtime.attach(environment);
        var session = new CollectingSession();
        var tool = ToolDefinition.builder("add", "Add numbers")
                .parameter("a", "First", "integer")
                .executor(args -> "2")
                .build();
        var context = new AgentExecutionContext(
                "add", "You are helpful", "qwen-plus", null, "session-2", "user-1", "conv-2",
                List.of(tool), null, null, List.of(), Map.of(), List.of(), null, null);

        runtime.doExecuteWithHandle(mock(ReactAgent.class), context, session)
                .whenDone().get(10, TimeUnit.SECONDS);

        assertEquals(2, model.calls.get(), "the model answers after the tool round");
        assertEquals("late answer", session.text());
    }

    /** Asks for the {@code add} tool on its first call, answers with text after. */
    private static final class ToolThenTextChatModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            if (calls.incrementAndGet() == 1) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "add", "{\"a\":1}")))
                        .build())));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("late answer"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }
    }
}
