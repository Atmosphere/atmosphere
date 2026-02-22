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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.Future;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class StreamingSessionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AtmosphereResource resource;
    private Broadcaster broadcaster;
    private StreamingSession session;

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        resource = mock(AtmosphereResource.class);
        broadcaster = mock(Broadcaster.class);
        when(resource.uuid()).thenReturn("resource-uuid");
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(anyString())).thenReturn(mock(Future.class));
        session = StreamingSessions.start("test-session", resource);
    }

    @Test
    public void testSendToken() throws Exception {
        session.send("Hello");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(broadcaster).broadcast(captor.capture());

        var json = MAPPER.readTree(captor.getValue());
        assertEquals("token", json.get("type").asText());
        assertEquals("Hello", json.get("data").asText());
        assertEquals("test-session", json.get("sessionId").asText());
        assertEquals(1L, json.get("seq").asLong());
    }

    @Test
    public void testSendMultipleTokensWithSequence() throws Exception {
        session.send("Hello");
        session.send(" world");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(broadcaster, times(2)).broadcast(captor.capture());

        List<String> messages = captor.getAllValues();
        var first = MAPPER.readTree(messages.get(0));
        var second = MAPPER.readTree(messages.get(1));

        assertEquals(1L, first.get("seq").asLong());
        assertEquals(2L, second.get("seq").asLong());
        assertEquals(" world", second.get("data").asText());
    }

    @Test
    public void testProgress() throws Exception {
        session.progress("Thinking...");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(broadcaster).broadcast(captor.capture());

        var json = MAPPER.readTree(captor.getValue());
        assertEquals("progress", json.get("type").asText());
        assertEquals("Thinking...", json.get("data").asText());
    }

    @Test
    public void testComplete() throws Exception {
        session.complete();

        var captor = ArgumentCaptor.forClass(String.class);
        verify(broadcaster).broadcast(captor.capture());

        var json = MAPPER.readTree(captor.getValue());
        assertEquals("complete", json.get("type").asText());
        assertFalse(json.has("data"));
        assertTrue(session.isClosed());
    }

    @Test
    public void testCompleteWithSummary() throws Exception {
        session.complete("Full response here");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(broadcaster).broadcast(captor.capture());

        var json = MAPPER.readTree(captor.getValue());
        assertEquals("complete", json.get("type").asText());
        assertEquals("Full response here", json.get("data").asText());
    }

    @Test
    public void testError() throws Exception {
        session.error(new RuntimeException("Connection lost"));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(broadcaster).broadcast(captor.capture());

        var json = MAPPER.readTree(captor.getValue());
        assertEquals("error", json.get("type").asText());
        assertEquals("Connection lost", json.get("data").asText());
        assertTrue(session.isClosed());
    }

    @Test
    public void testSendAfterCloseIsIgnored() {
        session.complete();
        reset(broadcaster);

        session.send("should be ignored");

        verify(broadcaster, never()).broadcast(anyString());
    }

    @Test
    public void testDoubleCompleteIsIgnored() {
        session.complete();
        reset(broadcaster);

        session.complete();

        verify(broadcaster, never()).broadcast(anyString());
    }

    @Test
    public void testSendMetadata() throws Exception {
        session.sendMetadata("model", "gpt-4");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(broadcaster).broadcast(captor.capture());

        var json = MAPPER.readTree(captor.getValue());
        assertEquals("metadata", json.get("type").asText());
        assertEquals("model", json.get("key").asText());
        assertEquals("gpt-4", json.get("value").asText());
    }

    @Test
    public void testAutoCloseCallsComplete() throws Exception {
        try (var s = session) {
            s.send("token");
        }

        var captor = ArgumentCaptor.forClass(String.class);
        verify(broadcaster, times(2)).broadcast(captor.capture());

        var last = MAPPER.readTree(captor.getAllValues().get(1));
        assertEquals("complete", last.get("type").asText());
        assertTrue(session.isClosed());
    }

    @Test
    public void testStartFromResource() {
        var res = mock(AtmosphereResource.class);
        when(res.uuid()).thenReturn("res-123");

        var s = StreamingSessions.start(res);
        assertNotNull(s);
        assertFalse(s.isClosed());
    }

    @Test
    public void testSessionId() {
        assertEquals("test-session", session.sessionId());
    }

    @Test
    public void testFullStreamLifecycle() throws Exception {
        session.progress("Connecting to model...");
        session.sendMetadata("model", "gpt-4o");
        session.send("Hello");
        session.send(" world");
        session.send("!");
        session.complete("Hello world!");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(broadcaster, times(6)).broadcast(captor.capture());

        List<String> messages = captor.getAllValues();
        var types = messages.stream()
                .map(m -> {
                    try {
                        return MAPPER.readTree(m).get("type").asText();
                    } catch (Exception e) {
                        return "error";
                    }
                })
                .toList();

        assertEquals(List.of("progress", "metadata", "token", "token", "token", "complete"), types);
        assertTrue(session.isClosed());
    }
}
