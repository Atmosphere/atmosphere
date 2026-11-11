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
import org.atmosphere.mcp.protocol.Mcp2026;
import org.atmosphere.mcp.protocol.McpMethod;
import org.atmosphere.protocol.SseEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * {@link AtmosphereHandler} that bridges Atmosphere's transport layer with the
 * MCP JSON-RPC protocol. Supports three transports:
 * <ul>
 *   <li><b>Streamable HTTP</b> (MCP spec 2025-11-25) — POST for requests, GET for SSE notifications, DELETE to end session</li>
 *   <li><b>WebSocket</b> — full-duplex JSON-RPC via WebSocket frames</li>
 *   <li><b>SSE fallback</b> — Atmosphere's automatic WebSocket→SSE downgrade</li>
 * </ul>
 * Sessions survive transient disconnections: pending notifications are buffered
 * and replayed when a client reconnects with the same {@code Mcp-Session-Id}.
 * Idle sessions are evicted after a configurable TTL (default 30 min).
 * <p>
 * Registered at the MCP path resolved from {@code @Agent}.
 */
public final class McpHandler implements AtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(McpHandler.class);
    private static final String APPLICATION_JSON = "application/json";
    private static final String TEXT_EVENT_STREAM = "text/event-stream";
    /** Request attribute holding the sink a GET stream was attached to its session with. */
    private static final String STREAM_SINK_ATTRIBUTE = "org.atmosphere.mcp.streamSink";
    private static final int DEFAULT_MAX_SESSIONS = 10_000;
    private static final int MAX_BODY_SIZE = 10 * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Header name for MCP protocol version negotiation, required on all
     * post-handshake requests since spec 2025-06-18.
     */
    public static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

    private final McpProtocolHandler protocolHandler;
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
    /**
     * The session each open WebSocket is the stream of, by resource uuid.
     * Bounded by the open connections: an entry is removed when its socket
     * closes.
     */
    private final Map<String, WebSocketStream> webSocketStreams = new ConcurrentHashMap<>();
    /** Tool calls one WebSocket may have running at once; past this a call is refused. */
    static final int MAX_WEBSOCKET_TOOL_CALLS = 16;
    private final long sessionTtlMs;
    private final int maxSessions;
    private final ScheduledExecutorService cleaner;

    public McpHandler(McpProtocolHandler protocolHandler) {
        this(protocolHandler, McpSession.DEFAULT_TTL_MS, DEFAULT_MAX_SESSIONS);
    }

    public McpHandler(McpProtocolHandler protocolHandler, long sessionTtlMs) {
        this(protocolHandler, sessionTtlMs, DEFAULT_MAX_SESSIONS);
    }

    public McpHandler(McpProtocolHandler protocolHandler, long sessionTtlMs, int maxSessions) {
        this.protocolHandler = protocolHandler;
        this.sessionTtlMs = sessionTtlMs;
        this.maxSessions = maxSessions;
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = Thread.ofPlatform().daemon().name("mcp-session-cleaner").unstarted(r);
            return t;
        });
        this.cleaner.scheduleAtFixedRate(this::evictExpiredSessions,
                sessionTtlMs, Math.max(sessionTtlMs / 2, 60_000L), TimeUnit.MILLISECONDS);
    }

    /**
     * Return the underlying {@link McpProtocolHandler} backing this handler.
     */
    public McpProtocolHandler protocolHandler() {
        return protocolHandler;
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        var request = resource.getRequest();
        var response = resource.getResponse();

        // MCP 2025-11-25 clarification: invalid Origin → HTTP 403, not 400.
        // We treat "no Origin" as benign (non-browser clients); a present
        // but non-matching Origin against a configured allowlist is the
        // case the spec is targeting. Allowlist lives on the request
        // attribute "org.atmosphere.mcp.allowedOrigins" (List<String>);
        // when absent, no enforcement (back-compat with existing deployments).
        if (!isOriginAllowed(request)) {
            response.setStatus(403);
            response.setContentType(APPLICATION_JSON);
            response.getWriter().write(
                    "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Forbidden Origin\"}}");
            return;
        }

        // OAuth resource-server enforcement (MCP authorization spec, RFC 9728).
        // McpAuthorization.authenticate() accepts a framework-set principal
        // (Spring Security oauth2ResourceServer / AuthInterceptor) OR validates
        // the Authorization: Bearer token against a configured TokenValidator.
        // Default-deny (Invariant #6): unauthenticated ⇒ 401 + WWW-Authenticate
        // so the client can discover the authorization server.
        var auth = protocolHandler.authorization();
        if (auth.enabled() && !auth.authenticate(request)) {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", auth.wwwAuthenticate());
            response.setContentType(APPLICATION_JSON);
            response.getWriter().write(
                    "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"Unauthorized\"}}");
            return;
        }

        var method = request.getMethod();

        switch (method.toUpperCase()) {
            case "POST" -> handlePost(resource);
            case "GET" -> handleGet(resource);
            case "DELETE" -> handleDelete(resource);
            default -> {
                response.setStatus(405);
                response.getWriter().write("{\"error\":\"Method not allowed\"}");
            }
        }
    }

    /**
     * Validate the {@code Origin} header against an optional allowlist.
     * Returns {@code true} when there is no allowlist configured, when no
     * {@code Origin} header is present (non-browser clients), or when the
     * presented Origin is in the allowlist.
     */
    @SuppressWarnings("unchecked")
    private boolean isOriginAllowed(org.atmosphere.cpr.AtmosphereRequest request) {
        var origin = request.getHeader("Origin");
        if (origin == null || origin.isEmpty()) {
            return true;
        }
        var allowed = (List<String>) request.getAttribute("org.atmosphere.mcp.allowedOrigins");
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        return allowed.contains(origin);
    }

    /**
     * After the initialize handshake, MCP 2025-06-18 requires the client to
     * echo the negotiated protocol revision in {@code MCP-Protocol-Version}
     * on every subsequent HTTP request. A missing or mismatched header on a
     * known session yields HTTP 400 so misconfigured clients fail loud.
     *
     * @return {@code true} if the request may proceed, {@code false} if a
     *         response has already been written (caller should return).
     */
    private boolean enforceProtocolVersionHeader(AtmosphereResource resource, McpSession session) throws IOException {
        if (session == null || !session.isInitialized()) {
            // Pre-handshake (initialize itself) — no enforcement
            return true;
        }
        var negotiated = session.protocolVersion();
        if (negotiated == null) {
            return true;
        }
        var presented = resource.getRequest().getHeader(PROTOCOL_VERSION_HEADER);
        if (presented == null) {
            // Tolerant for back-compat: spec says clients SHOULD send it; we
            // don't fail-close here to avoid breaking existing 2025-03-26 clients
            // that don't know about the header. Once this is widely adopted we
            // can promote to fail-closed.
            return true;
        }
        if (!presented.equals(negotiated)) {
            var response = resource.getResponse();
            response.setStatus(400);
            response.setContentType(APPLICATION_JSON);
            // JSON-encode via Jackson so a malicious header value (e.g. one
            // containing `"` or backslashes) cannot break out of the message
            // string and inject extra JSON fields. CodeQL flags raw string
            // concatenation of user-controlled headers (java/xss).
            response.getWriter().write(MAPPER.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "error", Map.of(
                            "code", -32600,
                            "message", "MCP-Protocol-Version mismatch: session="
                                    + negotiated + ", header=" + presented))));
            return false;
        }
        return true;
    }

    /**
     * POST — Streamable HTTP: client sends JSON-RPC request, server responds with JSON or SSE.
     */
    private void handlePost(AtmosphereResource resource) throws IOException {
        var request = resource.getRequest();
        var response = resource.getResponse();

        // Reject oversized bodies early via Content-Length
        var contentLengthStr = request.getHeader("Content-Length");
        if (contentLengthStr != null) {
            try {
                long contentLength = Long.parseLong(contentLengthStr);
                if (contentLength > MAX_BODY_SIZE) {
                    response.setStatus(413);
                    response.setContentType(APPLICATION_JSON);
                    response.getWriter().write(
                            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Request body too large\"}}");
                    return;
                }
            } catch (NumberFormatException ignored) {
                // Malformed Content-Length; bounded read below will protect
            }
        }

        // Read request body with size limit
        var reader = request.getReader();
        var sb = new StringBuilder();
        var buf = new char[4096];
        int totalRead = 0;
        int n;
        while ((n = reader.read(buf)) != -1) {
            totalRead += n;
            if (totalRead > MAX_BODY_SIZE) {
                response.setStatus(413);
                response.setContentType(APPLICATION_JSON);
                response.getWriter().write(
                        "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Request body too large\"}}");
                return;
            }
            sb.append(buf, 0, n);
        }

        if (sb.isEmpty()) {
            response.setStatus(400);
            response.setContentType(APPLICATION_JSON);
            response.getWriter().write("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Empty body\"}}");
            return;
        }

        // SEP-2243 (MCP 2026-07-28): when the stateless client mirrors the
        // method/target into the Mcp-Method/Mcp-Name routing headers, they MUST
        // agree with the body so a load balancer can dispatch on headers alone.
        var headerMismatch = validateOperabilityHeaders(sb.toString(),
                request.getHeader(Mcp2026.HEADER_MCP_METHOD),
                request.getHeader(Mcp2026.HEADER_MCP_NAME));
        if (headerMismatch != null) {
            response.setStatus(400);
            response.setContentType(APPLICATION_JSON);
            response.getWriter().write(MAPPER.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "error", Map.of("code", -32600, "message", headerMismatch))));
            return;
        }

        // Restore session from Mcp-Session-Id header if present
        var sessionId = request.getHeader(McpSession.SESSION_ID_HEADER);
        McpSession existingSession = null;
        if (sessionId != null) {
            existingSession = sessions.get(sessionId);
            if (existingSession != null) {
                request.setAttribute(McpSession.ATTRIBUTE_KEY, existingSession);
            }
        }

        // MCP 2025-06-18: protocol version header enforcement on post-handshake POSTs
        if (!enforceProtocolVersionHeader(resource, existingSession)) {
            return;
        }

        if (callToolOffTheSocketThread(resource, sb.toString())) {
            return;
        }

        var jsonResponse = protocolHandler.handleMessage(resource, sb.toString());

        // After handleMessage, check if a new session was created (initialize)
        var session = (McpSession) request.getAttribute(McpSession.ATTRIBUTE_KEY);
        if (session != null) {
            try {
                sessions.compute(session.sessionId(), (id, existing) -> {
                    if (existing != null) return existing;
                    if (sessions.size() >= maxSessions) {
                        throw new IllegalStateException("Too many sessions");
                    }
                    return session;
                });
            } catch (IllegalStateException e) {
                response.setStatus(503);
                response.setContentType(APPLICATION_JSON);
                response.getWriter().write(
                        "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,\"message\":\"Too many sessions\"}}");
                return;
            }
            session.touch();
            response.setHeader(McpSession.SESSION_ID_HEADER, session.sessionId());
        }

        if (jsonResponse == null) {
            // Notification (no id) — respond with 202 Accepted
            response.setStatus(202);
            attachWebSocketStream(resource, session);
            return;
        }

        var accept = request.getHeader("Accept");
        if (accept != null && accept.contains(TEXT_EVENT_STREAM)) {
            // SSE format, written past the SSE interceptor so an Accept of
            // exactly text/event-stream does not frame the event twice
            SseEvents.begin(response);
            SseEvents.write(response, SseEvents.message(jsonResponse));
        } else {
            // Plain JSON
            response.setStatus(200);
            response.setContentType(APPLICATION_JSON);
            McpSession.StreamSink writer = message -> {
                response.getWriter().write(message);
                response.getWriter().flush();
            };
            if (session != null && webSocketStreams.containsKey(resource.uuid())) {
                // One socket carries this answer and the requests tool threads
                // write on the session's stream: do not let them interleave.
                session.writeSerialized(writer, jsonResponse);
            } else {
                writer.send(jsonResponse);
            }
        }
        attachWebSocketStream(resource, session);
    }

    /**
     * SEP-2243 operability-header consistency check for the stateless
     * Streamable-HTTP transport. When {@code Mcp-Method} is present the server
     * MUST reject any disagreement with the JSON-RPC body (and likewise
     * {@code Mcp-Name} against the addressed tool/resource/prompt), so a router
     * can trust the headers without reading the body. Returns an error message
     * to send back, or {@code null} when the headers are absent or consistent.
     * A body that fails to parse here returns {@code null} so the protocol
     * handler can surface the parse error through the normal path.
     */
    private String validateOperabilityHeaders(String body, String methodHeader, String nameHeader) {
        if (methodHeader == null) {
            return null;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (RuntimeException e) {
            logger.trace("Mcp-Method header present but body did not parse; "
                    + "deferring to protocol handler for the parse error", e);
            return null;
        }
        var bodyMethod = node.has("method") && node.get("method").isString()
                ? node.get("method").stringValue() : null;
        if (bodyMethod == null || !bodyMethod.equals(methodHeader)) {
            return "Mcp-Method header '" + methodHeader + "' does not match body method '"
                    + bodyMethod + "'";
        }
        if (nameHeader != null) {
            var bodyName = operationName(bodyMethod, node.get("params"));
            if (bodyName != null && !bodyName.equals(nameHeader)) {
                return "Mcp-Name header '" + nameHeader + "' does not match body target '"
                        + bodyName + "'";
            }
        }
        return null;
    }

    /**
     * The tool/resource/prompt name a request targets, used for the
     * {@code Mcp-Name} consistency check. Returns {@code null} for methods that
     * do not address a named entity.
     */
    private static String operationName(String method, JsonNode params) {
        if (params == null) {
            return null;
        }
        return switch (method) {
            case McpMethod.TOOLS_CALL, McpMethod.PROMPTS_GET ->
                    params.has("name") && params.get("name").isString()
                            ? params.get("name").stringValue() : null;
            case McpMethod.RESOURCES_READ ->
                    params.has("uri") && params.get("uri").isString()
                            ? params.get("uri").stringValue() : null;
            default -> null;
        };
    }

    /**
     * GET — Opens SSE stream for server-initiated notifications.
     */
    private void handleGet(AtmosphereResource resource) throws IOException {
        var request = resource.getRequest();
        var response = resource.getResponse();

        // Restore session from header
        var sessionId = request.getHeader(McpSession.SESSION_ID_HEADER);
        McpSession session = null;
        if (sessionId != null) {
            session = sessions.get(sessionId);
            if (session != null) {
                request.setAttribute(McpSession.ATTRIBUTE_KEY, session);
                session.touch();
                response.setHeader(McpSession.SESSION_ID_HEADER, session.sessionId());
            }
        }
        if (!enforceProtocolVersionHeader(resource, session)) {
            return;
        }

        // SSE notification stream or WebSocket upgrade
        response.setContentType(TEXT_EVENT_STREAM);
        response.setCharacterEncoding("UTF-8");
        resource.suspend();

        // This stream becomes the session's: messages queued while the client
        // was disconnected are replayed on it, and server-initiated requests
        // issued from now on are written to it as they are issued.
        if (session != null) {
            // A WebSocket upgrade that names its session carries frames, not SSE events.
            McpSession.StreamSink sink = isWebSocket(resource)
                    ? message -> SseEvents.deliver(resource, message)
                    : message -> SseEvents.write(response, SseEvents.message(message));
            request.setAttribute(STREAM_SINK_ATTRIBUTE, sink);
            session.attachStream(sink);
            logger.debug("GET stream attached to MCP session {}", sessionId);
        }
    }

    /**
     * DELETE — Terminates MCP session.
     */
    private void handleDelete(AtmosphereResource resource) throws IOException {
        var request = resource.getRequest();
        var response = resource.getResponse();
        var sessionId = request.getHeader(McpSession.SESSION_ID_HEADER);

        McpSession session = null;
        if (sessionId != null) {
            session = sessions.get(sessionId);
        }
        if (!enforceProtocolVersionHeader(resource, session)) {
            return;
        }

        if (sessionId != null) {
            var removed = sessions.remove(sessionId);
            if (removed != null) {
                // Fail any in-flight server-initiated request so callers
                // awaiting an elicitation response unblock (Invariant #2).
                var cancelled = removed.close();
                logger.info("MCP session terminated: {} ({} pending server request(s) cancelled)",
                        sessionId, cancelled);
            }
        }

        response.setStatus(204);
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        var resource = event.getResource();
        if (event.isCancelled() || event.isClosedByClient() || event.isClosedByApplication()) {
            logger.debug("MCP connection closed: {}", resource.uuid());
            var stream = sessionStream(resource);
            if (stream != null) {
                stream.session().detachStream(stream.sink());
            }
            detachWebSocketStream(resource);
            return;
        }

        // SseEvents.deliver frames each write once on an SSE stream and leaves
        // it unchanged on a WebSocket.
        var message = event.getMessage();
        if (message instanceof String text) {
            // Incoming WebSocket message
            if (!callToolOffTheSocketThread(resource, text)) {
                respond(resource, protocolHandler.handleMessage(resource, text));
            }
        } else if (message instanceof List<?> list) {
            // Broadcast — write each message
            // A session's GET stream is also written by server-initiated
            // requests from tool threads: share the session's write lock.
            var stream = sessionStream(resource);
            for (var item : list) {
                if (item instanceof String text) {
                    if (stream != null) {
                        stream.session().writeSerialized(m -> SseEvents.deliver(resource, m), text);
                    } else {
                        SseEvents.deliver(resource, text);
                    }
                }
            }
        }
    }

    private record SessionStream(McpSession session, McpSession.StreamSink sink) {
    }

    /** A WebSocket that is its session's stream, with the tool calls it has running. */
    private record WebSocketStream(McpSession session, McpSession.StreamSink sink, Semaphore toolCalls) {
    }

    /** The session and sink a GET stream was attached with, or {@code null} for any other resource. */
    private static SessionStream sessionStream(AtmosphereResource resource) {
        var request = resource.getRequest();
        if (request == null
                || !(request.getAttribute(McpSession.ATTRIBUTE_KEY) instanceof McpSession session)
                || !(request.getAttribute(STREAM_SINK_ATTRIBUTE) instanceof McpSession.StreamSink sink)) {
            return null;
        }
        return new SessionStream(session, sink);
    }

    /**
     * Called directly for WebSocket messages (bypasses onStateChange for request-response).
     */
    public void processMessage(AtmosphereResource resource, String message) throws IOException {
        if (!callToolOffTheSocketThread(resource, message)) {
            respond(resource, protocolHandler.handleMessage(resource, message));
        }
    }

    /**
     * Run a {@code tools/call} that arrived on a WebSocket on its own virtual
     * thread, and write its answer on the socket when it returns. The
     * container hands a connection's frames to the handler one at a time, so
     * a tool that waits for the client (an elicitation, a sampling request, a
     * roots listing) on the frame's thread would also hold back the frame
     * carrying the client's answer, and wait out its timeout. Streamable HTTP
     * needs none of this: every POST has its own thread.
     *
     * <p>Only once the socket is its session's stream, and at most
     * {@link #MAX_WEBSOCKET_TOOL_CALLS} at a time per socket; a call past that
     * is refused with a JSON-RPC error (Invariant #3). Answers may therefore
     * arrive out of request order, which JSON-RPC allows: each carries its
     * request's id.</p>
     *
     * @return whether the message was taken; {@code false} leaves it to the caller
     */
    private boolean callToolOffTheSocketThread(AtmosphereResource resource, String message)
            throws IOException {
        var stream = isWebSocket(resource) ? webSocketStreams.get(resource.uuid()) : null;
        if (stream == null) {
            return false;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(message);
        } catch (RuntimeException e) {
            // Malformed: the protocol handler answers it with PARSE_ERROR.
            return false;
        }
        if (!node.isObject() || !McpMethod.TOOLS_CALL.equals(node.path("method").asString(null))
                || !node.hasNonNull("id")) {
            return false;
        }
        var session = stream.session();
        if (!stream.toolCalls().tryAcquire()) {
            session.writeSerialized(stream.sink(), MAPPER.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", MAPPER.treeToValue(node.get("id"), Object.class),
                    "error", Map.of("code", -32000, "message",
                            "Too many tool calls in flight on this connection (max "
                                    + MAX_WEBSOCKET_TOOL_CALLS + ")"))));
            return true;
        }
        try {
            Thread.ofVirtual().name("mcp-ws-tool-" + session.sessionId()).start(() -> {
                try {
                    var answer = protocolHandler.handleMessage(resource, message);
                    if (answer != null) {
                        session.sendOrQueue(answer);
                    }
                } catch (RuntimeException e) {
                    logger.warn("MCP tool call on WebSocket {} failed: {}", resource.uuid(), e.toString());
                    logger.debug("MCP tool call failure", e);
                } finally {
                    stream.toolCalls().release();
                }
            });
        } catch (RuntimeException e) {
            stream.toolCalls().release();
            throw e;
        }
        return true;
    }

    /**
     * Write the answer to a message {@link #onStateChange} or
     * {@link #processMessage} received on a WebSocket, under the session's
     * stream lock once the socket is the session's stream, then attach it.
     */
    private void respond(AtmosphereResource resource, String response) throws IOException {
        var session = resource.getRequest() != null
                && resource.getRequest().getAttribute(McpSession.ATTRIBUTE_KEY) instanceof McpSession s
                ? s : null;
        if (response != null) {
            McpSession.StreamSink writer = message -> SseEvents.deliver(resource, message);
            if (session != null && webSocketStreams.containsKey(resource.uuid())) {
                session.writeSerialized(writer, response);
            } else {
                writer.send(response);
            }
        }
        attachWebSocketStream(resource, session);
    }

    /**
     * Make a WebSocket the stream of the session it carries, once. Only a
     * Streamable HTTP GET used to attach a stream, so a server-initiated
     * request ({@code elicitation/create}, {@code sampling/createMessage},
     * {@code roots/list}) issued for a WebSocket client stayed queued and its
     * tool timed out. The connection is full duplex, so it carries the
     * server's requests as well as its answers; messages queued for the
     * session before this are written now, in order. Called after the answer
     * to the message that created the session is written, so
     * {@code initialize}'s answer comes first.
     */
    private void attachWebSocketStream(AtmosphereResource resource, McpSession session) {
        if (session == null || session.isClosed() || !isWebSocket(resource)
                || resource.getRequest().getAttribute(STREAM_SINK_ATTRIBUTE) != null) {
            // A WebSocket upgrade that named its session is attached by handleGet.
            return;
        }
        var attached = new boolean[1];
        webSocketStreams.computeIfAbsent(resource.uuid(), uuid -> {
            attached[0] = true;
            return new WebSocketStream(session, message -> SseEvents.deliver(resource, message),
                    new Semaphore(MAX_WEBSOCKET_TOOL_CALLS));
        });
        if (attached[0]) {
            session.attachStream(webSocketStreams.get(resource.uuid()).sink());
            logger.debug("WebSocket {} attached as the stream of MCP session {}",
                    resource.uuid(), session.sessionId());
        }
    }

    /**
     * End the session a closing WebSocket created. No later connection can
     * name it (a WebSocket client sends no {@code Mcp-Session-Id}), so it is
     * removed and closed now: its pending server requests fail instead of
     * waiting out each tool's timeout (Invariant #2), and it gives back its
     * slot under {@code maxSessions} instead of holding it until expiry.
     */
    private void detachWebSocketStream(AtmosphereResource resource) {
        var stream = webSocketStreams.remove(resource.uuid());
        if (stream == null) {
            return;
        }
        var session = stream.session();
        sessions.remove(session.sessionId(), session);
        var cancelled = session.close();
        logger.debug("MCP session {} closed with its WebSocket {} ({} pending server request(s) cancelled)",
                session.sessionId(), resource.uuid(), cancelled);
    }

    private static boolean isWebSocket(AtmosphereResource resource) {
        return resource.transport() == AtmosphereResource.TRANSPORT.WEBSOCKET;
    }

    /**
     * Returns the session store (visible for testing).
     */
    public Map<String, McpSession> sessions() {
        return sessions;
    }

    @Override
    public void destroy() {
        cleaner.shutdownNow();
        // Drain rather than clear() so every session's in-flight
        // server-initiated requests are failed instead of dropped
        // un-completed (Invariant #2).
        for (var sessionId : Set.copyOf(sessions.keySet())) {
            var session = sessions.remove(sessionId);
            if (session != null) {
                session.close();
            }
        }
        webSocketStreams.clear();
        logger.debug("McpHandler destroyed");
    }

    /**
     * Remove every session idle past the TTL, failing its in-flight
     * server-initiated requests. Scheduled on the cleaner thread;
     * package-private so tests can drive a pass deterministically instead of
     * racing the fixed-rate schedule.
     *
     * @return the number of sessions evicted
     */
    int evictExpiredSessions() {
        var evicted = 0;
        var it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getValue().isExpired(sessionTtlMs)) {
                it.remove();
                evicted++;
                var cancelled = entry.getValue().close();
                logger.info("Evicted expired MCP session: {} ({} pending server request(s) cancelled)",
                        entry.getKey(), cancelled);
            }
        }
        return evicted;
    }
}
