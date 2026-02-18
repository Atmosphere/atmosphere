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
import org.mockito.ArgumentCaptor;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

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
        when(resource.write(anyString())).thenReturn(resource);
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
        verify(resource, times(threadCount * tokensPerThread)).write(captor.capture());

        // Verify all sequence numbers are unique
        Set<Long> seqs = ConcurrentHashMap.newKeySet();
        for (var msg : captor.getAllValues()) {
            var json = MAPPER.readTree(msg);
            assertTrue(seqs.add(json.get("seq").asLong()),
                    "Sequence number should be unique: " + json.get("seq"));
        }

        assertEquals(seqs.size(), threadCount * tokensPerThread);
    }

    @Test
    public void testMultipleSessionsSameBroadcaster() throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        when(resource.write(anyString())).thenReturn(resource);

        var session1 = StreamingSessions.start("session-1", resource);
        var session2 = StreamingSessions.start("session-2", resource);

        session1.send("Hello from 1");
        session2.send("Hello from 2");
        session1.complete();
        session2.send("Still going");
        session2.complete();

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, times(5)).write(captor.capture());

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

        assertEquals(session1Count, 2, "Session 1 should have 2 messages (token + complete)");
        assertEquals(session2Count, 3, "Session 2 should have 3 messages (2 tokens + complete)");
    }

    @Test
    public void testSessionIsolation() {
        var resource1 = mock(AtmosphereResource.class);
        when(resource1.uuid()).thenReturn("res-1");
        when(resource1.write(anyString())).thenReturn(resource1);
        var resource2 = mock(AtmosphereResource.class);
        when(resource2.uuid()).thenReturn("res-2");
        when(resource2.write(anyString())).thenReturn(resource2);

        var session1 = StreamingSessions.start("iso-1", resource1);
        var session2 = StreamingSessions.start("iso-2", resource2);

        session1.send("Only for broadcaster1");
        session2.send("Only for broadcaster2");

        verify(resource1, times(1)).write(anyString());
        verify(resource2, times(1)).write(anyString());

        // Closing one should not affect the other
        session1.complete();
        assertTrue(session1.isClosed());
        assertFalse(session2.isClosed());

        session2.send("Still works");
        verify(resource2, times(2)).write(anyString());
    }

    @Test
    public void testConcurrentCloseAndSend() throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        when(resource.write(anyString())).thenReturn(resource);
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
        assertEquals(errors.get(), 0, "No exceptions should be thrown");
        assertTrue(session.isClosed(), "Session should be closed");
    }

    @Test
    public void testAutoCloseInTryWithResources() throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        when(resource.write(anyString())).thenReturn(resource);
        List<String> capturedTypes = new ArrayList<>();

        doAnswer(inv -> {
            String json = inv.getArgument(0);
            var node = MAPPER.readTree(json);
            capturedTypes.add(node.get("type").asText());
            return null;
        }).when(resource).write(anyString());

        try (var session = StreamingSessions.start("auto-close", resource)) {
            session.send("token1");
            session.send("token2");
            session.progress("Working...");
        }

        // Should have: token, token, progress, complete (auto-close)
        assertEquals(capturedTypes.size(), 4);
        assertEquals(capturedTypes.get(0), "token");
        assertEquals(capturedTypes.get(1), "token");
        assertEquals(capturedTypes.get(2), "progress");
        assertEquals(capturedTypes.get(3), "complete");
    }

    @Test
    public void testMetadataInterspersedWithTokens() throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        when(resource.write(anyString())).thenReturn(resource);
        var session = StreamingSessions.start("meta-session", resource);

        session.sendMetadata("model", "gpt-4o");
        session.sendMetadata("temperature", 0.7);
        session.send("Hello");
        session.sendMetadata("tokens_used", 42);
        session.send(" world");
        session.complete("Hello world");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, times(6)).write(captor.capture());

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
        assertEquals(metaMsgs.size(), 3);
        assertEquals(metaMsgs.get(0).get("key").asText(), "model");
        assertEquals(metaMsgs.get(0).get("value").asText(), "gpt-4o");
        assertEquals(metaMsgs.get(1).get("key").asText(), "temperature");
        assertEquals(metaMsgs.get(1).get("value").asDouble(), 0.7);
        assertEquals(metaMsgs.get(2).get("key").asText(), "tokens_used");
        assertEquals(metaMsgs.get(2).get("value").asInt(), 42);
    }

    @Test
    public void testErrorClosesSession() {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        when(resource.write(anyString())).thenReturn(resource);
        var session = StreamingSessions.start("error-session", resource);

        session.send("some data");
        session.error(new IllegalStateException("Something went wrong"));

        assertTrue(session.isClosed());

        // Subsequent sends should be ignored
        session.send("ignored");

        verify(resource, times(2)).write(anyString()); // token + error only
    }

    @Test
    public void testErrorAfterCompleteIsIgnored() {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        when(resource.write(anyString())).thenReturn(resource);
        var session = StreamingSessions.start("double-close", resource);

        session.complete();
        session.error(new RuntimeException("Should be ignored"));

        verify(resource, times(1)).write(anyString()); // only complete
    }

    @Test
    public void testWireProtocolFormat() throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        when(resource.write(anyString())).thenReturn(resource);
        var session = StreamingSessions.start("wire-test", resource);

        session.send("Hello");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource).write(captor.capture());

        String raw = captor.getValue();
        JsonNode json = MAPPER.readTree(raw);

        // Verify all required wire protocol fields are present
        assertTrue(json.has("type"), "Must have 'type' field");
        assertTrue(json.has("data"), "Token must have 'data' field");
        assertTrue(json.has("sessionId"), "Must have 'sessionId' field");
        assertTrue(json.has("seq"), "Must have 'seq' field");

        assertEquals(json.get("type").asText(), "token");
        assertEquals(json.get("data").asText(), "Hello");
        assertEquals(json.get("sessionId").asText(), "wire-test");
        assertTrue(json.get("seq").asLong() > 0, "Seq should be positive");

        // Verify it's valid JSON that JavaScript can parse
        assertNotNull(MAPPER.readTree(raw));
    }

    @Test
    public void testLargeTokenPayload() throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        when(resource.write(anyString())).thenReturn(resource);
        var session = StreamingSessions.start("large-session", resource);

        // Simulate a large token (e.g. a code block)
        String largeToken = "x".repeat(10_000);
        session.send(largeToken);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource).write(captor.capture());

        var json = MAPPER.readTree(captor.getValue());
        assertEquals(json.get("data").asText().length(), 10_000);
    }

    @Test
    public void testSpecialCharactersInTokens() throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("res-1");
        when(resource.write(anyString())).thenReturn(resource);
        var session = StreamingSessions.start("special-session", resource);

        session.send("He said \"hello\" and\nnewline\ttab");
        session.send("Unicode: 你好世界 🌍");
        session.send("<script>alert('xss')</script>");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(resource, times(3)).write(captor.capture());

        // All should be valid JSON
        for (var msg : captor.getAllValues()) {
            JsonNode json = MAPPER.readTree(msg);
            assertNotNull(json.get("data").asText());
        }
    }
}
