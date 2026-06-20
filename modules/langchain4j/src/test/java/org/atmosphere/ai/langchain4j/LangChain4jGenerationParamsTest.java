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
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.GenerationParams;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Proves the framework-level {@link GenerationParams} carried on
 * {@code AiConfig.LlmSettings} actually reach the LangChain4j
 * {@link ChatRequest} that {@code LangChain4jAgentRuntime} dispatches —
 * Runtime Truth (Correctness Invariant #5). Only the params the runtime
 * actually wires (temperature, maxTokens→maxOutputTokens, topP, stop) are
 * asserted; the README matrix matches.
 */
@SuppressWarnings("unchecked")
public class LangChain4jGenerationParamsTest {

    private AtmosphereResource resource;
    private Broadcaster broadcaster;
    private StreamingSession session;

    @BeforeEach
    public void setUp() {
        resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("r1");
        broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(any(), any(Set.class))).thenReturn(mock(Future.class));
        session = StreamingSessions.start("lc4j-gen", resource);
    }

    @AfterEach
    public void tearDown() {
        // Re-establish a clean AiConfig so the global singleton does not leak
        // generation state into other tests in the module.
        org.atmosphere.ai.AiConfig.configure("local", "llama3.2", null, null);
    }

    @Test
    public void testGenerationParamsReachChatRequest() {
        // Configure AiConfig so the runtime reads our generation overrides.
        var settings = org.atmosphere.ai.AiConfig.configure("local", "llama3.2", null, null);
        var withGen = new org.atmosphere.ai.AiConfig.LlmSettings(
                settings.client(), settings.model(), settings.mode(), settings.baseUrl(),
                settings.apiKey(), settings.promptCacheKeyMode(),
                new GenerationParams(0.15, 321, 0.66, List.of("HALT")));
        installSettings(withGen);

        var captured = new ChatRequest[1];
        var model = mock(StreamingChatModel.class);
        doAnswer(inv -> {
            captured[0] = inv.getArgument(0);
            StreamingChatResponseHandler handler = inv.getArgument(1);
            handler.onPartialResponse("ok");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("ok")).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        var runtime = new TestableRuntime(model);
        runtime.execute(textContext(), session);

        assertNotNull(captured[0], "the runtime must dispatch a ChatRequest");
        var params = captured[0].parameters();
        assertEquals(0.15, params.temperature(), 1e-9, "temperature must reach the ChatRequest");
        assertEquals(321, params.maxOutputTokens(), "maxTokens must reach maxOutputTokens");
        assertEquals(0.66, params.topP(), 1e-9, "topP must reach the ChatRequest");
        assertEquals(List.of("HALT"), params.stopSequences(), "stop must reach stopSequences");
    }

    @Test
    public void testEmptyGenerationLeavesParamsUnset() {
        var settings = org.atmosphere.ai.AiConfig.configure("local", "llama3.2", null, null);
        // Default settings => generation defaults() (all null). Re-install to be explicit.
        installSettings(new org.atmosphere.ai.AiConfig.LlmSettings(
                settings.client(), settings.model(), settings.mode(), settings.baseUrl(),
                settings.apiKey(), settings.promptCacheKeyMode(), GenerationParams.defaults()));

        var captured = new ChatRequest[1];
        var model = mock(StreamingChatModel.class);
        doAnswer(inv -> {
            captured[0] = inv.getArgument(0);
            StreamingChatResponseHandler handler = inv.getArgument(1);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("ok")).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        new TestableRuntime(model).execute(textContext(), session);

        assertNotNull(captured[0]);
        var params = captured[0].parameters();
        // Unset generation must leave LC4j's optional params null (today's shape).
        assertNull(params.temperature(), "temperature unset when generation empty");
        assertNull(params.maxOutputTokens(), "maxOutputTokens unset when generation empty");
        assertNull(params.topP(), "topP unset when generation empty");
        assertTrue(params.stopSequences() == null || params.stopSequences().isEmpty(),
                "stopSequences unset when generation empty");
    }

    private static AgentExecutionContext textContext() {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "gpt-4",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    /** Reflectively install a custom LlmSettings into AiConfig's singleton. */
    private static void installSettings(org.atmosphere.ai.AiConfig.LlmSettings settings) {
        try {
            var f = org.atmosphere.ai.AiConfig.class.getDeclaredField("instance");
            f.setAccessible(true);
            f.set(null, settings);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not install AiConfig settings for the test", e);
        }
    }

    static class TestableRuntime extends LangChain4jAgentRuntime {
        TestableRuntime(StreamingChatModel model) {
            setNativeClient(model);
        }
    }
}
