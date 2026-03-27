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
package org.atmosphere.ai.fanout;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.llm.LlmClient;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class FanOutStreamingSessionTest {

    private AtmosphereResource resource;
    private Broadcaster broadcaster;
    private StreamingSession parentSession;

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        resource = mock(AtmosphereResource.class);
        broadcaster = mock(Broadcaster.class);
        when(resource.uuid()).thenReturn("resource-uuid");
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(any(RawMessage.class), any(Set.class))).thenReturn(mock(Future.class));
        parentSession = StreamingSessions.start("parent-123", resource);
    }

    @AfterEach
    public void tearDown() {
        parentSession.close();
    }

    /** A mock LlmClient that sends a fixed number of streaming texts. */
    private static LlmClient fixedTextClient(String... texts) {
        return (request, session) -> {
            for (var text : texts) {
                if (session.isClosed()) {
                    return;
                }
                session.send(text);
                try { Thread.sleep(5); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            session.complete();
        };
    }

    /** A slow LlmClient that sleeps between streaming texts. */
    private static LlmClient slowClient(int delayMs, String... texts) {
        return (request, session) -> {
            for (var text : texts) {
                if (session.isClosed()) {
                    return;
                }
                session.send(text);
                try { Thread.sleep(delayMs); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            session.complete();
        };
    }

    @Test
    public void testAllResponsesStrategy() {
        var endpoints = List.of(
                new ModelEndpoint("fast", fixedTextClient("Hello", " world"), "fast-model"),
                new ModelEndpoint("slow", fixedTextClient("Hi", " there"), "slow-model")
        );

        try (var fanOut = new FanOutStreamingSession(parentSession, endpoints,
                new FanOutStrategy.AllResponses(), resource)) {
            fanOut.fanOut(ChatCompletionRequest.of("ignored", "test prompt"));

            var results = fanOut.getResults();
            assertEquals(2, results.size());
            assertTrue(results.containsKey("fast"));
            assertTrue(results.containsKey("slow"));
            assertEquals("Hello world", results.get("fast").fullResponse());
            assertEquals("Hi there", results.get("slow").fullResponse());
            assertEquals(2, results.get("fast").streamingTextCount());
            assertEquals(2, results.get("slow").streamingTextCount());
        }
    }

    @Test
    public void testFirstCompleteStrategy() throws Exception {
        var endpoints = List.of(
                new ModelEndpoint("fast", fixedTextClient("Quick"), "fast-model"),
                new ModelEndpoint("slow", slowClient(500, "Slow", " response", " here"), "slow-model")
        );

        try (var fanOut = new FanOutStreamingSession(parentSession, endpoints,
                new FanOutStrategy.FirstComplete(), resource)) {
            fanOut.fanOut(ChatCompletionRequest.of("ignored", "test prompt"));

            var results = fanOut.getResults();
            // Fast model should have completed
            assertTrue(results.containsKey("fast"));
            assertEquals("Quick", results.get("fast").fullResponse());
        }
    }

    @Test
    public void testEmptyEndpoints() {
        try (var fanOut = new FanOutStreamingSession(parentSession, List.of(),
                new FanOutStrategy.AllResponses(), resource)) {
            fanOut.fanOut(ChatCompletionRequest.of("model", "test"));
            assertTrue(parentSession.isClosed());
        }
    }

    @Test
    public void testFanOutResultMetrics() {
        var endpoints = List.of(
                new ModelEndpoint("model1", fixedTextClient("a", "b", "c"), "model1")
        );

        try (var fanOut = new FanOutStreamingSession(parentSession, endpoints,
                new FanOutStrategy.AllResponses(), resource)) {
            fanOut.fanOut(ChatCompletionRequest.of("ignored", "test"));

            var result = fanOut.getResults().get("model1");
            assertNotNull(result);
            assertEquals("model1", result.modelId());
            assertEquals("abc", result.fullResponse());
            assertEquals(3, result.streamingTextCount());
            assertTrue(result.timeToFirstStreamingTextMs() >= 0);
            assertTrue(result.totalTimeMs() >= result.timeToFirstStreamingTextMs());
        }
    }

    @Test
    public void testFanOutWithBroadcaster() {
        var endpoints = List.of(
                new ModelEndpoint("model1", fixedTextClient("Hello"), "model1")
        );

        var parentFromBroadcaster = StreamingSessions.start("parent-bc", broadcaster);

        try (var fanOut = new FanOutStreamingSession(parentFromBroadcaster, endpoints,
                new FanOutStrategy.AllResponses(), broadcaster)) {
            fanOut.fanOut(ChatCompletionRequest.of("ignored", "test"));

            var results = fanOut.getResults();
            assertEquals(1, results.size());
            assertEquals("Hello", results.get("model1").fullResponse());
        }

        parentFromBroadcaster.close();
    }

    @Test
    public void testModelEndpointRecord() {
        var client = fixedTextClient("test");
        var endpoint = new ModelEndpoint("gemini", client, "gemini-2.5-flash");

        assertEquals("gemini", endpoint.id());
        assertSame(client, endpoint.client());
        assertEquals("gemini-2.5-flash", endpoint.model());
    }

    @Test
    public void testFanOutStrategySealed() {
        FanOutStrategy strategy1 = new FanOutStrategy.AllResponses();
        FanOutStrategy strategy2 = new FanOutStrategy.FirstComplete();
        FanOutStrategy strategy3 = new FanOutStrategy.FastestStreamingTexts(5);

        assertInstanceOf(FanOutStrategy.AllResponses.class, strategy1);
        assertInstanceOf(FanOutStrategy.FirstComplete.class, strategy2);
        assertInstanceOf(FanOutStrategy.FastestStreamingTexts.class, strategy3);
        assertEquals(5, ((FanOutStrategy.FastestStreamingTexts) strategy3).streamingTextThreshold());
    }

    @Test
    public void testFanOutResultRecord() {
        var result = new FanOutResult("model1", "Hello world", 50, 200, 5);

        assertEquals("model1", result.modelId());
        assertEquals("Hello world", result.fullResponse());
        assertEquals(50, result.timeToFirstStreamingTextMs());
        assertEquals(200, result.totalTimeMs());
        assertEquals(5, result.streamingTextCount());
    }

    @Test
    public void testErrorInOneModelDoesNotBlockOthers() {
        LlmClient failingClient = (request, session) -> {
            session.error(new RuntimeException("Model failed"));
        };

        var endpoints = List.of(
                new ModelEndpoint("good", fixedTextClient("Hello"), "good-model"),
                new ModelEndpoint("bad", failingClient, "bad-model")
        );

        try (var fanOut = new FanOutStreamingSession(parentSession, endpoints,
                new FanOutStrategy.AllResponses(), resource)) {
            fanOut.fanOut(ChatCompletionRequest.of("ignored", "test"));

            var results = fanOut.getResults();
            assertTrue(results.containsKey("good"));
            assertEquals("Hello", results.get("good").fullResponse());
        }
    }
}
