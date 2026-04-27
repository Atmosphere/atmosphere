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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.atmosphere.a2a.protocol.A2aMethod;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.protocol.AbstractProtocolHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Atmosphere handler for the A2A protocol — supports both the JSON-RPC binding
 * (POST with a JSON-RPC 2.0 envelope) and the v1.0.0 HTTP+JSON / REST binding
 * (colon-verb URLs like {@code POST /tasks/{id}:cancel}). REST requests are
 * translated into synthetic JSON-RPC envelopes and dispatched through
 * {@link A2aProtocolHandler#handleMessage(String)}; this keeps the dispatch
 * surface single-source-of-truth and means the two bindings agree by
 * construction (per Correctness Invariant #7 — Mode Parity).
 */
public final class A2aHandler extends AbstractProtocolHandler<A2aSession>
        implements LocalDispatchable {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String APPLICATION_JSON = "application/json";
    private static final String EVENT_STREAM = "text/event-stream";

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
        var body = sb.toString();

        restoreSession(resource);
        var session = ensureSession(resource);
        registerSession(session, response);

        var rest = restRoute(request.getRequestURI(), "POST", body);
        var jsonRpcBody = rest != null ? rest : body;

        if (jsonRpcBody.isEmpty()) {
            response.setStatus(400);
            response.setContentType(APPLICATION_JSON);
            response.getWriter().write(
                    "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Empty body\"}}");
            return;
        }

        var accept = request.getHeader("Accept");
        if (accept != null && accept.contains(EVENT_STREAM)
                && isStreamingRequest(jsonRpcBody)) {
            handleSseStreaming(resource, jsonRpcBody);
            return;
        }

        var jsonResponse = protocolHandler.handleMessage(jsonRpcBody);
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

        var rest = restRoute(path, "GET", "");
        if (rest != null) {
            var jsonResponse = protocolHandler.handleMessage(rest);
            if (jsonResponse == null) {
                response.setStatus(202);
                return;
            }
            response.setStatus(200);
            response.setContentType(APPLICATION_JSON);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(jsonResponse);
            response.getWriter().flush();
            return;
        }

        // SSE stream for task subscriptions
        restoreSession(resource);
        response.setContentType(EVENT_STREAM);
        response.setCharacterEncoding("UTF-8");
        resource.suspend();

        var session = getSessionFromRequest(resource);
        if (session != null) {
            replayPending(session, response);
        }
    }

    @Override
    protected void handleDelete(AtmosphereResource resource) throws IOException {
        var path = resource.getRequest().getRequestURI();
        var rest = restRoute(path, "DELETE", "");
        if (rest != null) {
            var jsonResponse = protocolHandler.handleMessage(rest);
            var response = resource.getResponse();
            if (jsonResponse == null) {
                response.setStatus(204);
                return;
            }
            response.setStatus(200);
            response.setContentType(APPLICATION_JSON);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(jsonResponse);
            response.getWriter().flush();
            return;
        }
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

    /**
     * Translate a v1.0.0 REST path into a synthetic JSON-RPC envelope, or
     * return {@code null} when the path does not match any REST route.
     * Path components after the last {@code "/a2a"}, {@code "/agent/<name>"},
     * or otherwise — any prefix is permitted; only the suffix is matched.
     */
    String restRoute(String path, String httpMethod, String body) {
        if (path == null) {
            return null;
        }
        var trimmed = path;
        var query = trimmed.indexOf('?');
        if (query >= 0) {
            trimmed = trimmed.substring(0, query);
        }

        if ("POST".equals(httpMethod) && trimmed.endsWith("/message:send")) {
            return wrap(A2aMethod.SEND_MESSAGE, parseJson(body));
        }
        if ("POST".equals(httpMethod) && trimmed.endsWith("/message:stream")) {
            return wrap(A2aMethod.SEND_STREAMING_MESSAGE, parseJson(body));
        }
        if ("GET".equals(httpMethod) && trimmed.endsWith("/extendedAgentCard")) {
            return wrap(A2aMethod.GET_EXTENDED_AGENT_CARD, Map.of());
        }
        if ("GET".equals(httpMethod) && (trimmed.endsWith("/tasks") || trimmed.contains("/tasks?"))) {
            return wrap(A2aMethod.LIST_TASKS, Map.of());
        }

        var taskMatch = matchTaskPath(trimmed);
        if (taskMatch == null) {
            return null;
        }
        var taskId = taskMatch.taskId;
        var rest = taskMatch.rest;

        if ("GET".equals(httpMethod) && rest.isEmpty()) {
            return wrap(A2aMethod.GET_TASK, Map.of("id", taskId));
        }
        if ("POST".equals(httpMethod) && rest.equals(":cancel")) {
            var params = new HashMap<String, Object>();
            params.put("id", taskId);
            params.putAll(parseJson(body));
            return wrap(A2aMethod.CANCEL_TASK, params);
        }
        if ("POST".equals(httpMethod) && rest.equals(":subscribe")) {
            return wrap(A2aMethod.SUBSCRIBE_TO_TASK, Map.of("id", taskId));
        }
        if (rest.equals("/pushNotificationConfigs")) {
            if ("POST".equals(httpMethod)) {
                var params = new HashMap<String, Object>();
                params.put("taskId", taskId);
                params.putAll(parseJson(body));
                return wrap(A2aMethod.CREATE_TASK_PUSH_NOTIFICATION_CONFIG, params);
            }
            if ("GET".equals(httpMethod)) {
                return wrap(A2aMethod.LIST_TASK_PUSH_NOTIFICATION_CONFIGS,
                        Map.of("taskId", taskId));
            }
        }
        if (rest.startsWith("/pushNotificationConfigs/")) {
            var configId = rest.substring("/pushNotificationConfigs/".length());
            if ("GET".equals(httpMethod)) {
                return wrap(A2aMethod.GET_TASK_PUSH_NOTIFICATION_CONFIG,
                        Map.of("taskId", taskId, "id", configId));
            }
            if ("DELETE".equals(httpMethod)) {
                return wrap(A2aMethod.DELETE_TASK_PUSH_NOTIFICATION_CONFIG,
                        Map.of("taskId", taskId, "id", configId));
            }
        }
        return null;
    }

    private record TaskPathMatch(String taskId, String rest) {
    }

    /**
     * Locate the {@code /tasks/{id}} segment and return the task id plus the
     * suffix beyond it. Returns {@code null} if the path doesn't include a
     * {@code /tasks/{id}} pattern.
     */
    private static TaskPathMatch matchTaskPath(String path) {
        var marker = "/tasks/";
        var idx = path.lastIndexOf(marker);
        if (idx < 0) {
            return null;
        }
        var afterTasks = path.substring(idx + marker.length());
        if (afterTasks.isEmpty()) {
            return null;
        }
        // Task id ends at next '/' or ':' or end-of-string.
        var end = afterTasks.length();
        for (int i = 0; i < afterTasks.length(); i++) {
            var c = afterTasks.charAt(i);
            if (c == '/' || c == ':') {
                end = i;
                break;
            }
        }
        var taskId = afterTasks.substring(0, end);
        var rest = afterTasks.substring(end);
        return new TaskPathMatch(taskId, rest);
    }

    private boolean isStreamingRequest(String body) {
        try {
            var node = mapper.readTree(body);
            var method = node.has("method") ? node.get("method").stringValue() : null;
            return A2aMethod.SEND_STREAMING_MESSAGE.equals(A2aMethod.canonicalize(method))
                    || A2aMethod.SUBSCRIBE_TO_TASK.equals(A2aMethod.canonicalize(method));
        } catch (Exception e) {
            logger.trace("Failed to parse body for streaming detection", e);
            return false;
        }
    }

    private void handleSseStreaming(AtmosphereResource resource, String body)
            throws IOException {
        var response = resource.getResponse();
        response.setStatus(200);
        response.setContentType(EVENT_STREAM);
        response.setCharacterEncoding("UTF-8");

        var rpcId = parseRequestId(body);
        var writer = response.getWriter();
        protocolHandler.handleStreamingMessage(body,
                token -> writeStreamChunk(writer, rpcId, token),
                () -> writer.write("data: [DONE]\n\n"));
    }

    /**
     * Emit one SSE chunk as a v1.0.0-compliant JSON-RPC envelope wrapping a
     * {@code StreamResponse} with an {@code artifactUpdate} oneof variant.
     */
    private void writeStreamChunk(java.io.PrintWriter writer, Object rpcId, String token) {
        try {
            var artifact = Map.of(
                    "artifactId", UUID.randomUUID().toString(),
                    "parts", java.util.List.of(Map.of("text", token))
            );
            var artifactUpdate = Map.of("artifact", artifact);
            var streamResponse = Map.of("artifactUpdate", artifactUpdate);
            var envelope = new LinkedHashMap<String, Object>();
            envelope.put("jsonrpc", "2.0");
            envelope.put("id", rpcId);
            envelope.put("result", streamResponse);
            writer.write("data: " + mapper.writeValueAsString(envelope) + "\n\n");
            writer.flush();
        } catch (JacksonException e) {
            logger.warn("Failed to write SSE token", e);
        }
    }

    private Object parseRequestId(String body) {
        try {
            var node = mapper.readTree(body);
            if (node.has("id")) {
                var id = node.get("id");
                if (id.isNumber()) {
                    return id.numberValue();
                }
                if (id.isString()) {
                    return id.stringValue();
                }
            }
        } catch (Exception e) {
            logger.trace("Failed to parse JSON-RPC id from streaming body", e);
        }
        return 1;
    }

    private A2aSession ensureSession(AtmosphereResource resource) {
        var session = getSessionFromRequest(resource);
        if (session == null) {
            session = new A2aSession();
            setSessionOnRequest(resource, session);
        }
        return session;
    }

    private String wrap(String method, Map<String, ?> params) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", UUID.randomUUID().toString());
        envelope.put("method", method);
        envelope.put("params", params);
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JacksonException e) {
            logger.warn("Failed to serialize REST→JSON-RPC envelope", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = mapper.readTree(body);
            return mapper.treeToValue(node, Map.class);
        } catch (JacksonException e) {
            logger.warn("Failed to parse REST body as JSON; treating as empty", e);
            return Map.of();
        }
    }
}
