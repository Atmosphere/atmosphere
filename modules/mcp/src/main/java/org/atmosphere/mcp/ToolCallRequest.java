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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a server-to-client tool call request.
 *
 * <p>Serializes to JSON with the format:
 * {@code {"type":"tool_call","id":"...","name":"...","args":{...}}}</p>
 *
 * @param id   unique call identifier
 * @param name tool name to invoke on the client
 * @param args tool arguments (may be empty or null)
 */
public record ToolCallRequest(String id, String name, Map<String, Object> args) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Serialize this request to JSON for transmission to the client.
     *
     * @return JSON string
     */
    public String toJson() {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", "tool_call");
        map.put("id", id);
        map.put("name", name);
        map.put("args", args != null ? args : Map.of());
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ToolCallRequest", e);
        }
    }
}
