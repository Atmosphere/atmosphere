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
package org.atmosphere.ai.crewai;

import org.atmosphere.ai.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Default {@link CrewAiSidecarClient} implementation: talks to the sidecar
 * over plain HTTP and parses SSE frames into typed
 * {@link SidecarEvent}s. No Python ever loads in the JVM.
 *
 * <p>Wire-level parsing: SSE frames are {@code event: <name>} +
 * {@code data: <json>} pairs separated by blank lines. Unknown event names
 * are logged at TRACE and skipped — never thrown — so a sidecar that
 * speaks a newer protocol stays forward-compatible (Correctness Invariant
 * #4 — Boundary Safety).</p>
 */
public final class HttpSseSidecarClient implements CrewAiSidecarClient {

    private static final Logger logger = LoggerFactory.getLogger(HttpSseSidecarClient.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final String EVENT_PREFIX = "event: ";
    private static final String DATA_PREFIX = "data: ";
    private static final String SESSION_HEADER = "X-Atmosphere-CrewAI-Session";

    private final URI baseUrl;
    private final HttpClient httpClient;
    private final Duration healthTimeout;
    private final Duration requestTimeout;

    /** Visible for tests so callers may inject a tuned {@link HttpClient}. */
    public HttpSseSidecarClient(CrewAiSidecarConfig config) {
        // HTTP/1.1 is mandatory: java.net.http defaults to HTTP/2 which
        // attempts an `Upgrade: h2c` against plain-HTTP sidecars; uvicorn
        // (the FastAPI host) does not implement h2c upgrade and the
        // resulting request lands with an empty body — FastAPI then
        // returns 422 `body missing`. Pinning HTTP_1_1 here makes the
        // wire shape deterministic against any uvicorn / hypercorn /
        // Werkzeug-style sidecar. Confirmed e2e (drift caught via
        // chrome-devtools roundtrip).
        this(config, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.healthTimeout())
                .build());
    }

    HttpSseSidecarClient(CrewAiSidecarConfig config, HttpClient httpClient) {
        Objects.requireNonNull(config, "config");
        this.baseUrl = stripTrailingSlash(config.baseUrl());
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.healthTimeout = config.healthTimeout();
        this.requestTimeout = config.requestTimeout();
    }

    @Override
    public boolean health() {
        var request = HttpRequest.newBuilder()
                .uri(baseUrl.resolve("/health"))
                .timeout(healthTimeout)
                .GET()
                .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.trace("CrewAI sidecar health probe failed against {}: {}",
                    baseUrl, e.toString());
            return false;
        }
    }

    @Override
    public SidecarSession startSession(StartRequest request) {
        Objects.requireNonNull(request, "request");
        String body;
        try {
            body = MAPPER.writeValueAsString(toJson(request));
        } catch (RuntimeException e) {
            throw new CrewAiSidecarException(
                    "Failed to serialise CrewAI sidecar StartRequest", e);
        }
        var httpRequest = HttpRequest.newBuilder()
                .uri(baseUrl.resolve("/v1/sessions"))
                .timeout(requestTimeout)
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new CrewAiSidecarException(
                    "Failed to reach CrewAI sidecar at " + baseUrl, e);
        }
        if (response.statusCode() / 100 != 2) {
            // Drain the body so the connection can be reused and we have
            // some forensic snippet for the error message.
            var snippet = readSnippet(response.body());
            throw new CrewAiSidecarException(
                    "CrewAI sidecar returned HTTP " + response.statusCode() + ": " + snippet);
        }
        var sessionId = response.headers().firstValue(SESSION_HEADER).orElse(null);
        return new HttpSseSession(sessionId, response.body());
    }

    @Override
    public void cancelSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        // URL-encoding the session id so a sidecar that hands back an opaque
        // identifier containing reserved characters does not break the
        // DELETE path (Correctness Invariant #4 — Boundary Safety).
        var encoded = java.net.URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder()
                .uri(baseUrl.resolve("/v1/sessions/" + encoded))
                .timeout(healthTimeout)
                .DELETE()
                .build();
        try {
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Cancel is best-effort: the sidecar will time the session out
            // on its own. Log at TRACE — never swallow silently — so
            // post-mortem tooling can see the cancel never landed.
            logger.trace("CrewAI sidecar cancel for {} failed: {}",
                    sessionId, e.toString());
        }
    }

    private static ObjectNode toJson(StartRequest request) {
        var root = MAPPER.createObjectNode();
        root.put("message", request.message() != null ? request.message() : "");
        if (request.model() != null && !request.model().isBlank()) {
            root.put("model", request.model());
        }
        var historyArray = root.putArray("history");
        for (var entry : request.history()) {
            var node = historyArray.addObject();
            node.put("role", entry.role() != null ? entry.role() : "user");
            node.put("content", entry.content() != null ? entry.content() : "");
        }
        if (!request.options().isEmpty()) {
            var optionsNode = root.putObject("options");
            for (var entry : request.options().entrySet()) {
                optionsNode.putPOJO(entry.getKey(), entry.getValue());
            }
        }
        // Optional fields only serialised when non-empty / non-null so
        // pre-tool-bridge sidecars don't have to learn the extra shape
        // (forward-compatibility, Correctness Invariant #4 — Boundary Safety).
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            root.put("system_prompt", request.systemPrompt());
        }
        if (!request.tools().isEmpty()) {
            var toolsArray = root.putArray("tools");
            for (var tool : request.tools()) {
                var node = toolsArray.addObject();
                node.put("name", tool.name());
                node.put("description", tool.description() != null ? tool.description() : "");
                var paramArray = node.putArray("parameters");
                for (var param : tool.parameters()) {
                    var pNode = paramArray.addObject();
                    pNode.put("name", param.name());
                    pNode.put("type", param.type() != null ? param.type() : "string");
                    pNode.put("description",
                            param.description() != null ? param.description() : "");
                    pNode.put("required", param.required());
                }
                node.put("return_type",
                        tool.returnType() != null && !tool.returnType().isBlank()
                                ? tool.returnType() : "string");
            }
            if (request.toolCallbackUrl() != null && !request.toolCallbackUrl().isBlank()) {
                root.put("tool_callback_url", request.toolCallbackUrl());
                // Handed over on this loopback hop rather than in the callback
                // URL: URLs surface in access logs and process listings, and
                // the token must not.
                root.put("tool_callback_token", request.toolCallbackToken());
            }
        }
        return root;
    }

    private static URI stripTrailingSlash(URI uri) {
        var s = uri.toString();
        if (s.endsWith("/")) {
            try {
                return new URI(s.substring(0, s.length() - 1));
            } catch (java.net.URISyntaxException e) {
                return uri;
            }
        }
        return uri;
    }

    private static String readSnippet(InputStream body) {
        if (body == null) {
            return "<no body>";
        }
        try (body) {
            var bytes = body.readAllBytes();
            var text = new String(bytes, StandardCharsets.UTF_8);
            return text.length() > 500 ? text.substring(0, 500) + "..." : text;
        } catch (IOException e) {
            return "<unreadable: " + e.getClass().getSimpleName() + ">";
        }
    }

    /** Per-session SSE reader. */
    private static final class HttpSseSession implements SidecarSession {

        private final java.util.concurrent.atomic.AtomicReference<String> sessionId;
        private final InputStream body;
        private final BufferedReader reader;
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();

        HttpSseSession(String sessionId, InputStream body) {
            this.sessionId = new java.util.concurrent.atomic.AtomicReference<>(sessionId);
            this.body = body;
            this.reader = new BufferedReader(
                    new InputStreamReader(body, StandardCharsets.UTF_8));
        }

        @Override
        public String sessionId() {
            return sessionId.get();
        }

        @Override
        public Iterator<SidecarEvent> events() {
            return new EventIterator();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                // Closing the BufferedReader closes the underlying
                // InputStream which releases the HTTP connection back to
                // the pool — Correctness Invariant #1 (Ownership).
                try {
                    reader.close();
                } catch (IOException e) {
                    logger.trace("Closing CrewAI sidecar SSE reader threw: {}", e.toString());
                }
                try {
                    body.close();
                } catch (IOException e) {
                    logger.trace("Closing CrewAI sidecar SSE body threw: {}", e.toString());
                }
            }
        }

        private final class EventIterator implements Iterator<SidecarEvent> {
            private SidecarEvent next;
            private boolean exhausted;

            @Override
            public boolean hasNext() {
                if (exhausted) {
                    return false;
                }
                if (next != null) {
                    return true;
                }
                next = readNext();
                if (next == null) {
                    exhausted = true;
                    return false;
                }
                return true;
            }

            @Override
            public SidecarEvent next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                var current = next;
                next = null;
                return current;
            }

            private SidecarEvent readNext() {
                String eventName = null;
                String data = null;
                String line;
                try {
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (eventName != null || data != null) {
                                return dispatch(eventName, data);
                            }
                            continue;
                        }
                        if (line.startsWith(EVENT_PREFIX)) {
                            eventName = line.substring(EVENT_PREFIX.length()).trim();
                        } else if (line.startsWith(DATA_PREFIX)) {
                            // SSE allows multi-line data: payloads (each line
                            // prefixed with `data: `). Concatenate them with
                            // a newline so the JSON parser sees the full frame.
                            var chunk = line.substring(DATA_PREFIX.length());
                            data = data == null ? chunk : data + "\n" + chunk;
                        } else if (line.startsWith(":")) {
                            // SSE comment — ignored per the spec.
                            continue;
                        } else {
                            logger.trace("Skipping non-SSE line from CrewAI sidecar: {}", line);
                        }
                    }
                } catch (IOException e) {
                    // Transport dropped — translate to an Error frame so
                    // the runtime always reaches a terminal state
                    // (Correctness Invariant #2 — Terminal Path Completeness).
                    return new SidecarEvent.Error(
                            "CrewAI sidecar stream interrupted: " + e.getMessage());
                }
                // EOF — surface any half-built frame, then signal done.
                if (eventName != null || data != null) {
                    return dispatch(eventName, data);
                }
                return null;
            }

            private SidecarEvent dispatch(String eventName, String dataJson) {
                var name = eventName != null ? eventName : "message";
                try {
                    return switch (name) {
                        case "token" -> tokenEvent(dataJson);
                        case "usage" -> usageEvent(dataJson);
                        case "done" -> new SidecarEvent.Done();
                        case "error" -> errorEvent(dataJson);
                        case "session" -> {
                            updateSessionId(dataJson);
                            yield readNext();
                        }
                        default -> {
                            logger.trace("Unhandled CrewAI sidecar event '{}'", name);
                            yield readNext();
                        }
                    };
                } catch (RuntimeException e) {
                    return new SidecarEvent.Error(
                            "Failed to parse CrewAI sidecar event '" + name + "': "
                                    + e.getMessage());
                }
            }

            private SidecarEvent tokenEvent(String dataJson) {
                if (dataJson == null || dataJson.isBlank()) {
                    return new SidecarEvent.Token("");
                }
                JsonNode node = MAPPER.readTree(dataJson);
                var text = node.path("text").asString("");
                return new SidecarEvent.Token(text);
            }

            private SidecarEvent usageEvent(String dataJson) {
                if (dataJson == null || dataJson.isBlank()) {
                    return new SidecarEvent.Usage(TokenUsage.of(0, 0));
                }
                JsonNode node = MAPPER.readTree(dataJson);
                long input = node.path("input").asLong(0L);
                long output = node.path("output").asLong(0L);
                long total = node.path("total").asLong(input + output);
                var modelRaw = node.path("model").asString("");
                var model = modelRaw.isEmpty() ? null : modelRaw;
                return new SidecarEvent.Usage(new TokenUsage(input, output, 0L, total, model));
            }

            private SidecarEvent errorEvent(String dataJson) {
                if (dataJson == null || dataJson.isBlank()) {
                    return new SidecarEvent.Error("CrewAI sidecar reported an unspecified error");
                }
                JsonNode node = MAPPER.readTree(dataJson);
                var message = node.path("message").asString("");
                if (message.isEmpty()) {
                    message = dataJson;
                }
                return new SidecarEvent.Error(message);
            }

            private void updateSessionId(String dataJson) {
                if (dataJson == null || dataJson.isBlank()) {
                    return;
                }
                JsonNode node = MAPPER.readTree(dataJson);
                var id = node.path("sessionId").asString("");
                if (!id.isBlank()) {
                    sessionId.set(id);
                }
            }
        }
    }
}
