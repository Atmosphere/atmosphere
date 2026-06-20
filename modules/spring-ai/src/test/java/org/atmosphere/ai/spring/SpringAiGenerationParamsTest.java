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
import org.atmosphere.ai.GenerationParams;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Proves the framework-level {@link GenerationParams} carried on
 * {@code AiConfig.LlmSettings} reach the Spring AI {@link ChatOptions} that
 * {@code SpringAiAgentRuntime} attaches to the prompt spec — Runtime Truth
 * (Correctness Invariant #5). Spring AI's generic {@code ChatOptions.Builder}
 * carries temperature/maxTokens/topP/stopSequences, so all four are asserted.
 */
@SuppressWarnings("unchecked")
public class SpringAiGenerationParamsTest {

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
        session = StreamingSessions.start("spring-gen", resource);
    }

    @AfterEach
    public void tearDown() {
        org.atmosphere.ai.AiConfig.configure("local", "llama3.2", null, null);
    }

    @Test
    public void testGenerationParamsReachChatOptions() {
        var settings = org.atmosphere.ai.AiConfig.configure("local", "llama3.2", null, null);
        installSettings(new org.atmosphere.ai.AiConfig.LlmSettings(
                settings.client(), settings.model(), settings.mode(), settings.baseUrl(),
                settings.apiKey(), settings.promptCacheKeyMode(),
                new GenerationParams(0.25, 222, 0.77, List.of("STOP"))));

        var promptSpec = mock(ChatClient.ChatClientRequestSpec.class,
                org.mockito.Answers.RETURNS_SELF);
        var optionsCaptor = ArgumentCaptor.forClass(ChatOptions.Builder.class);
        var chatClient = chatClientReturning(promptSpec);

        new TestableRuntime(chatClient).execute(textContext(), session);

        // The runtime attaches a ChatOptions.Builder carrying the generation
        // overrides. options(...) may also be invoked with a model-only builder
        // in other paths, but for this text context it's the generation path.
        verify(promptSpec, atLeastOnce()).options(optionsCaptor.capture());
        var opts = optionsCaptor.getValue().build();
        assertEquals(0.25, opts.getTemperature(), 1e-9, "temperature must reach ChatOptions");
        assertEquals(222, opts.getMaxTokens(), "maxTokens must reach ChatOptions");
        assertEquals(0.77, opts.getTopP(), 1e-9, "topP must reach ChatOptions");
        assertEquals(List.of("STOP"), opts.getStopSequences(), "stop must reach stopSequences");
    }

    @Test
    public void testEmptyGenerationAttachesNoOptionsForPlainContext() {
        // Default generation + no model override + no cache hint => the runtime
        // must NOT attach an options builder (byte-identical to today).
        var settings = org.atmosphere.ai.AiConfig.configure("local", "llama3.2", null, null);
        installSettings(new org.atmosphere.ai.AiConfig.LlmSettings(
                settings.client(), settings.model(), settings.mode(), settings.baseUrl(),
                settings.apiKey(), settings.promptCacheKeyMode(), GenerationParams.defaults()));

        var promptSpec = mock(ChatClient.ChatClientRequestSpec.class,
                org.mockito.Answers.RETURNS_SELF);
        var chatClient = chatClientReturning(promptSpec);

        // model() null + no generation: options(...) must never be called.
        new TestableRuntime(chatClient).execute(plainContext(), session);

        verify(promptSpec, never()).options(any(ChatOptions.Builder.class));
    }

    private ChatClient chatClientReturning(ChatClient.ChatClientRequestSpec promptSpec) {
        var chatClient = mock(ChatClient.class);
        var streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        var generation = new Generation(new AssistantMessage("Hello world"));
        var chatResponse = new ChatResponse(List.of(generation));
        when(streamSpec.chatResponse()).thenReturn(Flux.just(chatResponse));
        return chatClient;
    }

    private static AgentExecutionContext textContext() {
        // model() = null so the runtime does NOT take the model-override branch;
        // generation alone must drive the options builder.
        return new AgentExecutionContext(
                "Hello", "You are helpful", null,
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    private static AgentExecutionContext plainContext() {
        return new AgentExecutionContext(
                "Hello", "You are helpful", null,
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    private static void installSettings(org.atmosphere.ai.AiConfig.LlmSettings settings) {
        try {
            var f = org.atmosphere.ai.AiConfig.class.getDeclaredField("instance");
            f.setAccessible(true);
            f.set(null, settings);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not install AiConfig settings for the test", e);
        }
    }

    static class TestableRuntime extends SpringAiAgentRuntime {
        TestableRuntime(ChatClient client) {
            setNativeClient(client);
        }
    }
}
