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
package org.atmosphere.a2a.runtime;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.atmosphere.a2a.protocol.A2aMethod;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.protocol.AbstractProtocolHandler;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Atmosphere handler for the A2A protocol. Processes HTTP POST (JSON-RPC), GET (agent
 * card and SSE subscriptions), and DELETE (session teardown) requests, delegating
 * JSON-RPC dispatch to {@link A2aProtocolHandler}. Also implements
 * {@link LocalDispatchable} for in-process transports.
 */
public final class A2aHandler extends AbstractProtocolHandler<A2aSession>
        implements LocalDispatchable {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String APPLICATION_JSON = "application/json";

    private final A2aProtocolHandler protocolHandler;

    public A2aHandler(A2aProtocolHandler protocolHandler) {
        this(protocolHandler, A2aSession.DEFAULT_TTL_MS);
    }

    public A2aHandler(A2aProtocolHandler protocolHandler, long sessionTtlMs) {
        super(sessionTtlMs, A2aSession.SESSION_ID_HEADER,
                A2aSession.ATTRIBUTE_KEY, "a2a-session-cleaner");
        this.protocolHandler = protocolHandler;
    }

    @Override
    public String dispatchLocal(String jsonRpcRequest) {
        return protocolHandler.handleMessage(jsonRpcRequest);
    }

    @Override
    public void dispatchLocalStreaming(String jsonRpcRequest, Consumer<String> onToken,
                                       Runnable onComplete) {
        protocolHandler.handleStreamingMessage(jsonRpcRequest, onToken, onComplete);
    }

    @Override
    protected void handlePost(AtmosphereResource resource) throws IOException {
        var request = resource.getRequest();
        var response = resource.getResponse();

        var reader = request.getReader();
        var sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }

        if (sb.isEmpty()) {
            response.setStatus(400);
            response.setContentType(APPLICATION_JSON);
            response.getWriter().write(
                    "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Empty body\"}}");
            return;
        }

        restoreSession(resource);

        // Detect message/stream and write SSE events if client accepts them
        var accept = request.getHeader("Accept");
        if (accept != null && accept.contains("text/event-stream")
                && isStreamingRequest(sb.toString())) {
            handleSseStreaming(resource, sb.toString());
            return;
        }

        var jsonResponse = protocolHandler.handleMessage(sb.toString());

        var session = getSessionFromRequest(resource);
        if (session == null) {
            session = new A2aSession();
            setSessionOnRequest(resource, session);
        }
        registerSession(session, response);

        if (jsonResponse == null) {
            response.setStatus(202);
            return;
        }

        writeResponse(resource, jsonResponse);
    }

    @Override
    protected void handleGet(AtmosphereResource resource) throws IOException {
        var request = resource.getRequest();
        var response = resource.getResponse();

        // Check if this is an agent card request
        var path = request.getRequestURI();
        if (path != null && (path.endsWith("/agent.json")
                || path.contains("/.well-known/agent.json"))) {
            response.setStatus(200);
            response.setContentType(APPLICATION_JSON);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(mapper.writeValueAsString(protocolHandler.agentCard()));
            response.getWriter().flush();
            return;
        }

        // SSE stream for task subscriptions
        restoreSession(resource);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        resource.suspend();

        var session = getSessionFromRequest(resource);
        if (session != null) {
            replayPending(session, response);
        }
    }

    @Override
    protected void handleDelete(AtmosphereResource resource) throws IOException {
        var removed = removeSessionByHeader(resource);
        if (removed != null) {
            logger.info("A2A session terminated: {}", removed.sessionId());
        }
        resource.getResponse().setStatus(204);
    }

    @Override
    protected void handleIncomingMessage(AtmosphereResource resource, String message)
            throws IOException {
        var jsonResponse = protocolHandler.handleMessage(message);
        if (jsonResponse != null) {
            write(resource.getResponse(), jsonResponse);
        }
    }

    private boolean isStreamingRequest(String body) {
        try {
            var node = mapper.readTree(body);
            var method = node.has("method") ? node.get("method").stringValue() : null;
            return A2aMethod.SEND_STREAMING_MESSAGE.equals(method);
        } catch (Exception e) {
            logger.trace("Failed to parse body for streaming detection", e);
            return false;
        }
    }

    private void handleSseStreaming(AtmosphereResource resource, String body)
            throws IOException {
        var response = resource.getResponse();
        response.setStatus(200);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");

        var writer = response.getWriter();
        protocolHandler.handleStreamingMessage(body,
                token -> {
                    try {
                        writer.write("data: " + mapper.writeValueAsString(
                                java.util.Map.of("artifact",
                                        java.util.Map.of("parts",
                                                java.util.List.of(java.util.Map.of("text", token)))))
                                + "\n\n");
                        writer.flush();
                    } catch (JacksonException e) {
                        logger.warn("Failed to write SSE token", e);
                    }
                },
                () -> {
                    writer.write("data: [DONE]\n\n");
                    writer.flush();
                });
    }
}
