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
import org.atmosphere.ai.AiProviderException;
import org.atmosphere.ai.CollectingSession;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A provider failure ends the turn with an error, as on every other runtime.
 * Spring AI Alibaba's {@code AgentLlmNode} catches a failed model call and
 * ends the ReAct run with an {@link AssistantMessage} carrying the exception
 * text, so {@code ReactAgent.call} returned normally and the runtime streamed
 * {@code "Exception: HTTP 401 - ..."} as the answer, with no error frame.
 */
class SpringAiAlibabaProviderErrorTest {

    private static final String PROVIDER_ERROR =
            "HTTP 401 - {\"error\":{\"message\":\"Incorrect API key provided: ollama.\"}}";

    private final AiEnvironment environment = AiEnvironment.create(key -> null);

    @AfterEach
    void close() {
        environment.close();
    }

    @Test
    void aFailedModelCallEndsTheTurnWithAnErrorNotAnAnswer() throws Exception {
        var model = new UsageCapturingChatModel(new FailingChatModel());
        SpringAiAlibabaAgentRuntime.setChatModel(environment, model);
        var runtime = new SpringAiAlibabaAgentRuntime();
        runtime.attach(environment);
        var session = new CollectingSession();

        var handle = runtime.doExecuteWithHandle(agentOver(model), context("conv-1"), session);
        var failure = assertThrows(ExecutionException.class,
                () -> handle.whenDone().get(10, TimeUnit.SECONDS));

        assertTrue(session.isClosed(), "the failed call must end the turn");
        assertTrue(session.failed(), "the turn must end with an error frame, not complete()");
        assertInstanceOf(AiProviderException.AuthenticationFailed.class, session.failure(),
                "an HTTP 401 is classified like on the other runtimes");
        assertFalse(session.text().contains("HTTP 401"),
                "the provider error must not be streamed as the answer: " + session.text());
        assertInstanceOf(AiProviderException.AuthenticationFailed.class, failure.getCause());
    }

    @Test
    void aSuccessfulAnswerStillCompletes() throws Exception {
        var model = new UsageCapturingChatModel(new AnsweringChatModel());
        SpringAiAlibabaAgentRuntime.setChatModel(environment, model);
        var runtime = new SpringAiAlibabaAgentRuntime();
        runtime.attach(environment);
        var session = new CollectingSession();

        runtime.doExecuteWithHandle(agentOver(model), context("conv-2"), session)
                .whenDone().get(10, TimeUnit.SECONDS);

        assertFalse(session.failed());
        assertEquals("Tokyo is rainy.", session.text());
    }

    @Test
    void aCallThatSucceedsAfterAFailureClearsIt() {
        var collector = UsageCapturingChatModel.beginCapture();
        try {
            var model = new UsageCapturingChatModel(new FailOnceChatModel());
            assertThrows(IllegalStateException.class, () -> model.call(new Prompt("hi")));
            assertInstanceOf(IllegalStateException.class, collector.failure());

            model.call(new Prompt("hi"));

            assertNull(collector.failure(), "only the latest model call decides whether the run failed");
        } finally {
            UsageCapturingChatModel.endCapture();
        }
    }

    @Test
    void aFailedStreamIsRecordedOnTheDispatchThatAskedForIt() {
        var collector = UsageCapturingChatModel.beginCapture();
        try {
            var model = new UsageCapturingChatModel(new FailingChatModel());
            assertThrows(IllegalStateException.class, () -> model.stream(new Prompt("hi")).blockLast());

            assertInstanceOf(IllegalStateException.class, collector.failure());
        } finally {
            UsageCapturingChatModel.endCapture();
        }
    }

    private static ReactAgent agentOver(ChatModel model) throws Exception {
        return ReactAgent.builder()
                .name("provider-error-test")
                .model(model)
                .build();
    }

    private static AgentExecutionContext context(String conversation) {
        return new AgentExecutionContext(
                "What is the weather in Tokyo?", "You are helpful", "qwen-plus", null, "session-" + conversation,
                "user-1", conversation, List.of(), null, null, List.of(), Map.of(), List.of(), null, null);
    }

    /** A model whose provider answers every call with an HTTP 401. */
    private static final class FailingChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new IllegalStateException(PROVIDER_ERROR);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.error(new IllegalStateException(PROVIDER_ERROR));
        }
    }

    private static final class AnsweringChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Tokyo is rainy."))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }
    }

    private static final class FailOnceChatModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException(PROVIDER_ERROR);
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }
    }
}
