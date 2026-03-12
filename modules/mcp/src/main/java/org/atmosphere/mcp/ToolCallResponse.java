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
package org.atmosphere.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Represents a client-to-server tool call response.
 *
 * <p>Expected JSON format:
 * {@code {"id":"...","result":"...","error":"..."}}</p>
 *
 * @param id     the call ID matching the original {@link ToolCallRequest}
 * @param result the tool result (null if error)
 * @param error  the error message (null if success)
 */
public record ToolCallResponse(String id, String result, String error) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parse a JSON string into a {@link ToolCallResponse}.
     *
     * @param json the JSON string
     * @return parsed response
     * @throws IllegalArgumentException if JSON is invalid or missing required fields
     */
    public static ToolCallResponse fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Response JSON is null or blank");
        }
        try {
            var node = MAPPER.readTree(json);
            var id = textOrNull(node, "id");
            if (id == null) {
                throw new IllegalArgumentException("Response JSON missing 'id' field");
            }
            var result = textOrNull(node, "result");
            var error = textOrNull(node, "error");
            return new ToolCallResponse(id, result, error);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid response JSON: " + json, e);
        }
    }

    /**
     * @return true if this response represents an error
     */
    public boolean isError() {
        return error != null && !error.isBlank();
    }

    /**
     * @return the result value, or the error message if no result
     */
    public String resultValue() {
        return result != null ? result : error;
    }

    private static String textOrNull(JsonNode node, String field) {
        var child = node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        return child.asText();
    }
}
