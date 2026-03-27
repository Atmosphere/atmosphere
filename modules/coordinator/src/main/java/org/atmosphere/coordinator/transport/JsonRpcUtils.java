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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared JSON-RPC 2.0 utilities used by both {@link LocalAgentTransport} and
 * {@link A2aAgentTransport} for building A2A protocol messages.
 */
final class JsonRpcUtils {

    private static final ObjectMapper mapper = new ObjectMapper();

    private JsonRpcUtils() { }

    /**
     * Build a JSON-RPC 2.0 request for the A2A {@code message/send} method.
     */
    static String buildSendRequest(String skill, Map<String, Object> args)
            throws JsonProcessingException {
        return buildRequest("message/send", skill, args);
    }

    /**
     * Build a JSON-RPC 2.0 request for the A2A {@code message/stream} method.
     */
    static String buildStreamRequest(String skill, Map<String, Object> args)
            throws JsonProcessingException {
        return buildRequest("message/stream", skill, args);
    }

    /**
     * Extract the first artifact text from a JSON-RPC result, falling back to
     * the status message or the raw JSON string.
     */
    static String extractArtifactText(JsonNode json) {
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

    private static String buildRequest(String method, String skill, Map<String, Object> args)
            throws JsonProcessingException {
        var firstValue = args.values().isEmpty()
                ? "" : String.valueOf(args.values().iterator().next());
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
                "method", method,
                "params", params
        );
        return mapper.writeValueAsString(rpcRequest);
    }
}
