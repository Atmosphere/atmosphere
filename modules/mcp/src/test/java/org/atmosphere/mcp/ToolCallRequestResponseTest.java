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

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallRequestResponseTest {

    // --- ToolCallRequest ---

    @Test
    void requestToJsonContainsAllFields() {
        var request = new ToolCallRequest("call-1", "weather", Map.of("city", "Montreal"));
        var json = request.toJson();
        assertTrue(json.contains("\"type\":\"tool_call\""));
        assertTrue(json.contains("\"id\":\"call-1\""));
        assertTrue(json.contains("\"name\":\"weather\""));
        assertTrue(json.contains("\"city\":\"Montreal\""));
    }

    @Test
    void requestToJsonHandlesNullArgs() {
        var request = new ToolCallRequest("call-2", "ping", null);
        var json = request.toJson();
        assertTrue(json.contains("\"args\":{}"));
    }

    @Test
    void requestToJsonHandlesEmptyArgs() {
        var request = new ToolCallRequest("call-3", "health", Map.of());
        var json = request.toJson();
        assertTrue(json.contains("\"args\":{}"));
    }

    // --- ToolCallResponse ---

    @Test
    void responseFromJsonParsesSuccessfully() {
        var response = ToolCallResponse.fromJson("{\"id\":\"call-1\",\"result\":\"22°C\"}");
        assertEquals("call-1", response.id());
        assertEquals("22°C", response.result());
        assertNull(response.error());
        assertFalse(response.isError());
    }

    @Test
    void responseFromJsonParsesError() {
        var response = ToolCallResponse.fromJson("{\"id\":\"call-1\",\"error\":\"timeout\"}");
        assertEquals("call-1", response.id());
        assertNull(response.result());
        assertEquals("timeout", response.error());
        assertTrue(response.isError());
    }

    @Test
    void responseFromJsonThrowsOnMissingId() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolCallResponse.fromJson("{\"result\":\"ok\"}"));
    }

    @Test
    void responseFromJsonThrowsOnNullInput() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolCallResponse.fromJson(null));
    }

    @Test
    void responseFromJsonThrowsOnBlankInput() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolCallResponse.fromJson("  "));
    }

    @Test
    void responseFromJsonThrowsOnInvalidJson() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolCallResponse.fromJson("not-json"));
    }

    @Test
    void responseResultValueReturnsResultWhenPresent() {
        var response = new ToolCallResponse("id", "ok", null);
        assertEquals("ok", response.resultValue());
    }

    @Test
    void responseResultValueReturnsErrorWhenNoResult() {
        var response = new ToolCallResponse("id", null, "fail");
        assertEquals("fail", response.resultValue());
    }

    @Test
    void responseIsErrorReturnsFalseForBlankError() {
        var response = new ToolCallResponse("id", "ok", "  ");
        assertFalse(response.isError());
    }
}
