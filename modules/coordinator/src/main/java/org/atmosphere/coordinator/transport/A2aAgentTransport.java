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

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Transport for remote agents via A2A JSON-RPC 2.0 over HTTP.
 */
public class A2aAgentTransport implements AgentTransport {

    private static final Logger logger = LoggerFactory.getLogger(A2aAgentTransport.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String agentName;
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
        this.agentName = agentName;
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
    }

    @Override
    public AgentResult send(String agentName, String skill, Map<String, String> args) {
        var start = Instant.now();
        try {
            var requestBody = buildJsonRpc(skill, args);
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

                var text = extractArtifactText(json);
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
    public void stream(String agentName, String skill, Map<String, String> args,
                       Consumer<String> onToken, Runnable onComplete) {
        var result = send(agentName, skill, args);
        onToken.accept(result.text());
        onComplete.run();
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

    private String buildJsonRpc(String skill, Map<String, String> args) throws Exception {
        var firstValue = args.values().isEmpty() ? "" : args.values().iterator().next();
        var message = Map.of(
                "role", "user",
                "parts", List.of(Map.of("type", "text", "text", firstValue)),
                "metadata", Map.of("skillId", skill)
        );
        var params = new LinkedHashMap<String, Object>();
        params.put("message", message);
        params.put("arguments", args);

        var rpcRequest = Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "message/send",
                "params", params
        );
        return mapper.writeValueAsString(rpcRequest);
    }

    private String extractArtifactText(JsonNode json) {
        var result = json.get("result");
        if (result != null) {
            var artifacts = result.get("artifacts");
            if (artifacts != null && artifacts.isArray() && !artifacts.isEmpty()) {
                var parts = artifacts.get(0).get("parts");
                if (parts != null && parts.isArray() && !parts.isEmpty()) {
                    if (parts.get(0).has("text")) {
                        return parts.get(0).get("text").asText();
                    }
                }
            }
        }
        return json.toString();
    }
}
