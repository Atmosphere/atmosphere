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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared JSON-RPC 2.0 utilities used by both {@link LocalAgentTransport} and
 * {@link A2aAgentTransport} for building A2A protocol messages. Aligned with
 * A2A v1.0.0: method names are {@code SendMessage} / {@code SendStreamingMessage}
 * (PascalCase per spec §9.4) and message parts use the flattened
 * {@code {"text":"..."}} shape (the v1.0.0 collapsed-{@code Part} schema; no
 * {@code type} / {@code kind} discriminator).
 */
final class JsonRpcUtils {

    static final String METHOD_SEND_MESSAGE = "SendMessage";
    static final String METHOD_SEND_STREAMING_MESSAGE = "SendStreamingMessage";

    private static final ObjectMapper mapper = new ObjectMapper();

    private JsonRpcUtils() { }

    static String buildSendRequest(String skill, Map<String, Object> args) {
        return buildRequest(METHOD_SEND_MESSAGE, skill, args);
    }

    static String buildStreamRequest(String skill, Map<String, Object> args) {
        return buildRequest(METHOD_SEND_STREAMING_MESSAGE, skill, args);
    }

    /**
     * Extract the first artifact text from an A2A v1.0.0 SendMessage response.
     * The wire shape wraps the task in a {@code SendMessageResponse} oneof:
     * {@code result.task.artifacts[0].parts[0].text} (or {@code result.message.parts[...]}
     * for direct message replies). Falls back to the status message text and
     * finally the raw JSON.
     */
    static String extractArtifactText(JsonNode json) {
        var result = json.get("result");
        if (result == null) {
            return json.toString();
        }
        var task = result.has("task") ? result.get("task") : result;
        var artifactText = firstPartText(task.get("artifacts"));
        if (artifactText != null) {
            return artifactText;
        }
        if (result.has("message")) {
            var msgText = firstPartText(List.of(result.get("message")));
            if (msgText != null) {
                return msgText;
            }
        }
        if (task.has("status") && task.get("status").has("message")) {
            var statusMessage = task.get("status").get("message");
            // Pre-1.0 servers emitted status.message as a plain string; v1.0.0
            // wraps it in a Message record with parts. Accept both.
            if (statusMessage.isString()) {
                return statusMessage.stringValue();
            }
            var fromParts = firstPartText(List.of(statusMessage));
            if (fromParts != null) {
                return fromParts;
            }
        }
        return json.toString();
    }

    /**
     * Walks an array (or single-element list) of part-bearing nodes and
     * returns the first {@code text} field encountered.
     */
    private static String firstPartText(Object partsCarrier) {
        if (partsCarrier instanceof List<?> list) {
            for (var node : list) {
                if (node instanceof JsonNode jn) {
                    var t = textFromPartsCarrier(jn);
                    if (t != null) {
                        return t;
                    }
                }
            }
            return null;
        }
        if (partsCarrier instanceof JsonNode jn && jn.isArray() && !jn.isEmpty()) {
            for (var entry : jn) {
                var t = textFromPartsCarrier(entry);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    private static String textFromPartsCarrier(JsonNode node) {
        var parts = node.get("parts");
        if (parts != null && parts.isArray() && !parts.isEmpty()
                && parts.get(0).has("text")) {
            return parts.get(0).get("text").stringValue();
        }
        return null;
    }

    private static String buildRequest(String method, String skill, Map<String, Object> args) {
        var firstValue = args.values().isEmpty()
                ? "" : String.valueOf(args.values().iterator().next());
        var message = Map.of(
                "messageId", UUID.randomUUID().toString(),
                "role", "ROLE_USER",
                "parts", List.of(Map.of("text", firstValue)),
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
