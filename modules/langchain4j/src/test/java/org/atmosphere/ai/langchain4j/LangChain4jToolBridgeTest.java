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
package org.atmosphere.ai.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import org.atmosphere.ai.tool.ToolBridgeUtils;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LangChain4jToolBridge}.
 */
public class LangChain4jToolBridgeTest {

    @Test
    public void testToToolSpecifications() {
        var tools = List.of(
                ToolDefinition.builder("get_weather", "Get weather for a city")
                        .parameter("city", "City name", "string")
                        .parameter("units", "Temperature units", "string", false)
                        .executor(args -> "sunny")
                        .build()
        );

        var specs = LangChain4jToolBridge.toToolSpecifications(tools);
        assertEquals(1, specs.size());

        var spec = specs.get(0);
        assertEquals("get_weather", spec.name());
        assertEquals("Get weather for a city", spec.description());
        assertNotNull(spec.parameters());
        assertEquals(2, spec.parameters().properties().size());
        assertTrue(spec.parameters().required().contains("city"));
        assertFalse(spec.parameters().required().contains("units"));
    }

    @Test
    public void testToToolSpecificationsWithDifferentTypes() {
        var tools = List.of(
                ToolDefinition.builder("calculate", "Calculate")
                        .parameter("a", "First number", "integer")
                        .parameter("b", "Second number", "number")
                        .parameter("verbose", "Verbose output", "boolean")
                        .executor(args -> "42")
                        .build()
        );

        var specs = LangChain4jToolBridge.toToolSpecifications(tools);
        assertEquals(1, specs.size());
        assertEquals(3, specs.get(0).parameters().properties().size());
    }

    @Test
    public void testToToolMap() {
        var tool1 = ToolDefinition.builder("a", "A").executor(args -> "a").build();
        var tool2 = ToolDefinition.builder("b", "B").executor(args -> "b").build();

        var map = LangChain4jToolBridge.toToolMap(List.of(tool1, tool2));
        assertEquals(2, map.size());
        assertSame(tool1, map.get("a"));
        assertSame(tool2, map.get("b"));
    }

    @Test
    public void testExecuteToolCalls() {
        var tool = ToolDefinition.builder("add", "Add numbers")
                .parameter("a", "First", "integer")
                .parameter("b", "Second", "integer")
                .executor(args -> {
                    int a = Integer.parseInt(args.get("a").toString());
                    int b = Integer.parseInt(args.get("b").toString());
                    return a + b;
                })
                .build();
        var toolMap = Map.of("add", tool);

        var request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("add")
                .arguments("{\"a\":3,\"b\":7}")
                .build();
        var aiMessage = AiMessage.from(List.of(request));

        var results = LangChain4jToolBridge.executeToolCalls(aiMessage, toolMap, null, null, List.of(), null);
        assertEquals(1, results.size());
        assertEquals("10", results.get(0).text());
        assertEquals("add", results.get(0).toolName());
    }

    @Test
    public void testExecuteToolCallsUnknownTool() {
        var request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("missing_tool")
                .arguments("{}")
                .build();
        var aiMessage = AiMessage.from(List.of(request));

        var results = LangChain4jToolBridge.executeToolCalls(aiMessage, Map.of(), null, null, List.of(), null);
        assertEquals(1, results.size());
        assertTrue(results.get(0).text().contains("Tool not found"));
    }

    @Test
    public void testUnknownToolEndsWithAToolError() {
        // The model asked for a tool there is none of: the call is reported
        // and fails, as on the built-in runtime, instead of leaving no trace.
        var request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("missing_tool")
                .arguments("{}")
                .build();
        var frames = new java.util.ArrayList<org.atmosphere.ai.AiEvent>();
        var session = new org.atmosphere.ai.StreamingSession() {
            @Override public String sessionId() { return "s"; }
            @Override public void send(String text) { }
            @Override public void sendMetadata(String key, Object value) { }
            @Override public void progress(String message) { }
            @Override public void complete() { }
            @Override public void complete(String summary) { }
            @Override public void error(Throwable t) { }
            @Override public boolean isClosed() { return false; }
            @Override public void emit(org.atmosphere.ai.AiEvent event) { frames.add(event); }
        };

        LangChain4jToolBridge.executeToolCalls(AiMessage.from(List.of(request)), Map.of(), session, null,
                List.of(), null);

        assertEquals(List.of(new org.atmosphere.ai.AiEvent.ToolStart("missing_tool", Map.of()),
                new org.atmosphere.ai.AiEvent.ToolError("missing_tool", "Tool not found")), frames);
    }

    @Test
    public void testParseJsonArgsStringValues() {
        var result = ToolBridgeUtils.parseJsonArgs("{\"name\":\"Alice\"}");
        assertEquals("Alice", result.get("name"));
    }

    @Test
    public void testParseJsonArgsNumericValues() {
        var result = ToolBridgeUtils.parseJsonArgs("{\"count\":42}");
        assertEquals(42L, result.get("count"));
    }

    @Test
    public void testParseJsonArgsEmpty() {
        assertEquals(Map.of(), ToolBridgeUtils.parseJsonArgs(null));
        assertEquals(Map.of(), ToolBridgeUtils.parseJsonArgs("{}"));
    }
}
