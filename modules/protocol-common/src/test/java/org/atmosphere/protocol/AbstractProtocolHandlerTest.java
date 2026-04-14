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
package org.atmosphere.protocol;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AbstractProtocolHandler} — HTTP dispatch, session lifecycle,
 * TTL eviction, and response writing.
 */
class AbstractProtocolHandlerTest {

    private TestProtocolHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TestProtocolHandler();
    }

    // ── HTTP dispatch ──

    @Test
    void onRequest_dispatchesPost() throws IOException {
        var resource = mockResource("POST");
        handler.onRequest(resource);
        assertEquals("POST", handler.lastMethod);
    }

    @Test
    void onRequest_dispatchesGet() throws IOException {
        var resource = mockResource("GET");
        handler.onRequest(resource);
        assertEquals("GET", handler.lastMethod);
    }

    @Test
    void onRequest_dispatchesDelete() throws IOException {
        var resource = mockResource("DELETE");
        handler.onRequest(resource);
        assertEquals("DELETE", handler.lastMethod);
    }

    @Test
    void onRequest_returns405ForUnsupportedMethod() throws IOException {
        var resource = mockResource("PATCH");
        var response = resource.getResponse();
        handler.onRequest(resource);
        verify(response).setStatus(405);
    }

    // ── onStateChange ──

    @Test
    void onStateChange_writesStringMessage() throws IOException {
        var event = mock(AtmosphereResourceEvent.class);
        var resource = mock(AtmosphereResource.class);
        var response = mock(AtmosphereResponse.class);
        var sw = new StringWriter();
        when(event.getResource()).thenReturn(resource);
        when(resource.getResponse()).thenReturn(response);
        when(response.getWriter()).thenReturn(new PrintWriter(sw));
        when(event.getMessage()).thenReturn("hello");

        handler.onStateChange(event);
        assertEquals("hello", sw.toString());
    }

    @Test
    void onStateChange_writesListOfStrings() throws IOException {
        var event = mock(AtmosphereResourceEvent.class);
        var resource = mock(AtmosphereResource.class);
        var response = mock(AtmosphereResponse.class);
        var sw = new StringWriter();
        when(event.getResource()).thenReturn(resource);
        when(resource.getResponse()).thenReturn(response);
        when(response.getWriter()).thenReturn(new PrintWriter(sw));
        when(event.getMessage()).thenReturn(List.of("a", "b"));

        handler.onStateChange(event);
        assertEquals("ab", sw.toString());
    }

    @Test
    void onStateChange_skipsOnCancel() throws IOException {
        var event = mock(AtmosphereResourceEvent.class);
        var resource = mock(AtmosphereResource.class);
        when(event.getResource()).thenReturn(resource);
        when(event.isCancelled()).thenReturn(true);
        when(resource.uuid()).thenReturn("test-uuid");

        handler.onStateChange(event);
        // Should not throw, should return early
    }

    @Test
    void onStateChange_skipsOnClosedByClient() throws IOException {
        var event = mock(AtmosphereResourceEvent.class);
        var resource = mock(AtmosphereResource.class);
        when(event.getResource()).thenReturn(resource);
        when(event.isClosedByClient()).thenReturn(true);
        when(resource.uuid()).thenReturn("test-uuid");

        handler.onStateChange(event);
    }

    // ── Session management ──

    @Test
    void registerSession_addsToStore() {
        var session = new ProtocolSession();
        var response = mock(AtmosphereResponse.class);
        handler.registerSession(session, response);

        assertEquals(1, handler.sessions().size());
        assertTrue(handler.sessions().containsKey(session.sessionId()));
        verify(response).setHeader("X-Test-Session", session.sessionId());
    }

    @Test
    void restoreSession_returnsSavedSession() {
        var session = new ProtocolSession();
        var response = mock(AtmosphereResponse.class);
        handler.registerSession(session, response);

        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var resp2 = mock(AtmosphereResponse.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(resp2);
        when(request.getHeader("X-Test-Session")).thenReturn(session.sessionId());

        var restored = handler.restoreSession(resource);
        assertNotNull(restored);
        assertEquals(session.sessionId(), restored.sessionId());
    }

    @Test
    void restoreSession_returnsNullForMissingSession() {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getHeader("X-Test-Session")).thenReturn("nonexistent");

        assertNull(handler.restoreSession(resource));
    }

    @Test
    void restoreSession_returnsNullForMissingHeader() {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getHeader("X-Test-Session")).thenReturn(null);

        assertNull(handler.restoreSession(resource));
    }

    @Test
    void removeSessionByHeader_removesSession() {
        var session = new ProtocolSession();
        var response = mock(AtmosphereResponse.class);
        handler.registerSession(session, response);

        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getHeader("X-Test-Session")).thenReturn(session.sessionId());

        var removed = handler.removeSessionByHeader(resource);
        assertNotNull(removed);
        assertTrue(handler.sessions().isEmpty());
    }

    // ── writeResponse ──

    @Test
    void writeResponse_writesJsonByDefault() throws IOException {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        var sw = new StringWriter();
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(request.getHeader("Accept")).thenReturn("application/json");
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        handler.writeResponse(resource, "{\"ok\":true}");
        verify(response).setContentType("application/json");
        assertEquals("{\"ok\":true}", sw.toString());
    }

    @Test
    void writeResponse_writesSSEWhenAcceptEventStream() throws IOException {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        var sw = new StringWriter();
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(request.getHeader("Accept")).thenReturn("text/event-stream");
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        handler.writeResponse(resource, "{\"data\":1}");
        verify(response).setContentType("text/event-stream");
        assertTrue(sw.toString().contains("event: message"));
        assertTrue(sw.toString().contains("data: {\"data\":1}"));
    }

    // ── destroy ──

    @Test
    void destroy_clearsSessions() {
        var session = new ProtocolSession();
        var response = mock(AtmosphereResponse.class);
        handler.registerSession(session, response);
        assertEquals(1, handler.sessions().size());

        handler.destroy();
        assertTrue(handler.sessions().isEmpty());
    }

    // ── Replay pending ──

    @Test
    void replayPending_sendsBufferedNotifications() throws IOException {
        var session = new ProtocolSession();
        session.addPendingNotification("{\"type\":\"notify1\"}");
        session.addPendingNotification("{\"type\":\"notify2\"}");

        var response = mock(AtmosphereResponse.class);
        var sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        handler.replayPending(session, response);
        var output = sw.toString();
        assertTrue(output.contains("notify1"));
        assertTrue(output.contains("notify2"));
        assertEquals(0, session.pendingCount());
    }

    // ── helpers ──

    private AtmosphereResource mockResource(String method) {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        var sw = new StringWriter();
        try {
            when(response.getWriter()).thenReturn(new PrintWriter(sw));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(request.getMethod()).thenReturn(method);
        return resource;
    }

    /**
     * Concrete implementation for testing the abstract handler.
     */
    static class TestProtocolHandler extends AbstractProtocolHandler<ProtocolSession> {
        String lastMethod;

        TestProtocolHandler() {
            super(60_000L, "X-Test-Session", "test.session", "test-cleaner");
        }

        @Override
        protected void handlePost(AtmosphereResource resource) {
            lastMethod = "POST";
        }

        @Override
        protected void handleGet(AtmosphereResource resource) {
            lastMethod = "GET";
        }

        @Override
        protected void handleDelete(AtmosphereResource resource) {
            lastMethod = "DELETE";
        }
    }
}
