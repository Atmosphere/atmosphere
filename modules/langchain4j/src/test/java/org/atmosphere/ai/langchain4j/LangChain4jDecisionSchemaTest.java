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

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.NativeStructuredOutput;
import org.atmosphere.ai.decision.Answer;
import org.atmosphere.ai.decision.DecisionRequest;
import org.atmosphere.ai.decision.Question;
import org.atmosphere.ai.decision.RuntimeDecisionModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Mode Parity (Correctness Invariant #7) for provider-native structured output:
 * the schema the caller stamps reaches the LangChain4j {@link ChatRequest}'s
 * response format, as it does on every other native runtime. A decision
 * question's closed value set (a boolean, a string enum of codes) is narrower
 * than the generic reply record it is parsed into; deriving the schema from
 * that record instead tells the provider {@code answer} is an empty object.
 */
class LangChain4jDecisionSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        AiConfig.configure("local", "llama3.2", null, null);
    }

    @AfterEach
    void tearDown() {
        AiConfig.configure("local", "llama3.2", null, null);
    }

    @Test
    void decisionQuestionsReachTheProviderWithTheirClosedValueSets() {
        var captured = new CopyOnWriteArrayList<ChatRequest>();
        var model = mock(StreamingChatModel.class);
        doAnswer(inv -> {
            ChatRequest request = inv.getArgument(0);
            captured.add(request);
            StreamingChatResponseHandler handler = inv.getArgument(1);
            var system = request.messages().getFirst().toString();
            var reply = system.contains("OPTIONS")
                    ? "{\"answer\":\"B\",\"confidence\":0.8}"
                    : "{\"answer\":true,\"confidence\":0.9}";
            handler.onPartialResponse(reply);
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(reply)).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        var options = new LinkedHashMap<String, String>();
        options.put("approve", "");
        options.put("reject", "");
        var questions = new LinkedHashMap<String, Question>();
        questions.put("noul", new Question.Noul("Is it raining?", "wet", "dry"));
        questions.put("choice", new Question.Choice("Pick one", options));
        var result = new RuntimeDecisionModel(new TestableRuntime(model))
                .decide(new DecisionRequest("state", questions, null));

        assertTrue(assertInstanceOf(Answer.Noul.class, result.answers().get("noul")).value());
        assertEquals("reject", assertInstanceOf(Answer.Choice.class, result.answers().get("choice")).choice());
        assertEquals(2, captured.size());

        var answerSchemas = new ArrayList<Map<String, Object>>();
        for (var request : captured) {
            var format = request.responseFormat();
            assertNotNull(format, "native structured output must attach a response format");
            var root = assertInstanceOf(JsonRawSchema.class, format.jsonSchema().rootElement(),
                    "the stamped schema must be sent, not one derived from the reply record");
            var answer = MAPPER.readTree(root.schema()).path("properties").path("answer");
            answerSchemas.add(Map.of("type", answer.path("type").stringValue(),
                    "enum", answer.path("enum").isMissingNode() ? List.of() : List.of(
                            answer.path("enum").get(0).stringValue(), answer.path("enum").get(1).stringValue())));
        }
        assertTrue(answerSchemas.contains(Map.of("type", "boolean", "enum", List.of())), answerSchemas.toString());
        assertTrue(answerSchemas.contains(Map.of("type", "string", "enum", List.of("A", "B"))),
                answerSchemas.toString());
    }

    @Test
    void withoutAStampedSchemaTheResponseTypeIsStillDerived() {
        var context = new AgentExecutionContext(
                "hi", "sys", null, null, "s", null, null,
                List.of(), null, null, List.of(), Map.of(NativeStructuredOutput.APPLY_METADATA_KEY, Boolean.TRUE),
                List.of(), Weather.class, null);
        var schema = LangChain4jAgentRuntime.nativeSchemaFor(context);
        assertNotNull(schema);
        assertEquals("Weather", schema.name());
        assertTrue(schema.rootElement().toString().contains("city"), schema.rootElement().toString());
    }

    record Weather(String city, double celsius) {
    }

    static class TestableRuntime extends LangChain4jAgentRuntime {
        TestableRuntime(StreamingChatModel model) {
            setNativeClient(model);
        }
    }
}
