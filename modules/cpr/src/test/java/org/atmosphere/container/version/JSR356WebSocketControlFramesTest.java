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
package org.atmosphere.container.version;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Extension;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (registre#10): {@code sendPing}/{@code sendPong} threw
 * {@link UnsupportedOperationException} on every binding despite the
 * javadoc promising a ping, and {@code close(code, reason)} was an empty
 * body — a caller closing with a code and reason got a silent no-op and
 * the connection stayed open. The JSR356 binding must transmit control
 * frames, and the base class must at least close the connection.
 *
 * <p>Hand-rolled fakes are used instead of Mockito — the JSR356 interfaces
 * cannot be mocked on JDK 21+ (see the JSR356WebSocketTest exclusion).</p>
 */
class JSR356WebSocketControlFramesTest {

    private AtmosphereFramework framework;
    private FakeSession session;
    private JSR356WebSocket webSocket;

    @BeforeEach
    void setUp() throws Exception {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(new BlockingIOCometSupport(framework.getAtmosphereConfig()));
        framework.init();
        session = new FakeSession();
        webSocket = new JSR356WebSocket(session, framework.getAtmosphereConfig());
    }

    @AfterEach
    void tearDown() {
        framework.destroy();
    }

    @Test
    void sendPingTransmitsTheControlFrame() {
        webSocket.sendPing("keepalive".getBytes(StandardCharsets.UTF_8));

        assertNotNull(session.remote.lastPing, "the ping must reach the container");
        assertArrayEquals("keepalive".getBytes(StandardCharsets.UTF_8),
                toBytes(session.remote.lastPing));
    }

    @Test
    void sendPongTransmitsTheControlFrame() {
        webSocket.sendPong("alive".getBytes(StandardCharsets.UTF_8));

        assertNotNull(session.remote.lastPong, "the pong must reach the container");
        assertArrayEquals("alive".getBytes(StandardCharsets.UTF_8),
                toBytes(session.remote.lastPong));
    }

    @Test
    void controlFramesOnAClosedSocketFailLoudlyNotSilently() {
        session.open = false;
        assertThrows(IllegalStateException.class,
                () -> webSocket.sendPing(new byte[0]));
        assertThrows(IllegalStateException.class,
                () -> webSocket.sendPong(new byte[0]));
    }

    @Test
    void closeWithCodeAndReasonActuallyClosesWithThem() {
        webSocket.close(1001, "going away");

        assertNotNull(session.lastCloseReason,
                "close(code, reason) must not be a silent no-op");
        assertEquals(1001, session.lastCloseReason.getCloseCode().getCode());
        assertEquals("going away", session.lastCloseReason.getReasonPhrase());
    }

    @Test
    void oversizedCloseReasonIsTruncatedNotRejected() {
        webSocket.close(1000, "x".repeat(300));

        assertNotNull(session.lastCloseReason);
        assertEquals(123, session.lastCloseReason.getReasonPhrase().length(),
                "RFC 6455 caps the reason at 123 bytes — truncate, don't fail");
    }

    /** The base class must close the connection even without native support. */
    @Test
    void baseCloseWithCodeDelegatesToClose() {
        var closed = new boolean[1];
        var base = new WebSocket(framework.getAtmosphereConfig()) {
            @Override public boolean isOpen() { return !closed[0]; }
            @Override public WebSocket write(String s) { return this; }
            @Override public WebSocket write(byte[] b, int offset, int length) { return this; }
            @Override public void close() { closed[0] = true; }
        };

        base.close(1000, "bye");

        assertTrue(closed[0], "the connection must not silently stay open when "
                + "a binding cannot transmit the code and reason");
    }

    private static byte[] toBytes(ByteBuffer buffer) {
        var bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    // ---- fakes ----

    private static final class FakeBasicRemote implements RemoteEndpoint.Basic {
        ByteBuffer lastPing;
        ByteBuffer lastPong;

        @Override public void sendPing(ByteBuffer data) { lastPing = data; }
        @Override public void sendPong(ByteBuffer data) { lastPong = data; }
        @Override public void sendText(String text) { }
        @Override public void sendBinary(ByteBuffer data) { }
        @Override public void sendText(String partialMessage, boolean isLast) { }
        @Override public void sendBinary(ByteBuffer partialByte, boolean isLast) { }
        @Override public OutputStream getSendStream() { return OutputStream.nullOutputStream(); }
        @Override public Writer getSendWriter() { return Writer.nullWriter(); }
        @Override public void sendObject(Object data) { }
        @Override public void setBatchingAllowed(boolean allowed) { }
        @Override public boolean getBatchingAllowed() { return false; }
        @Override public void flushBatch() { }
    }

    private static final class FakeSession implements Session {
        final FakeBasicRemote remote = new FakeBasicRemote();
        boolean open = true;
        CloseReason lastCloseReason;

        @Override public boolean isOpen() { return open; }
        @Override public RemoteEndpoint.Basic getBasicRemote() { return remote; }
        @Override public void close() { open = false; }
        @Override public void close(CloseReason closeReason) {
            open = false;
            lastCloseReason = closeReason;
        }
        @Override public WebSocketContainer getContainer() { return null; }
        @Override public void addMessageHandler(MessageHandler handler) { }
        @Override public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Whole<T> handler) { }
        @Override public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Partial<T> handler) { }
        @Override public Set<MessageHandler> getMessageHandlers() { return Set.of(); }
        @Override public void removeMessageHandler(MessageHandler handler) { }
        @Override public String getProtocolVersion() { return "13"; }
        @Override public String getNegotiatedSubprotocol() { return ""; }
        @Override public List<Extension> getNegotiatedExtensions() { return List.of(); }
        @Override public boolean isSecure() { return false; }
        @Override public long getMaxIdleTimeout() { return 0; }
        @Override public void setMaxIdleTimeout(long milliseconds) { }
        @Override public void setMaxBinaryMessageBufferSize(int length) { }
        @Override public int getMaxBinaryMessageBufferSize() { return 0; }
        @Override public void setMaxTextMessageBufferSize(int length) { }
        @Override public int getMaxTextMessageBufferSize() { return 0; }
        @Override public RemoteEndpoint.Async getAsyncRemote() { return asyncRemote; }

        private final RemoteEndpoint.Async asyncRemote = new RemoteEndpoint.Async() {
            @Override public long getSendTimeout() { return 0; }
            @Override public void setSendTimeout(long timeoutmillis) { }
            @Override public void sendText(String text, jakarta.websocket.SendHandler handler) { }
            @Override public java.util.concurrent.Future<Void> sendText(String text) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            @Override public java.util.concurrent.Future<Void> sendBinary(ByteBuffer data) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            @Override public void sendBinary(ByteBuffer data, jakarta.websocket.SendHandler handler) { }
            @Override public java.util.concurrent.Future<Void> sendObject(Object data) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            @Override public void sendObject(Object data, jakarta.websocket.SendHandler handler) { }
            @Override public void setBatchingAllowed(boolean allowed) { }
            @Override public boolean getBatchingAllowed() { return false; }
            @Override public void flushBatch() { }
            @Override public void sendPing(ByteBuffer applicationData) { }
            @Override public void sendPong(ByteBuffer applicationData) { }
        };
        @Override public String getId() { return "fake-session"; }
        @Override public URI getRequestURI() { return URI.create("/"); }
        @Override public Map<String, List<String>> getRequestParameterMap() { return Map.of(); }
        @Override public String getQueryString() { return ""; }
        @Override public Map<String, String> getPathParameters() { return Map.of(); }
        @Override public Map<String, Object> getUserProperties() { return Map.of(); }
        @Override public Principal getUserPrincipal() { return null; }
        @Override public Set<Session> getOpenSessions() { return Set.of(); }
    }
}
