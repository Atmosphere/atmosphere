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
package org.atmosphere.ai.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultTest {

    @Test
    void successFactory() {
        var result = ToolResult.success("weather", "{\"temp\":22}");
        assertEquals("weather", result.toolName());
        assertEquals("{\"temp\":22}", result.result());
        assertTrue(result.success());
        assertNull(result.error());
    }

    @Test
    void failureFactory() {
        var result = ToolResult.failure("weather", "API timeout");
        assertEquals("weather", result.toolName());
        assertNull(result.result());
        assertFalse(result.success());
        assertEquals("API timeout", result.error());
    }

    @Test
    void fullConstructor() {
        var result = new ToolResult("calc", "42", true, null);
        assertEquals("calc", result.toolName());
        assertEquals("42", result.result());
        assertTrue(result.success());
    }
}
