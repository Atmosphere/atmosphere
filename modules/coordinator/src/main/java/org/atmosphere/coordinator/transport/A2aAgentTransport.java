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
package org.atmosphere.coordinator.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.atmosphere.coordinator.fleet.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Transport for remote agents via A2A JSON-RPC 2.0 over HTTP.
 */
public class A2aAgentTransport implements AgentTransport {

    private static final Logger logger = LoggerFactory.getLogger(A2aAgentTransport.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient httpClient;

    public A2aAgentTransport(String agentName, String baseUrl) {
        this(agentName, baseUrl, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    /** Constructor with injectable HttpClient for testing. */
    public A2aAgentTransport(String agentName, String baseUrl, HttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
    }

    @Override
    public AgentResult send(String agentName, String skill, Map<String, Object> args) {
        var start = Instant.now();
        try {
            var requestBody = JsonRpcUtils.buildSendRequest(skill, args);
            var httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .build();

            logger.debug("A2A dispatch to '{}' skill '{}' at {}", agentName, skill, baseUrl);
            var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            var duration = Duration.between(start, Instant.now());

            if (response.statusCode() == 200) {
                var json = mapper.readTree(response.body());

                // Check for JSON-RPC error field before reporting success
                if (json.has("error")) {
                    var errorNode = json.get("error");
                    var errorMsg = errorNode.has("message")
                            ? errorNode.get("message").asText()
                            : errorNode.toString();
                    logger.warn("A2A dispatch to '{}' skill '{}' returned error: {}",
                            agentName, skill, errorMsg);
                    return AgentResult.failure(agentName, skill, errorMsg, duration);
                }

                // Check for failed task status
                var result = json.get("result");
                if (result != null && result.has("status")) {
                    var state = result.get("status").has("state")
                            ? result.get("status").get("state").asText() : "";
                    if ("failed".equalsIgnoreCase(state) || "canceled".equalsIgnoreCase(state)) {
                        var statusMsg = result.get("status").has("message")
                                ? result.get("status").get("message").asText()
                                : "Task " + state;
                        return AgentResult.failure(agentName, skill, statusMsg, duration);
                    }
                }

                var text = JsonRpcUtils.extractArtifactText(json);
                return new AgentResult(agentName, skill, text, Map.of(), duration, true);
            }
            return AgentResult.failure(agentName, skill,
                    "A2A call failed: HTTP " + response.statusCode(), duration);

        } catch (Exception e) {
            logger.error("A2A dispatch to '{}' failed", agentName, e);
            return AgentResult.failure(agentName, skill,
                    "A2A dispatch failed: " + e.getMessage(),
                    Duration.between(start, Instant.now()));
        }
    }

    @Override
    public void stream(String agentName, String skill, Map<String, Object> args,
                       Consumer<String> onToken, Runnable onComplete) {
        try {
            var requestBody = JsonRpcUtils.buildStreamRequest(skill, args);
            var httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .timeout(Duration.ofSeconds(60))
                    .build();

            var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() == 200) {
                var tokenEmitted = new AtomicBoolean(false);
                response.body().forEach(line -> {
                    if (line.startsWith("data:")) {
                        var data = line.substring(5).strip();
                        if (!data.isEmpty() && !"[DONE]".equals(data)) {
                            var text = extractStreamingText(data);
                            if (text != null && !text.isEmpty()) {
                                tokenEmitted.set(true);
                                onToken.accept(text);
                            }
                        }
                    }
                });
                if (tokenEmitted.get()) {
                    onComplete.run();
                    return;
                }
                logger.debug("A2A streaming to '{}' returned no tokens, falling back to send",
                        agentName);
            } else {
                logger.debug("A2A streaming returned HTTP {}, falling back to send",
                        response.statusCode());
            }
        } catch (Exception e) {
            logger.debug("A2A streaming to '{}' failed, falling back to send: {}",
                    agentName, e.getMessage());
        }
        // Graceful fallback to synchronous
        var result = send(agentName, skill, args);
        onToken.accept(result.text());
        onComplete.run();
    }

    private String extractStreamingText(String data) {
        try {
            var json = mapper.readTree(data);
            // A2A streaming event: artifact part text
            if (json.has("artifact")) {
                var parts = json.get("artifact").get("parts");
                if (parts != null && parts.isArray() && !parts.isEmpty()
                        && parts.get(0).has("text")) {
                    return parts.get(0).get("text").asText();
                }
            }
            // Status update with message
            if (json.has("status") && json.get("status").has("message")) {
                return json.get("status").get("message").asText();
            }
            // Plain text delta
            if (json.has("text")) {
                return json.get("text").asText();
            }
        } catch (Exception e) {
            logger.debug("Failed to parse SSE data: {}", data);
        }
        return null;
    }

    @Override
    public boolean isAvailable() {
        try {
            var rpc = Map.of("jsonrpc", "2.0", "id", 1,
                    "method", "agent/authenticatedExtendedCard");
            var httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rpc)))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .build();
            var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

}
