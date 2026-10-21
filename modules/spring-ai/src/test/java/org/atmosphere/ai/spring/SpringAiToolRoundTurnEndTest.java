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
package org.atmosphere.ai.spring;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.ApprovalTimedOutException;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tool round that ended the turn (an approval that ran out with the turn's
 * time limit ends the session with an error) calls the model no more. Spring
 * AI's {@code ToolCallingAdvisor} owns the loop; the runtime drives the real
 * {@link ChatClient} over a model that asks for a tool on its first call.
 */
class SpringAiToolRoundTurnEndTest {

    @Test
    void toolRoundThatEndedTheTurnMakesNoFollowUpModelCall() throws Exception {
        var model = new ToolThenTextChatModel();
        var runtime = new TestableRuntime(ChatClient.builder(model).build());
        var session = new CollectingSession();
        var tool = ToolDefinition.builder("add", "Add numbers")
                .parameter("a", "First", "integer")
                .executor(args -> {
                    session.error(new ApprovalTimedOutException(
                            "Approval for tool 'add' was not given before the turn's time limit", "apr_1", "add"));
                    return "{\"status\":\"timeout\"}";
                })
                .build();

        runtime.execute(new AgentExecutionContext(
                "add", null, "test-model", null, "session-1", "user", "conv-1",
                List.of(tool), null, null, List.of(), Map.of(), List.of(), null, null), session);

        assertTrue(session.await(Duration.ofSeconds(10)), "the tool round ended the turn");
        assertInstanceOf(ApprovalTimedOutException.class, session.failure(),
                "the turn ends with the approval that ran out, not a later error");
        // The follow-up, were it sent, runs right after the tool on Spring AI's
        // loop; give it the time to land before counting.
        Thread.sleep(500);
        assertEquals(1, model.calls.get(), "no follow-up model call once the turn ended");
    }

    /** Injects the {@link ChatClient} as the runtime's native client. */
    private static final class TestableRuntime extends SpringAiAgentRuntime {
        TestableRuntime(ChatClient client) {
            setNativeClient(client);
        }
    }

    /** Asks for the {@code add} tool on its first call, answers with text after. */
    private static final class ToolThenTextChatModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();

        /** Tool-calling options, so the {@code ToolCallingAdvisor} runs the loop. */
        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

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
