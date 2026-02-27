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
package org.atmosphere.ai.routing;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.llm.LlmClient;
import org.atmosphere.ai.routing.RoutingLlmClient.RoutingRule;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class RoutingLlmClientTest {

    private static LlmClient capturingClient(AtomicReference<String> capturedModel) {
        return (request, session) -> {
            capturedModel.set(request.model());
            session.send("response from " + request.model());
            session.complete();
        };
    }

    @Test
    public void testRoutesToDefaultWhenNoRuleMatches() {
        var capturedModel = new AtomicReference<String>();
        var defaultClient = capturingClient(capturedModel);

        var router = RoutingLlmClient.builder(defaultClient, "default-model").build();

        var session = mock(StreamingSession.class);
        router.streamChatCompletion(ChatCompletionRequest.of("any-model", "hello"), session);

        assertEquals("default-model", capturedModel.get());
    }

    @Test
    public void testContentBasedRouting() {
        var defaultModel = new AtomicReference<String>();
        var codeModel = new AtomicReference<String>();

        var defaultClient = capturingClient(defaultModel);
        var codeClient = capturingClient(codeModel);

        var router = RoutingLlmClient.builder(defaultClient, "default-model")
                .route(RoutingRule.contentBased(
                        prompt -> prompt.contains("code"),
                        codeClient, "gpt-4o"))
                .build();

        var session1 = mock(StreamingSession.class);
        router.streamChatCompletion(ChatCompletionRequest.of("any", "write code for me"), session1);
        assertEquals("gpt-4o", codeModel.get());
        assertNull(defaultModel.get());

        // Non-code prompt should use default
        var session2 = mock(StreamingSession.class);
        router.streamChatCompletion(ChatCompletionRequest.of("any", "tell me a joke"), session2);
        assertEquals("default-model", defaultModel.get());
    }

    @Test
    public void testModelBasedRouting() {
        var capturedModel = new AtomicReference<String>();
        var openaiClient = capturingClient(capturedModel);
        var defaultClient = capturingClient(new AtomicReference<>());

        var router = RoutingLlmClient.builder(defaultClient, "gemini-flash")
                .route(RoutingRule.modelBased(
                        model -> model.startsWith("gpt-"),
                        openaiClient))
                .build();

        var session = mock(StreamingSession.class);
        router.streamChatCompletion(ChatCompletionRequest.of("gpt-4o", "hello"), session);
        assertEquals("gpt-4o", capturedModel.get());
    }

    @Test
    public void testFirstMatchingRuleWins() {
        var captured1 = new AtomicReference<String>();
        var captured2 = new AtomicReference<String>();

        var router = RoutingLlmClient.builder(capturingClient(new AtomicReference<>()), "default")
                .route(RoutingRule.contentBased(
                        prompt -> prompt.contains("hello"),
                        capturingClient(captured1), "model-1"))
                .route(RoutingRule.contentBased(
                        prompt -> prompt.contains("hello"),
                        capturingClient(captured2), "model-2"))
                .build();

        var session = mock(StreamingSession.class);
        router.streamChatCompletion(ChatCompletionRequest.of("any", "hello world"), session);

        assertEquals("model-1", captured1.get());
        assertNull(captured2.get()); // second rule should NOT be reached
    }

    @Test
    public void testSendsRoutingMetadata() {
        var router = RoutingLlmClient.builder(
                        capturingClient(new AtomicReference<>()), "default-model")
                .build();

        var session = mock(StreamingSession.class);
        router.streamChatCompletion(ChatCompletionRequest.of("any", "hello"), session);

        verify(session).sendMetadata("routing.model", "default-model");
    }
}
