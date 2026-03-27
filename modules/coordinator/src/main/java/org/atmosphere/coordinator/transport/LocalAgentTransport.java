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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.atmosphere.a2a.runtime.LocalDispatchable;
import org.atmosphere.coordinator.fleet.AgentResult;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
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
    private final String a2aPath;

    public LocalAgentTransport(AtmosphereFramework framework, String agentName, String a2aPath) {
        this.framework = framework;
        this.a2aPath = a2aPath;
    }

    @Override
    public AgentResult send(String agentName, String skill, Map<String, Object> args) {
        var start = Instant.now();
        try {
            var requestBody = JsonRpcUtils.buildSendRequest(skill, args);

            var handlerWrapper = framework.getAtmosphereHandlers().get(a2aPath);
            if (handlerWrapper == null) {
                return AgentResult.failure(agentName, skill,
                        "Agent not found at " + a2aPath, Duration.between(start, Instant.now()));
            }

            var handler = handlerWrapper.atmosphereHandler();
            if (!(handler instanceof LocalDispatchable dispatchable)) {
                return AgentResult.failure(agentName, skill,
                        "Handler at " + a2aPath + " does not support local dispatch",
                        Duration.between(start, Instant.now()));
            }

            var responseStr = dispatchable.dispatchLocal(requestBody);
            var json = mapper.readTree(responseStr);
            var duration = Duration.between(start, Instant.now());

            // Check for JSON-RPC error field before reporting success
            if (json.has("error")) {
                var errorNode = json.get("error");
                var errorMsg = errorNode.has("message")
                        ? errorNode.get("message").stringValue()
                        : errorNode.toString();
                logger.warn("Local dispatch to '{}' skill '{}' returned error: {}",
                        agentName, skill, errorMsg);
                return AgentResult.failure(agentName, skill, errorMsg, duration);
            }

            // Check for failed task status
            var result = json.get("result");
            if (result != null && result.has("status")) {
                var state = result.get("status").has("state")
                        ? result.get("status").get("state").stringValue() : "";
                if ("failed".equalsIgnoreCase(state) || "canceled".equalsIgnoreCase(state)) {
                    var statusMsg = result.get("status").has("message")
                            ? result.get("status").get("message").stringValue()
                            : "Task " + state;
                    return AgentResult.failure(agentName, skill, statusMsg, duration);
                }
            }

            var text = JsonRpcUtils.extractArtifactText(json);

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
    public void stream(String agentName, String skill, Map<String, Object> args,
                       Consumer<String> onToken, Runnable onComplete) {
        try {
            var handlerWrapper = framework.getAtmosphereHandlers().get(a2aPath);
            if (handlerWrapper != null
                    && handlerWrapper.atmosphereHandler() instanceof LocalDispatchable dispatchable) {
                var requestBody = JsonRpcUtils.buildSendRequest(skill, args);
                var tokenEmitted = new AtomicBoolean(false);
                Consumer<String> trackingToken = token -> {
                    tokenEmitted.set(true);
                    onToken.accept(token);
                };
                dispatchable.dispatchLocalStreaming(requestBody, trackingToken, () -> {});
                if (tokenEmitted.get()) {
                    onComplete.run();
                    return;
                }
                logger.debug("Local streaming to '{}' produced no tokens, " +
                        "falling back to send", agentName);
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
                        return parts.get(0).get("text").stringValue();
                    }
                }
            }
            if (result.has("status") && result.get("status").has("message")) {
                return result.get("status").get("message").stringValue();
            }
        }
        return json.toString();
    }
}
