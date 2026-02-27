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

public class RoutingRuleLatencyBasedTest {

    private static LlmClient capturingClient(AtomicReference<String> capturedModel) {
        return (request, session) -> {
            capturedModel.set(request.model());
            session.send("response from " + request.model());
            session.complete();
        };
    }

    @Test
    public void testSelectsWithinLatency() {
        var capturedFast = new AtomicReference<String>();
        var capturedSlow = new AtomicReference<String>();

        var router = RoutingLlmClient.builder(capturingClient(new AtomicReference<>()), "default")
                .route(RoutingRule.latencyBased(100, List.of(
                        new ModelOption(capturingClient(capturedSlow), "slow", 0.001, 500, 10),
                        new ModelOption(capturingClient(capturedFast), "fast", 0.01, 50, 5)
                )))
                .build();

        // max 100ms: slow (500ms) doesn't fit, fast (50ms) fits
        var session = mock(StreamingSession.class);
        router.streamChatCompletion(ChatCompletionRequest.of("any", "hello"), session);

        assertEquals("fast", capturedFast.get());
        assertNull(capturedSlow.get());
    }

    @Test
    public void testFallsThroughWhenNothingFits() {
        var capturedDefault = new AtomicReference<String>();

        var router = RoutingLlmClient.builder(capturingClient(capturedDefault), "default-model")
                .route(RoutingRule.latencyBased(10, List.of(
                        new ModelOption(capturingClient(new AtomicReference<>()), "slow", 0.001, 500, 10)
                )))
                .build();

        var session = mock(StreamingSession.class);
        router.streamChatCompletion(ChatCompletionRequest.of("any", "hello"), session);

        assertEquals("default-model", capturedDefault.get());
    }

    @Test
    public void testSendsLatencyMetadata() {
        var router = RoutingLlmClient.builder(capturingClient(new AtomicReference<>()), "default")
                .route(RoutingRule.latencyBased(200, List.of(
                        new ModelOption(capturingClient(new AtomicReference<>()), "model-a", 0.001, 75, 5)
                )))
                .build();

        var session = mock(StreamingSession.class);
        router.streamChatCompletion(ChatCompletionRequest.of("any", "hello"), session);

        verify(session).sendMetadata("routing.model", "model-a");
        verify(session).sendMetadata("routing.latency", 75L);
    }

    @Test
    public void testExactMatchOnLatency() {
        var captured = new AtomicReference<String>();

        var router = RoutingLlmClient.builder(capturingClient(new AtomicReference<>()), "default")
                .route(RoutingRule.latencyBased(100, List.of(
                        new ModelOption(capturingClient(captured), "exact", 0.001, 100, 5)
                )))
                .build();

        // averageLatencyMs == maxLatencyMs -- should match (<=)
        var session = mock(StreamingSession.class);
        router.streamChatCompletion(ChatCompletionRequest.of("any", "hello"), session);
        assertEquals("exact", captured.get());
    }

    @Test
    public void testEmptyModelList() {
        var capturedDefault = new AtomicReference<String>();

        var router = RoutingLlmClient.builder(capturingClient(capturedDefault), "default-model")
                .route(RoutingRule.latencyBased(1000, List.of()))
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
                .route(RoutingRule.latencyBased(200, List.of(
                        new ModelOption(capturingClient(capturedLow), "low-cap", 0.001, 50, 3),
                        new ModelOption(capturingClient(capturedHigh), "high-cap", 0.01, 80, 10)
                )))
                .build();

        // Both fit (50 <= 200 and 80 <= 200); highest capability wins
        var session = mock(StreamingSession.class);
        router.streamChatCompletion(ChatCompletionRequest.of("any", "hello"), session);

        assertEquals("high-cap", capturedHigh.get());
        assertNull(capturedLow.get());
    }
}
