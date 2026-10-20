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

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * A turn whose model calls a tool runs more than one model round: the handle
 * of the turn is done when the last round ends, not when the first does. A
 * handle done after the first round made the pipeline end the turn early
 * (post-processing, turn metrics, the response cache) while the answer was
 * still streaming — the ai-tools sample's {@code [Cost] Request completed}
 * logged after the first round of a 13 s tool-loop run.
 */
class LangChain4jToolLoopHandleTest {

    private static StreamingChatModel toolThenAnswerModel() {
        var model = mock(StreamingChatModel.class);
        doAnswer(inv -> {
            ChatRequest req = inv.getArgument(0);
            StreamingChatResponseHandler handler = inv.getArgument(1);
            var hasToolResults = req.messages().stream()
                    .anyMatch(m -> m instanceof ToolExecutionResultMessage);
            if (!hasToolResults) {
                // Round 1: the model asks for the tool, at once.
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-1").name("lookup").arguments("{\"city\":\"Paris\"}").build())))
                        .build());
            } else {
                // Round 2: the answer streams in later, from the provider's thread.
                Thread.ofVirtual().start(() -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    handler.onPartialResponse("final answer");
                    handler.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from("final answer")).build());
                });
            }
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        return model;
    }

    private static AgentExecutionContext toolContext() {
        var tool = ToolDefinition.builder("lookup", "Look a city up")
                .parameter("city", "the city", "string")
                .executor(args -> "sunny in " + args.get("city"))
                .build();
        return new AgentExecutionContext(
                "Weather in Paris?", "You are helpful", "gpt-4",
                null, "session-1", "user-1", "conv-1",
                List.of(tool), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @Test
    void theHandleIsDoneWhenTheLastToolRoundEnds() throws Exception {
        var runtime = new LangChain4jRuntimeContractTest.TestableLangChain4jRuntime(toolThenAnswerModel());
        var session = new CollectingSession("tool-loop-1");

        var handle = runtime.executeWithHandle(toolContext(), session);
        handle.whenDone().get(10, TimeUnit.SECONDS);

        assertTrue(session.isClosed(), "the turn ended when its handle is done");
        assertFalse(session.failed(), "the turn did not fail: " + session.failure());
        assertTrue(session.text().contains("final answer"),
                "the handle is done after the last round's answer, got: " + session.text());
    }
}
