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
package org.atmosphere.ai.sk;

import com.microsoft.semantickernel.contextvariables.ContextVariable;
import com.microsoft.semantickernel.semanticfunctions.KernelFunctionArguments;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end regression test for {@link SemanticKernelToolBridge} — the
 * bridge that resolves Step 5 of the runtime-capability-honesty pass by
 * routing Atmosphere {@link ToolDefinition}s through Microsoft Semantic
 * Kernel's {@code KernelFunction} surface.
 *
 * <p>Contracts exercised:</p>
 * <ol>
 *   <li>An empty tool list produces a null plugin (zero-tool fast path).</li>
 *   <li>A populated tool list produces a non-null {@code KernelPlugin}
 *       containing one {@code KernelFunction} per Atmosphere tool, each with
 *       the expected name / description / InputVariable shape.</li>
 *   <li>Invoking an {@code AtmosphereSkFunction} via its
 *       {@code invokeAsync(...)} surface routes through
 *       {@link org.atmosphere.ai.tool.ToolExecutionHelper#executeWithApproval}
 *       and returns the tool executor's result as a {@code FunctionResult<String>}.</li>
 *   <li>The runtime's {@code capabilities()} now declares
 *       {@link AiCapability#TOOL_CALLING} and {@link AiCapability#TOOL_APPROVAL}
 *       — the runtime-truth anchor for the contract-test pin.</li>
 * </ol>
 */
class SemanticKernelToolBridgeTest {

    private static AgentExecutionContext contextWith(List<ToolDefinition> tools) {
        return new AgentExecutionContext(
                "hello", null, null, null, null, null, null,
                tools, null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    private static StreamingSession nullSession() {
        return new StreamingSession() {
            @Override public String sessionId() { return "test"; }
            @Override public void send(String text) { }
            @Override public void sendMetadata(String key, Object value) { }
            @Override public void progress(String message) { }
            @Override public void complete() { }
            @Override public void complete(String summary) { }
            @Override public void error(Throwable t) { }
            @Override public boolean isClosed() { return false; }
        };
    }

    @Test
    void buildPluginReturnsNullForEmptyToolList() {
        var ctx = contextWith(List.of());
        var plugin = SemanticKernelToolBridge.buildPlugin(ctx, nullSession());
        assertNull(plugin,
                "zero-tool fast path must return null so callers can skip plugin wiring");
    }

    @Test
    void buildPluginCreatesOneKernelFunctionPerAtmosphereTool() {
        var weatherCalled = new AtomicBoolean(false);
        var calcCalled = new AtomicBoolean(false);

        var weather = ToolDefinition.builder("get_weather", "Get current weather for a city")
                .parameter("city", "The city name", "string")
                .executor(args -> {
                    weatherCalled.set(true);
                    return "sunny, 22C in " + args.get("city");
                })
                .build();

        var calc = ToolDefinition.builder("add", "Add two integers")
                .parameter("a", "First number", "integer")
                .parameter("b", "Second number", "integer")
                .executor(args -> {
                    calcCalled.set(true);
                    return "42";
                })
                .build();

        var ctx = contextWith(List.of(weather, calc));
        var plugin = SemanticKernelToolBridge.buildPlugin(ctx, nullSession());

        assertNotNull(plugin, "two-tool context must produce a plugin");
        assertEquals("atmosphere_tools", plugin.getName());

        var weatherFn = plugin.get("get_weather");
        assertNotNull(weatherFn, "plugin must expose get_weather by name");
        assertEquals("Get current weather for a city", weatherFn.getDescription());
        var weatherMeta = weatherFn.getMetadata();
        assertEquals(1, weatherMeta.getParameters().size());
        assertEquals("city", weatherMeta.getParameters().get(0).getName());
        assertEquals("string", weatherMeta.getParameters().get(0).getType());

        var addFn = plugin.get("add");
        assertNotNull(addFn, "plugin must expose add by name");
        assertEquals(2, addFn.getMetadata().getParameters().size());

        // No executor calls expected yet — the bridge only builds metadata at
        // plugin construction time. Invocation tests come next.
        assertFalse(weatherCalled.get());
        assertFalse(calcCalled.get());
    }

    @Test
    void invokeAsyncRoutesThroughToolExecutionHelperAndReturnsFunctionResult() {
        var captured = new AtomicReference<Map<String, Object>>();
        var weather = ToolDefinition.builder("get_weather", "Get weather")
                .parameter("city", "City name", "string")
                .executor(args -> {
                    captured.set(Map.copyOf(args));
                    return "sunny in " + args.get("city");
                })
                .build();

        var plugin = SemanticKernelToolBridge.buildPlugin(
                contextWith(List.of(weather)), nullSession());
        var fn = plugin.get("get_weather");

        // Build KernelFunctionArguments with a single "city" entry — this is
        // the shape SK's auto-invoke loop would pass when the LLM produces
        // {"city": "Montreal"} as a tool call.
        var args = KernelFunctionArguments.builder()
                .withVariable("city", ContextVariable.of("Montreal"))
                .build();

        var result = fn.invokeAsync(null, args, null, null).block();

        assertNotNull(captured.get(), "tool executor must have been invoked");
        assertEquals("Montreal", captured.get().get("city"));

        assertNotNull(result, "invokeAsync must return a non-null FunctionResult");
        assertEquals("sunny in Montreal", result.getResult());
    }

    @Test
    void capabilitiesDeclareToolCallingAndToolApproval() {
        var caps = new SemanticKernelAgentRuntime().capabilities();
        assertTrue(caps.contains(AiCapability.TOOL_CALLING),
                "SK runtime must declare TOOL_CALLING now that the bridge is wired");
        assertTrue(caps.contains(AiCapability.TOOL_APPROVAL),
                "SK runtime must declare TOOL_APPROVAL because every invocation " +
                        "routes through ToolExecutionHelper.executeWithApproval");
    }
}
