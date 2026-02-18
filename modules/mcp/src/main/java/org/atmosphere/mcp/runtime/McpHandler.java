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

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link AtmosphereHandler} that bridges Atmosphere's transport layer with the
 * MCP JSON-RPC protocol. Supports three transports:
 * <ul>
 *   <li><b>Streamable HTTP</b> (MCP spec 2025-03-26) — POST for requests, GET for SSE notifications, DELETE to end session</li>
 *   <li><b>WebSocket</b> — full-duplex JSON-RPC via WebSocket frames</li>
 *   <li><b>SSE fallback</b> — Atmosphere's automatic WebSocket→SSE downgrade</li>
 * </ul>
 * Registered at the path specified by {@code @McpServer}.
 */
public final class McpHandler implements AtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(McpHandler.class);
    private static final String APPLICATION_JSON = "application/json";
    private static final String TEXT_EVENT_STREAM = "text/event-stream";

    private final McpProtocolHandler protocolHandler;
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();

    public McpHandler(McpProtocolHandler protocolHandler) {
        this.protocolHandler = protocolHandler;
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        var method = resource.getRequest().getMethod();

        switch (method.toUpperCase()) {
            case "POST" -> handlePost(resource);
            case "GET" -> handleGet(resource);
            case "DELETE" -> handleDelete(resource);
            default -> {
                resource.getResponse().setStatus(405);
                resource.getResponse().getWriter().write("{\"error\":\"Method not allowed\"}");
            }
        }
    }

    /**
     * POST — Streamable HTTP: client sends JSON-RPC request, server responds with JSON or SSE.
     */
    private void handlePost(AtmosphereResource resource) throws IOException {
        var request = resource.getRequest();
        var response = resource.getResponse();

        // Read request body
        var reader = request.getReader();
        var sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }

        if (sb.isEmpty()) {
            response.setStatus(400);
            response.setContentType(APPLICATION_JSON);
            response.getWriter().write("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Empty body\"}}");
            return;
        }

        // Restore session from Mcp-Session-Id header if present
        var sessionId = request.getHeader(McpSession.SESSION_ID_HEADER);
        if (sessionId != null) {
            var session = sessions.get(sessionId);
            if (session != null) {
                request.setAttribute(McpSession.ATTRIBUTE_KEY, session);
            }
        }

        var jsonResponse = protocolHandler.handleMessage(resource, sb.toString());

        // After handleMessage, check if a new session was created (initialize)
        var session = (McpSession) request.getAttribute(McpSession.ATTRIBUTE_KEY);
        if (session != null) {
            sessions.putIfAbsent(session.sessionId(), session);
            response.setHeader(McpSession.SESSION_ID_HEADER, session.sessionId());
        }

        if (jsonResponse == null) {
            // Notification (no id) — respond with 202 Accepted
            response.setStatus(202);
            return;
        }

        var accept = request.getHeader("Accept");
        if (accept != null && accept.contains(TEXT_EVENT_STREAM)) {
            // SSE format
            response.setStatus(200);
            response.setContentType(TEXT_EVENT_STREAM);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("event: message\ndata: " + jsonResponse + "\n\n");
            response.getWriter().flush();
        } else {
            // Plain JSON
            response.setStatus(200);
            response.setContentType(APPLICATION_JSON);
            response.getWriter().write(jsonResponse);
            response.getWriter().flush();
        }
    }

    /**
     * GET — Opens SSE stream for server-initiated notifications.
     */
    private void handleGet(AtmosphereResource resource) throws IOException {
        var request = resource.getRequest();
        var response = resource.getResponse();

        // Restore session from header
        var sessionId = request.getHeader(McpSession.SESSION_ID_HEADER);
        if (sessionId != null) {
            var session = sessions.get(sessionId);
            if (session != null) {
                request.setAttribute(McpSession.ATTRIBUTE_KEY, session);
                response.setHeader(McpSession.SESSION_ID_HEADER, session.sessionId());
            }
        }

        // SSE notification stream or WebSocket upgrade
        response.setContentType(TEXT_EVENT_STREAM);
        response.setCharacterEncoding("UTF-8");
        resource.suspend();
    }

    /**
     * DELETE — Terminates MCP session.
     */
    private void handleDelete(AtmosphereResource resource) throws IOException {
        var request = resource.getRequest();
        var response = resource.getResponse();
        var sessionId = request.getHeader(McpSession.SESSION_ID_HEADER);

        if (sessionId != null) {
            var removed = sessions.remove(sessionId);
            if (removed != null) {
                logger.info("MCP session terminated: {}", sessionId);
            }
        }

        response.setStatus(204);
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        var resource = event.getResource();
        if (event.isCancelled() || event.isClosedByClient() || event.isClosedByApplication()) {
            logger.debug("MCP connection closed: {}", resource.uuid());
            return;
        }

        var message = event.getMessage();
        if (message instanceof String text) {
            // Incoming WebSocket message
            var response = protocolHandler.handleMessage(resource, text);
            if (response != null) {
                write(resource.getResponse(), response);
            }
        } else if (message instanceof List<?> list) {
            // Broadcast — write each message
            for (var item : list) {
                if (item instanceof String text) {
                    write(resource.getResponse(), text);
                }
            }
        }
    }

    /**
     * Called directly for WebSocket messages (bypasses onStateChange for request-response).
     */
    public void processMessage(AtmosphereResource resource, String message) throws IOException {
        var response = protocolHandler.handleMessage(resource, message);
        if (response != null) {
            write(resource.getResponse(), response);
        }
    }

    /**
     * Returns the session store (visible for testing).
     */
    public Map<String, McpSession> sessions() {
        return sessions;
    }

    @Override
    public void destroy() {
        sessions.clear();
        logger.debug("McpHandler destroyed");
    }

    private void write(AtmosphereResponse response, String data) throws IOException {
        response.getWriter().write(data);
        response.getWriter().flush();
    }
}
