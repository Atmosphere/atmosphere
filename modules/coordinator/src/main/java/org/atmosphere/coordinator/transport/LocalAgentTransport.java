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
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Transport for co-located agents (same JVM). Invokes the agent's A2A protocol
 * handler directly — no HTTP, no network overhead.
 */
public class LocalAgentTransport implements AgentTransport {

    private static final Logger logger = LoggerFactory.getLogger(LocalAgentTransport.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final AtmosphereFramework framework;
    private final String agentName;
    private final String a2aPath;

    public LocalAgentTransport(AtmosphereFramework framework, String agentName, String a2aPath) {
        this.framework = framework;
        this.agentName = agentName;
        this.a2aPath = a2aPath;
    }

    @Override
    public AgentResult send(String agentName, String skill, Map<String, String> args) {
        var start = Instant.now();
        try {
            var requestBody = buildJsonRpc(skill, args);

            var handlerWrapper = framework.getAtmosphereHandlers().get(a2aPath);
            if (handlerWrapper == null) {
                return AgentResult.failure(agentName, skill,
                        "Agent not found at " + a2aPath, Duration.between(start, Instant.now()));
            }

            // Access the A2aHandler -> protocolHandler via reflection
            var handler = handlerWrapper.atmosphereHandler();
            var protocolHandlerField = handler.getClass().getDeclaredField("protocolHandler");
            protocolHandlerField.setAccessible(true);
            var protocolHandler = protocolHandlerField.get(handler);
            var handleMethod = protocolHandler.getClass().getMethod("handleMessage", String.class);
            var responseStr = (String) handleMethod.invoke(protocolHandler, requestBody);

            var json = mapper.readTree(responseStr);
            var duration = Duration.between(start, Instant.now());

            // Check for JSON-RPC error field before reporting success
            if (json.has("error")) {
                var errorNode = json.get("error");
                var errorMsg = errorNode.has("message")
                        ? errorNode.get("message").asText()
                        : errorNode.toString();
                logger.warn("Local dispatch to '{}' skill '{}' returned error: {}",
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

            logger.debug("Local dispatch to '{}' skill '{}' completed in {}ms",
                    agentName, skill, duration.toMillis());
            return new AgentResult(agentName, skill, text, Map.of(), duration, true);

        } catch (Exception e) {
            logger.error("Local dispatch to '{}' failed", agentName, e);
            return AgentResult.failure(agentName, skill,
                    "Local dispatch failed: " + e.getMessage(),
                    Duration.between(start, Instant.now()));
        }
    }

    @Override
    public void stream(String agentName, String skill, Map<String, String> args,
                       Consumer<String> onToken, Runnable onComplete) {
        try {
            var handlerWrapper = framework.getAtmosphereHandlers().get(a2aPath);
            if (handlerWrapper != null) {
                var handler = handlerWrapper.atmosphereHandler();
                var protocolHandlerField = handler.getClass().getDeclaredField("protocolHandler");
                protocolHandlerField.setAccessible(true);
                var protocolHandler = protocolHandlerField.get(handler);

                // Try streaming method first
                try {
                    var streamMethod = protocolHandler.getClass().getMethod(
                            "handleStreamingMessage", String.class, Consumer.class, Runnable.class);
                    var requestBody = buildJsonRpc(skill, args);
                    var tokenEmitted = new AtomicBoolean(false);
                    Consumer<String> trackingToken = token -> {
                        tokenEmitted.set(true);
                        onToken.accept(token);
                    };
                    streamMethod.invoke(protocolHandler, requestBody, trackingToken,
                            (Runnable) () -> {});
                    if (tokenEmitted.get()) {
                        onComplete.run();
                        return;
                    }
                    logger.debug("Local streaming to '{}' produced no tokens, " +
                            "falling back to send", agentName);
                } catch (NoSuchMethodException ignored) {
                    // Protocol handler doesn't support streaming — fall through
                }
            }
        } catch (Exception e) {
            logger.debug("Local streaming to '{}' failed, falling back to send: {}",
                    agentName, e.getMessage());
        }
        // Graceful fallback to synchronous
        var result = send(agentName, skill, args);
        onToken.accept(result.text());
        onComplete.run();
    }

    @Override
    public boolean isAvailable() {
        return framework.getAtmosphereHandlers().containsKey(a2aPath);
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
            if (result.has("status") && result.get("status").has("message")) {
                return result.get("status").get("message").asText();
            }
        }
        return json.toString();
    }
}
