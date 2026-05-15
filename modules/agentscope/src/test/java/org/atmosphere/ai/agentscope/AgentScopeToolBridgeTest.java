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
package org.atmosphere.ai.agentscope;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentScopeToolBridgeTest {

    @Test
    void toolkitRoutesToolExecutionThroughAtmosphereDefinition() {
        var tool = ToolDefinition.builder("greet", "Say hello")
                .parameter("name", "Name to greet", "string")
                .executor(args -> "hello " + args.get("name"))
                .build();
        var toolkit = AgentScopeToolBridge.toToolkit(List.of(tool), null, null, null);
        var agentTool = toolkit.getTool("greet");
        var call = ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1")
                        .name("greet")
                        .input(java.util.Map.of("name", "Ada"))
                        .build())
                .input(java.util.Map.of("name", "Ada"))
                .build();

        var result = agentTool.callAsync(call).block();

        assertEquals("call-1", result.getId());
        assertEquals("greet", result.getName());
        assertEquals("hello Ada", ((TextBlock) result.getOutput().get(0)).getText());
    }

    @Test
    void schemaContainsRequiredParameters() {
        var tool = ToolDefinition.builder("add", "Add numbers")
                .parameter("left", "Left number", "number")
                .parameter("right", "Right number", "number", false)
                .executor(args -> "3")
                .build();

        var schema = AgentScopeToolBridge.toSchema(tool);

        assertEquals("add", schema.getName());
        var parameters = schema.getParameters();
        assertTrue(parameters.containsKey("properties"));
        assertEquals(List.of("left"), parameters.get("required"));
    }
}
