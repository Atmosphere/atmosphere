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
package org.atmosphere.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the AI streaming SPI — concurrency, multi-session,
 * and error recovery scenarios.
 */
public class StreamingIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void testConcurrentTokenSending() throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        var broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(anyString())).thenReturn(mock(Future.class));
        var session = StreamingSessions.start("concurrent-session", resource);

        int threadCount = 10;
        int tokensPerThread = 100;
        var latch = new CountDownLatch(threadCount);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < tokensPerThread; i++) {
                            session.send("token-" + i);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "All threads should complete");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(broadcaster, times(threadCount * tokensPerThread)).broadcast(captor.capture());

        // Verify all sequence numbers are unique
        Set<Long> seqs = ConcurrentHashMap.newKeySet();
        for (var msg : captor.getAllValues()) {
            var json = MAPPER.readTree(msg);
            assertTrue(seqs.add(json.get("seq").asLong()),
                    "Sequence number should be unique: " + json.get("seq"));
        }

        assertEquals(threadCount * tokensPerThread, seqs.size());
    }

    @Test
    public void testMultipleSessionsSameBroadcaster() throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        var broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(anyString())).thenReturn(mock(Future.class));

        var session1 = StreamingSessions.start("session-1", resource);
        var session2 = StreamingSessions.start("session-2", resource);

        session1.send("Hello from 1");
        session2.send("Hello from 2");
        session1.complete();
        session2.send("Still going");
        session2.complete();

        var captor = ArgumentCaptor.forClass(String.class);
        verify(broadcaster, times(5)).broadcast(captor.capture());

        var messages = captor.getAllValues().stream()
                .map(m -> {
                    try {
                        return MAPPER.readTree((String) m);
                    } catch (Exception e) {
                        fail("Failed to parse JSON: " + m);
                        return null;
                    }
                })
                .toList();

        // Count messages per session
        long session1Count = messages.stream()
                .filter(j -> "session-1".equals(j.get("sessionId").asText()))
                .count();
        long session2Count = messages.stream()
                .filter(j -> "session-2".equals(j.get("sessionId").asText()))
                .count();

        assertEquals(2, session1Count, "Session 1 should have 2 messages (token + complete)");
        assertEquals(3, session2Count, "Session 2 should have 3 messages (2 tokens + complete)");
    }

    @Test
    public void testSessionIsolation() {
        var resource1 = mock(AtmosphereResource.class);
        when(resource1.uuid()).thenReturn("res-1");
        var broadcaster1 = mock(Broadcaster.class);
        when(resource1.getBroadcaster()).thenReturn(broadcaster1);
        when(broadcaster1.broadcast(anyString())).thenReturn(mock(Future.class));
        var resource2 = mock(AtmosphereResource.class);
        when(resource2.uuid()).thenReturn("res-2");
        var broadcaster2 = mock(Broadcaster.class);
        when(resource2.getBroadcaster()).thenReturn(broadcaster2);
        when(broadcaster2.broadcast(anyString())).thenReturn(mock(Future.class));

        var session1 = StreamingSessions.start("iso-1", resource1);
        var session2 = StreamingSessions.start("iso-2", resource2);

        session1.send("Only for broadcaster1");
        session2.send("Only for broadcaster2");

        verify(broadcaster1, times(1)).broadcast(anyString());
        verify(broadcaster2, times(1)).broadcast(anyString());

        // Closing one should not affect the other
        session1.complete();
        assertTrue(session1.isClosed());
        assertFalse(session2.isClosed());

        session2.send("Still works");
        verify(broadcaster2, times(2)).broadcast(anyString());
    }

    @Test
    public void testConcurrentCloseAndSend() throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        var broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(anyString())).thenReturn(mock(Future.class));
        var session = StreamingSessions.start("race-session", resource);

        int threadCount = 50;
        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(threadCount);
        var errors = new AtomicInteger(0);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Half the threads send, half try to close
            for (int t = 0; t < threadCount; t++) {
                final int idx = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        if (idx % 2 == 0) {
                            session.send("token-" + idx);
                        } else {
                            session.complete();
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
        }

        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "No exceptions should be thrown");
        assertTrue(session.isClosed(), "Session should be closed");
    }

    @Test
    public void testAutoCloseInTryWithResources() throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        var broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(anyString())).thenReturn(mock(Future.class));
        List<String> capturedTypes = new ArrayList<>();

        doAnswer(inv -> {
            String json = inv.getArgument(0);
            var node = MAPPER.readTree(json);
            capturedTypes.add(node.get("type").asText());
            return null;
        }).when(broadcaster).broadcast(anyString());

        try (var session = StreamingSessions.start("auto-close", resource)) {
            session.send("token1");
            session.send("token2");
            session.progress("Working...");
        }

        // Should have: token, token, progress, complete (auto-close)
        assertEquals(4, capturedTypes.size());
        assertEquals("token", capturedTypes.get(0));
        assertEquals("token", capturedTypes.get(1));
        assertEquals("progress", capturedTypes.get(2));
        assertEquals("complete", capturedTypes.get(3));
    }

    @Test
    public void testMetadataInterspersedWithTokens() throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        var broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(anyString())).thenReturn(mock(Future.class));
        var session = StreamingSessions.start("meta-session", resource);

        session.sendMetadata("model", "gpt-4o");
        session.sendMetadata("temperature", 0.7);
        session.send("Hello");
        session.sendMetadata("tokens_used", 42);
        session.send(" world");
        session.complete("Hello world");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(broadcaster, times(6)).broadcast(captor.capture());

        var messages = captor.getAllValues().stream()
                .map(m -> {
                    try {
                        return MAPPER.readTree((String) m);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .toList();

        // Verify metadata messages have key/value pairs
        var metaMsgs = messages.stream()
                .filter(j -> "metadata".equals(j.get("type").asText()))
                .toList();
        assertEquals(3, metaMsgs.size());
        assertEquals("model", metaMsgs.get(0).get("key").asText());
        assertEquals("gpt-4o", metaMsgs.get(0).get("value").asText());
        assertEquals("temperature", metaMsgs.get(1).get("key").asText());
        assertEquals(0.7, metaMsgs.get(1).get("value").asDouble());
        assertEquals("tokens_used", metaMsgs.get(2).get("key").asText());
        assertEquals(42, metaMsgs.get(2).get("value").asInt());
    }

    @Test
    public void testErrorClosesSession() {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        var broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(anyString())).thenReturn(mock(Future.class));
        var session = StreamingSessions.start("error-session", resource);

        session.send("some data");
        session.error(new IllegalStateException("Something went wrong"));

        assertTrue(session.isClosed());

        // Subsequent sends should be ignored
        session.send("ignored");

        verify(broadcaster, times(2)).broadcast(anyString()); // token + error only
    }

    @Test
    public void testErrorAfterCompleteIsIgnored() {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        var broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(anyString())).thenReturn(mock(Future.class));
        var session = StreamingSessions.start("double-close", resource);

        session.complete();
        session.error(new RuntimeException("Should be ignored"));

        verify(broadcaster, times(1)).broadcast(anyString()); // only complete
    }

    @Test
    public void testWireProtocolFormat() throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        var broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(anyString())).thenReturn(mock(Future.class));
        var session = StreamingSessions.start("wire-test", resource);

        session.send("Hello");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(broadcaster).broadcast(captor.capture());

        String raw = captor.getValue();
        JsonNode json = MAPPER.readTree(raw);

        // Verify all required wire protocol fields are present
        assertTrue(json.has("type"), "Must have 'type' field");
        assertTrue(json.has("data"), "Token must have 'data' field");
        assertTrue(json.has("sessionId"), "Must have 'sessionId' field");
        assertTrue(json.has("seq"), "Must have 'seq' field");

        assertEquals("token", json.get("type").asText());
        assertEquals("Hello", json.get("data").asText());
        assertEquals("wire-test", json.get("sessionId").asText());
        assertTrue(json.get("seq").asLong() > 0, "Seq should be positive");

        // Verify it's valid JSON that JavaScript can parse
        assertNotNull(MAPPER.readTree(raw));
    }

    @Test
    public void testLargeTokenPayload() throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        var broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(anyString())).thenReturn(mock(Future.class));
        var session = StreamingSessions.start("large-session", resource);

        // Simulate a large token (e.g. a code block)
        String largeToken = "x".repeat(10_000);
        session.send(largeToken);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(broadcaster).broadcast(captor.capture());

        var json = MAPPER.readTree(captor.getValue());
        assertEquals(10_000, json.get("data").asText().length());
    }

    @Test
    public void testSpecialCharactersInTokens() throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        var broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(anyString())).thenReturn(mock(Future.class));
        var session = StreamingSessions.start("special-session", resource);

        session.send("He said \"hello\" and\nnewline\ttab");
        session.send("Unicode: 你好世界 🌍");
        session.send("<script>alert('xss')</script>");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(broadcaster, times(3)).broadcast(captor.capture());

        // All should be valid JSON
        for (var msg : captor.getAllValues()) {
            JsonNode json = MAPPER.readTree(msg);
            assertNotNull(json.get("data").asText());
        }
    }
}
