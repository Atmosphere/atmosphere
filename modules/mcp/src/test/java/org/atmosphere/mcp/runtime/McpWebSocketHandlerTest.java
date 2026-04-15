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
package org.atmosphere.mcp.runtime;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpWebSocketHandlerTest {

    private McpProtocolHandler protocolHandler;
    private McpHandler mcpHandler;
    private WebSocket webSocket;
    private AtmosphereResource resource;
    private AtmosphereRequest request;

    @BeforeEach
    void setUp() {
        protocolHandler = Mockito.mock(McpProtocolHandler.class);
        mcpHandler = Mockito.mock(McpHandler.class);
        webSocket = Mockito.mock(WebSocket.class);
        resource = Mockito.mock(AtmosphereResource.class);
        request = Mockito.mock(AtmosphereRequest.class);
        when(webSocket.resource()).thenReturn(resource);
        when(resource.getRequest()).thenReturn(request);
    }

    @Test
    void onTextMessageDelegatesToProtocolHandler() throws IOException {
        when(protocolHandler.handleMessage(resource, "{\"jsonrpc\":\"2.0\"}"))
                .thenReturn("{\"result\":\"ok\"}");
        var handler = new McpWebSocketHandler(protocolHandler);
        handler.onTextMessage(webSocket, "{\"jsonrpc\":\"2.0\"}");
        verify(webSocket).write("{\"result\":\"ok\"}");
    }

    @Test
    void onTextMessageDoesNotWriteWhenResponseIsNull() throws IOException {
        when(protocolHandler.handleMessage(resource, "notification"))
                .thenReturn(null);
        var handler = new McpWebSocketHandler(protocolHandler);
        handler.onTextMessage(webSocket, "notification");
        verify(webSocket, never()).write(Mockito.anyString());
    }

    @Test
    void onTextMessageSkipsWhenNoResourceAttached() throws IOException {
        when(webSocket.resource()).thenReturn(null);
        var handler = new McpWebSocketHandler(protocolHandler);
        handler.onTextMessage(webSocket, "message");
        verify(protocolHandler, never()).handleMessage(Mockito.any(), Mockito.anyString());
    }

    @Test
    void onByteMessageDelegatesToOnTextMessage() throws IOException {
        var text = "{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}";
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        when(protocolHandler.handleMessage(resource, text))
                .thenReturn("{\"result\":\"pong\"}");
        var handler = new McpWebSocketHandler(protocolHandler);
        handler.onByteMessage(webSocket, bytes, 0, bytes.length);
        verify(webSocket).write("{\"result\":\"pong\"}");
    }

    @Test
    void onByteMessageRespectsOffsetAndLength() throws IOException {
        var fullBytes = "PREFIX{\"id\":1}SUFFIX".getBytes(StandardCharsets.UTF_8);
        int offset = 6;
        int length = 8; // "{\"id\":1}"
        when(protocolHandler.handleMessage(resource, "{\"id\":1}"))
                .thenReturn("{\"ok\":true}");
        var handler = new McpWebSocketHandler(protocolHandler);
        handler.onByteMessage(webSocket, fullBytes, offset, length);
        verify(webSocket).write("{\"ok\":true}");
    }

    @Test
    void onOpenWithoutMcpHandlerLogsOnly() throws IOException {
        when(resource.uuid()).thenReturn("res-123");
        var handler = new McpWebSocketHandler(protocolHandler);
        handler.onOpen(webSocket);
        // No exception, no session restoration
    }

    @Test
    void onOpenSkipsWhenNoResourceAttached() throws IOException {
        when(webSocket.resource()).thenReturn(null);
        var handler = new McpWebSocketHandler(protocolHandler, mcpHandler);
        handler.onOpen(webSocket);
        verify(mcpHandler, never()).sessions();
    }

    @Test
    void onOpenRestoresSessionAndReplaysPendingNotifications() throws IOException {
        var session = new McpSession();
        session.addPendingNotification("{\"notify\":1}");
        session.addPendingNotification("{\"notify\":2}");

        Map<String, McpSession> sessionsMap = new ConcurrentHashMap<>();
        sessionsMap.put(session.sessionId(), session);
        when(mcpHandler.sessions()).thenReturn(sessionsMap);
        when(request.getHeader(McpSession.SESSION_ID_HEADER))
                .thenReturn(session.sessionId());
        when(resource.uuid()).thenReturn("res-456");

        var handler = new McpWebSocketHandler(protocolHandler, mcpHandler);
        handler.onOpen(webSocket);

        verify(webSocket).write("{\"notify\":1}");
        verify(webSocket).write("{\"notify\":2}");
        verify(request).setAttribute(McpSession.ATTRIBUTE_KEY, session);
    }

    @Test
    void onOpenWithUnknownSessionIdDoesNotCrash() throws IOException {
        Map<String, McpSession> sessionsMap = new ConcurrentHashMap<>();
        when(mcpHandler.sessions()).thenReturn(sessionsMap);
        when(request.getHeader(McpSession.SESSION_ID_HEADER))
                .thenReturn("nonexistent-id");
        when(resource.uuid()).thenReturn("res-789");

        var handler = new McpWebSocketHandler(protocolHandler, mcpHandler);
        handler.onOpen(webSocket);
        // No exception, no write
        verify(webSocket, never()).write(Mockito.anyString());
    }

    @Test
    void onOpenWithNoSessionHeaderSkipsRestoration() throws IOException {
        when(request.getHeader(McpSession.SESSION_ID_HEADER)).thenReturn(null);
        when(resource.uuid()).thenReturn("res-101");

        var handler = new McpWebSocketHandler(protocolHandler, mcpHandler);
        handler.onOpen(webSocket);
        verify(mcpHandler, never()).sessions();
    }

    @Test
    void onCloseDoesNotThrow() {
        var handler = new McpWebSocketHandler(protocolHandler);
        when(webSocket.resource()).thenReturn(resource);
        when(resource.uuid()).thenReturn("uuid-1");
        handler.onClose(webSocket);
    }

    @Test
    void onCloseHandlesNullResource() {
        var handler = new McpWebSocketHandler(protocolHandler);
        when(webSocket.resource()).thenReturn(null);
        handler.onClose(webSocket);
    }

    @Test
    void onErrorDoesNotThrow() {
        var handler = new McpWebSocketHandler(protocolHandler);
        var exception = Mockito.mock(WebSocketProcessor.WebSocketException.class);
        handler.onError(webSocket, exception);
    }

    @Test
    void constructorWithProtocolHandlerOnly() throws IOException {
        var handler = new McpWebSocketHandler(protocolHandler);
        when(protocolHandler.handleMessage(resource, "msg")).thenReturn("resp");
        handler.onTextMessage(webSocket, "msg");
        verify(webSocket).write("resp");
    }
}
