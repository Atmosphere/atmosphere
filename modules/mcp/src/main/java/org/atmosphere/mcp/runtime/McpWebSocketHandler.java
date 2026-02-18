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

import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketHandler;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * WebSocket handler for MCP connections. Processes incoming WebSocket text frames
 * as JSON-RPC messages and writes responses back on the same WebSocket.
 */
public final class McpWebSocketHandler implements WebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(McpWebSocketHandler.class);

    private final McpProtocolHandler protocolHandler;
    private final McpHandler mcpHandler;

    public McpWebSocketHandler(McpProtocolHandler protocolHandler) {
        this(protocolHandler, null);
    }

    public McpWebSocketHandler(McpProtocolHandler protocolHandler, McpHandler mcpHandler) {
        this.protocolHandler = protocolHandler;
        this.mcpHandler = mcpHandler;
    }

    @Override
    public void onByteMessage(WebSocket webSocket, byte[] data, int offset, int length) throws IOException {
        onTextMessage(webSocket, new String(data, offset, length));
    }

    @Override
    public void onTextMessage(WebSocket webSocket, String message) throws IOException {
        var resource = webSocket.resource();
        if (resource == null) {
            logger.warn("WebSocket message received but no AtmosphereResource attached");
            return;
        }

        var response = protocolHandler.handleMessage(resource, message);
        if (response != null) {
            webSocket.write(response);
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) throws IOException {
        var resource = webSocket.resource();
        logger.debug("MCP WebSocket opened: {}", resource.uuid());

        // Restore session from Mcp-Session-Id header if present
        if (mcpHandler != null) {
            var sessionId = resource.getRequest().getHeader(McpSession.SESSION_ID_HEADER);
            if (sessionId != null) {
                var session = mcpHandler.sessions().get(sessionId);
                if (session != null) {
                    resource.getRequest().setAttribute(McpSession.ATTRIBUTE_KEY, session);
                    session.touch();
                    // Replay any pending notifications
                    var pending = session.drainPendingNotifications();
                    for (var notification : pending) {
                        webSocket.write(notification);
                    }
                    if (!pending.isEmpty()) {
                        logger.debug("Replayed {} pending notifications on WebSocket reconnect for session {}",
                                pending.size(), sessionId);
                    }
                }
            }
        }
    }

    @Override
    public void onClose(WebSocket webSocket) {
        logger.debug("MCP WebSocket closed: {}",
                webSocket.resource() != null ? webSocket.resource().uuid() : "unknown");
    }

    @Override
    public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) {
        logger.warn("MCP WebSocket error", t);
    }
}
