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

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void toJsonIncludesAllFields() throws Exception {
        var request = new ToolCallRequest("call-1", "getWeather",
                Map.of("city", "Montreal"));
        var json = request.toJson();
        var node = MAPPER.readTree(json);

        assertEquals("tool_call", node.get("type").stringValue());
        assertEquals("call-1", node.get("id").stringValue());
        assertEquals("getWeather", node.get("name").stringValue());
        assertEquals("Montreal", node.get("args").get("city").stringValue());
    }

    @Test
    void toJsonWithEmptyArgs() throws Exception {
        var request = new ToolCallRequest("call-2", "ping", Map.of());
        var json = request.toJson();
        var node = MAPPER.readTree(json);

        assertEquals("ping", node.get("name").stringValue());
        assertTrue(node.get("args").isEmpty());
    }

    @Test
    void toJsonWithNullArgsUsesEmptyMap() throws Exception {
        var request = new ToolCallRequest("call-3", "noop", null);
        var json = request.toJson();
        var node = MAPPER.readTree(json);

        assertNotNull(node.get("args"));
        assertTrue(node.get("args").isEmpty());
    }

    @Test
    void toJsonHandlesSpecialCharacters() throws Exception {
        var request = new ToolCallRequest("call-4", "echo",
                Map.of("msg", "hello \"world\" \n\ttab"));
        var json = request.toJson();
        var node = MAPPER.readTree(json);

        assertEquals("hello \"world\" \n\ttab", node.get("args").get("msg").stringValue());
    }

    @Test
    void recordAccessors() {
        var request = new ToolCallRequest("id-1", "tool-a", Map.of("k", "v"));
        assertEquals("id-1", request.id());
        assertEquals("tool-a", request.name());
        assertEquals(Map.of("k", "v"), request.args());
    }
}
