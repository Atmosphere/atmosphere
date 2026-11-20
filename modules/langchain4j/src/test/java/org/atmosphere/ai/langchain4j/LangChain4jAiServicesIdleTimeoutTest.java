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
package org.atmosphere.ai.langchain4j;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.test.StreamIdleAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stream idle bound on the {@code AiServices} path
 * ({@link LangChain4jAiServices#attach}), through LangChain4j's own
 * {@code AiServices} and its {@code AiServiceTokenStream} over a scripted
 * streaming model: a stream that goes silent ends the turn once in both
 * dispatch modes and is cancelled through the {@code StreamingHandle} its
 * partial callback handed over (the {@code TokenStream} has no cancel of its
 * own), and a tool {@code AiServices} runs itself for three times the bound is
 * not idle time.
 */
class LangChain4jAiServicesIdleTimeoutTest {

    /** The AiServices interface the tests drive. */
    interface Assistant {
        TokenStream chat(String message);
    }

    /** A streaming model that answers each round with the next script on a thread of its own. */
    private static final class ScriptedModel implements StreamingChatModel {
        private final List<Consumer<StreamingChatResponseHandler>> rounds;
        private final AtomicInteger round = new AtomicInteger();

        ScriptedModel(List<Consumer<StreamingChatResponseHandler>> rounds) {
            this.rounds = rounds;
        }

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            var script = rounds.get(Math.min(round.getAndIncrement(), rounds.size() - 1));
            Thread.ofVirtual().start(() -> script.accept(handler));
        }
    }

    /** A handle that counts its cancels. */
    private static final class CountingHandle implements StreamingHandle {
        final AtomicInteger cancels = new AtomicInteger();
        private volatile boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
            cancels.incrementAndGet();
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** The tool AiServices runs itself: three times the bound. */
    static final class SlowTools {
        @Tool("Looks the weather up; takes a while")
        String slowLookup() throws InterruptedException {
            Thread.sleep(StreamIdleAssertions.BOUND.multipliedBy(3).toMillis());
            return "sunny";
        }
    }

    private static AgentExecutionContext context() {
        return new AgentExecutionContext("Hello", "You are helpful", "gpt-4o-mini",
                null, "session-1", "user-1", null, List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @Test
    void aSilentAiServicesStreamEndsTheTurnOnceAndIsCancelledThroughItsHandle() throws Exception {
        var handles = new java.util.concurrent.CopyOnWriteArrayList<CountingHandle>();
        StreamIdleAssertions.assertSilentModelEndsTheTurnOnce(environment -> {
            var handle = new CountingHandle();
            handles.add(handle);
            // One partial, then silence until the stream is cancelled.
            var model = new ScriptedModel(List.of(h -> h.onPartialResponse(
                    new PartialResponse("tick"), new PartialResponseContext(handle))));
            var assistant = AiServices.builder(Assistant.class).streamingChatModel(model).build();
            return new LangChain4jRuntimeContractTest.TestableLangChain4jRuntime(model) {
                @Override
                protected org.atmosphere.ai.ExecutionHandle doExecuteWithHandle(
                        StreamingChatModel streamingModel, AgentExecutionContext context,
                        org.atmosphere.ai.StreamingSession session) {
                    return super.doExecuteWithHandle(streamingModel,
                            LangChain4jAiServices.attach(context, assistant::chat), session);
                }
            };
        }, context(), "StreamingHandle.cancel()",
                () -> handles.stream().mapToInt(h -> h.cancels.get() > 0 ? 1 : 0).sum());
    }

    @Test
    void aToolAiServicesRunsForThreeTimesTheBoundIsNotIdleTime() throws Exception {
        var toolCall = ToolExecutionRequest.builder().id("call-1").name("slowLookup").arguments("{}").build();
        var model = new ScriptedModel(List.of(
                h -> h.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from(toolCall))
                        .finishReason(FinishReason.TOOL_EXECUTION)
                        .build()),
                h -> {
                    h.onPartialResponse(new PartialResponse("sunny"), new PartialResponseContext(new CountingHandle()));
                    h.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from("sunny"))
                            .finishReason(FinishReason.STOP)
                            .build());
                }));
        var assistant = AiServices.builder(Assistant.class)
                .streamingChatModel(model)
                .tools(new SlowTools())
                .build();
        var environment = AiEnvironment.create(Map.of(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY,
                StreamIdleAssertions.BOUND.toMillis() + "ms")::get);
        try {
            var runtime = new LangChain4jRuntimeContractTest.TestableLangChain4jRuntime(model);
            runtime.attach(environment);
            var session = new CollectingSession();
            var handle = runtime.executeWithHandle(LangChain4jAiServices.attach(context(), assistant::chat), session);
            handle.whenDone().get(10, TimeUnit.SECONDS);
            assertTrue(!session.failed(),
                    "a tool AiServices ran for three times the bound timed the turn out: " + session.failure());
            assertEquals("sunny", session.text());
        } finally {
            environment.close();
        }
    }
}
