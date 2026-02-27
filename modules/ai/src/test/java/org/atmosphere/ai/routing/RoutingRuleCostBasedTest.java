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
import org.atmosphere.ai.routing.RoutingLlmClient.RoutingRule.ModelOption;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class RoutingRuleCostBasedTest {

    private static LlmClient capturingClient(AtomicReference<String> capturedModel) {
        return (request, session) -> {
            capturedModel.set(request.model());
            session.send("response from " + request.model());
            session.complete();
        };
    }

    @Test
    public void testSelectsWithinBudget() {
        var capturedCheap = new AtomicReference<String>();
        var capturedExpensive = new AtomicReference<String>();

        var router = RoutingLlmClient.builder(capturingClient(new AtomicReference<>()), "default")
                .route(RoutingRule.costBased(5.0, List.of(
                        new ModelOption(capturingClient(capturedExpensive), "expensive", 0.01, 100, 10),
                        new ModelOption(capturingClient(capturedCheap), "cheap", 0.001, 200, 5)
                )))
                .build();

        // maxTokens=2048 (default from ChatCompletionRequest.of)
        // expensive: 0.01 * 2048 = 20.48 > 5.0 -- too expensive
        // cheap: 0.001 * 2048 = 2.048 <= 5.0 -- fits
        var session = mock(StreamingSession.class);
        router.streamChatCompletion(ChatCompletionRequest.of("any", "hello"), session);

        assertEquals("cheap", capturedCheap.get());
        assertNull(capturedExpensive.get());
    }

    @Test
    public void testFallsThroughWhenNothingFits() {
        var capturedDefault = new AtomicReference<String>();

        var router = RoutingLlmClient.builder(capturingClient(capturedDefault), "default-model")
                .route(RoutingRule.costBased(0.001, List.of(
                        new ModelOption(capturingClient(new AtomicReference<>()), "expensive", 0.01, 100, 10)
                )))
                .build();

        // 0.01 * 2048 = 20.48 > 0.001 -- nothing fits
        var session = mock(StreamingSession.class);
        router.streamChatCompletion(ChatCompletionRequest.of("any", "hello"), session);

        assertEquals("default-model", capturedDefault.get());
    }

    @Test
    public void testSendsCostMetadata() {
        var router = RoutingLlmClient.builder(capturingClient(new AtomicReference<>()), "default")
                .route(RoutingRule.costBased(100.0, List.of(
                        new ModelOption(capturingClient(new AtomicReference<>()), "model-a", 0.002, 100, 5)
                )))
                .build();

        var session = mock(StreamingSession.class);
        // maxTokens = 100 via builder
        var request = ChatCompletionRequest.builder("any")
                .user("hello")
                .maxTokens(100)
                .build();
        router.streamChatCompletion(request, session);

        verify(session).sendMetadata("routing.model", "model-a");
        verify(session).sendMetadata("routing.cost", 0.2); // 0.002 * 100
    }

    @Test
    public void testMaxTokensCalculation() {
        var captured = new AtomicReference<String>();

        var router = RoutingLlmClient.builder(capturingClient(new AtomicReference<>()), "default")
                .route(RoutingRule.costBased(1.0, List.of(
                        new ModelOption(capturingClient(captured), "model-a", 0.01, 100, 5)
                )))
                .build();

        // maxTokens=50: 0.01 * 50 = 0.5 <= 1.0 -- fits
        var request = ChatCompletionRequest.builder("any")
                .user("hello")
                .maxTokens(50)
                .build();
        var session = mock(StreamingSession.class);
        router.streamChatCompletion(request, session);
        assertEquals("model-a", captured.get());

        // maxTokens=200: 0.01 * 200 = 2.0 > 1.0 -- doesn't fit
        captured.set(null);
        var request2 = ChatCompletionRequest.builder("any")
                .user("hello")
                .maxTokens(200)
                .build();
        var session2 = mock(StreamingSession.class);
        var defaultCaptured = new AtomicReference<String>();
        var router2 = RoutingLlmClient.builder(capturingClient(defaultCaptured), "default")
                .route(RoutingRule.costBased(1.0, List.of(
                        new ModelOption(capturingClient(new AtomicReference<>()), "model-a", 0.01, 100, 5)
                )))
                .build();
        router2.streamChatCompletion(request2, session2);
        assertEquals("default", defaultCaptured.get());
    }

    @Test
    public void testZeroCostEdge() {
        var captured = new AtomicReference<String>();

        var router = RoutingLlmClient.builder(capturingClient(new AtomicReference<>()), "default")
                .route(RoutingRule.costBased(0.0, List.of(
                        new ModelOption(capturingClient(captured), "free-model", 0.0, 100, 5)
                )))
                .build();

        // 0.0 * 2048 = 0.0 <= 0.0 -- fits
        var session = mock(StreamingSession.class);
        router.streamChatCompletion(ChatCompletionRequest.of("any", "hello"), session);
        assertEquals("free-model", captured.get());
    }

    @Test
    public void testEmptyModelList() {
        var capturedDefault = new AtomicReference<String>();

        var router = RoutingLlmClient.builder(capturingClient(capturedDefault), "default-model")
                .route(RoutingRule.costBased(100.0, List.of()))
                .build();

        var session = mock(StreamingSession.class);
        router.streamChatCompletion(ChatCompletionRequest.of("any", "hello"), session);

        assertEquals("default-model", capturedDefault.get());
    }

    @Test
    public void testTieBreakingByCapability() {
        var capturedHigh = new AtomicReference<String>();
        var capturedLow = new AtomicReference<String>();

        var router = RoutingLlmClient.builder(capturingClient(new AtomicReference<>()), "default")
                .route(RoutingRule.costBased(100.0, List.of(
                        new ModelOption(capturingClient(capturedLow), "low-cap", 0.001, 100, 3),
                        new ModelOption(capturingClient(capturedHigh), "high-cap", 0.001, 100, 10)
                )))
                .build();

        // Both fit the budget; highest capability wins
        var session = mock(StreamingSession.class);
        router.streamChatCompletion(ChatCompletionRequest.of("any", "hello"), session);

        assertEquals("high-cap", capturedHigh.get());
        assertNull(capturedLow.get());
    }
}
