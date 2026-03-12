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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallResponseTest {

    @Test
    void fromJsonParsesSuccessResponse() {
        var response = ToolCallResponse.fromJson(
                "{\"id\":\"call-1\",\"result\":\"sunny\"}");
        assertEquals("call-1", response.id());
        assertEquals("sunny", response.result());
        assertNull(response.error());
        assertFalse(response.isError());
    }

    @Test
    void fromJsonParsesErrorResponse() {
        var response = ToolCallResponse.fromJson(
                "{\"id\":\"call-2\",\"error\":\"Tool not found\"}");
        assertEquals("call-2", response.id());
        assertNull(response.result());
        assertEquals("Tool not found", response.error());
        assertTrue(response.isError());
    }

    @Test
    void fromJsonParsesResponseWithBothFields() {
        var response = ToolCallResponse.fromJson(
                "{\"id\":\"call-3\",\"result\":\"partial\",\"error\":\"warning\"}");
        assertEquals("partial", response.result());
        assertEquals("warning", response.error());
        assertTrue(response.isError());
    }

    @Test
    void fromJsonThrowsOnMissingId() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolCallResponse.fromJson("{\"result\":\"ok\"}"));
    }

    @Test
    void fromJsonThrowsOnNullInput() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolCallResponse.fromJson(null));
    }

    @Test
    void fromJsonThrowsOnBlankInput() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolCallResponse.fromJson("   "));
    }

    @Test
    void fromJsonThrowsOnInvalidJson() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolCallResponse.fromJson("not json"));
    }

    @Test
    void isErrorReturnsFalseForNullError() {
        var response = new ToolCallResponse("id", "result", null);
        assertFalse(response.isError());
    }

    @Test
    void isErrorReturnsFalseForBlankError() {
        var response = new ToolCallResponse("id", "result", "  ");
        assertFalse(response.isError());
    }

    @Test
    void resultValueReturnsResult() {
        var response = new ToolCallResponse("id", "42", null);
        assertEquals("42", response.resultValue());
    }

    @Test
    void resultValueReturnsErrorWhenNoResult() {
        var response = new ToolCallResponse("id", null, "failed");
        assertEquals("failed", response.resultValue());
    }

    @Test
    void fromJsonHandlesNullIdField() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolCallResponse.fromJson("{\"id\":null,\"result\":\"ok\"}"));
    }
}
