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
package org.atmosphere.ai.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallAccumulatorTest {

    @Test
    void testBasicAccumulation() {
        var acc = new ToolCallAccumulator();
        acc.setId("call_123");
        acc.setFunctionName("get_weather");
        acc.appendArguments("{\"city\":");
        acc.appendArguments("\"Montreal\"}");

        assertEquals("call_123", acc.id());
        assertEquals("get_weather", acc.functionName());
        assertEquals("{\"city\":\"Montreal\"}", acc.arguments());
    }

    @Test
    void testEmptyArguments() {
        var acc = new ToolCallAccumulator();
        acc.setId("call_456");
        acc.setFunctionName("no_args_tool");

        assertEquals("call_456", acc.id());
        assertEquals("no_args_tool", acc.functionName());
        assertEquals("", acc.arguments());
    }

    @Test
    void testMultipleArgumentChunks() {
        var acc = new ToolCallAccumulator();
        acc.appendArguments("{");
        acc.appendArguments("\"name\"");
        acc.appendArguments(":");
        acc.appendArguments("\"test\"");
        acc.appendArguments(",");
        acc.appendArguments("\"count\"");
        acc.appendArguments(":");
        acc.appendArguments("42");
        acc.appendArguments("}");

        assertEquals("{\"name\":\"test\",\"count\":42}", acc.arguments());
    }

    @Test
    void testOverwriteFields() {
        var acc = new ToolCallAccumulator();
        acc.setId("call_1");
        acc.setFunctionName("func_1");

        acc.setId("call_2");
        acc.setFunctionName("func_2");

        assertEquals("call_2", acc.id());
        assertEquals("func_2", acc.functionName());
    }
}
