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

import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DefaultToolRegistry}.
 */
public class DefaultToolRegistryTest {

    @Test
    public void testRegisterAndRetrieveTool() {
        var registry = new DefaultToolRegistry();
        var tool = ToolDefinition.builder("greet", "Say hello")
                .parameter("name", "The name to greet", "string")
                .executor(args -> "Hello " + args.get("name"))
                .build();

        registry.register(tool);

        var found = registry.getTool("greet");
        assertTrue(found.isPresent());
        assertEquals("greet", found.get().name());
        assertEquals("Say hello", found.get().description());
    }

    @Test
    public void testRegisterDuplicateThrows() {
        var registry = new DefaultToolRegistry();
        var tool = ToolDefinition.builder("greet", "Say hello")
                .executor(args -> "hi")
                .build();

        registry.register(tool);
        assertThrows(IllegalArgumentException.class, () -> registry.register(tool));
    }

    @Test
    public void testGetToolReturnsEmptyForUnknown() {
        var registry = new DefaultToolRegistry();
        assertTrue(registry.getTool("nonexistent").isEmpty());
    }

    @Test
    public void testAllTools() {
        var registry = new DefaultToolRegistry();
        var tool1 = ToolDefinition.builder("tool_a", "Tool A")
                .executor(args -> "a")
                .build();
        var tool2 = ToolDefinition.builder("tool_b", "Tool B")
                .executor(args -> "b")
                .build();

        registry.register(tool1);
        registry.register(tool2);

        assertEquals(2, registry.allTools().size());
    }

    @Test
    public void testGetToolsByNames() {
        var registry = new DefaultToolRegistry();
        registry.register(ToolDefinition.builder("a", "A").executor(args -> "a").build());
        registry.register(ToolDefinition.builder("b", "B").executor(args -> "b").build());
        registry.register(ToolDefinition.builder("c", "C").executor(args -> "c").build());

        var result = registry.getTools(List.of("a", "c", "missing"));
        assertEquals(2, result.size());
    }

    @Test
    public void testUnregister() {
        var registry = new DefaultToolRegistry();
        registry.register(ToolDefinition.builder("temp", "Temp").executor(args -> "x").build());

        assertTrue(registry.unregister("temp"));
        assertFalse(registry.unregister("temp"));
        assertTrue(registry.getTool("temp").isEmpty());
    }

    @Test
    public void testExecuteSuccess() {
        var registry = new DefaultToolRegistry();
        registry.register(ToolDefinition.builder("add", "Add numbers")
                .parameter("a", "First", "integer")
                .parameter("b", "Second", "integer")
                .executor(args -> {
                    int a = Integer.parseInt(args.get("a").toString());
                    int b = Integer.parseInt(args.get("b").toString());
                    return a + b;
                })
                .build());

        var result = registry.execute("add", Map.of("a", 3, "b", 7));
        assertTrue(result.success());
        assertEquals("10", result.result());
    }

    @Test
    public void testExecuteUnknownTool() {
        var registry = new DefaultToolRegistry();
        var result = registry.execute("missing", Map.of());
        assertFalse(result.success());
        assertTrue(result.error().contains("Unknown tool"));
    }

    @Test
    public void testExecuteToolException() {
        var registry = new DefaultToolRegistry();
        registry.register(ToolDefinition.builder("fail", "Always fails")
                .executor(args -> { throw new RuntimeException("boom"); })
                .build());

        var result = registry.execute("fail", Map.of());
        assertFalse(result.success());
        assertEquals("boom", result.error());
    }

    @Test
    public void testRegisterAnnotatedToolProvider() {
        var registry = new DefaultToolRegistry();
        registry.register(new WeatherToolProvider());

        var tool = registry.getTool("get_weather");
        assertTrue(tool.isPresent());
        assertEquals("Get current weather for a city", tool.get().description());
        assertEquals(1, tool.get().parameters().size());
        assertEquals("city", tool.get().parameters().get(0).name());
    }

    @Test
    public void testExecuteAnnotatedTool() throws Exception {
        var registry = new DefaultToolRegistry();
        registry.register(new WeatherToolProvider());

        var result = registry.execute("get_weather", Map.of("city", "Paris"));
        assertTrue(result.success());
        assertEquals("Weather in Paris: sunny, 22°C", result.result());
    }

    // Test tool provider
    static class WeatherToolProvider {
        @AiTool(name = "get_weather", description = "Get current weather for a city")
        public String getWeather(@Param(value = "city", description = "The city name") String city) {
            return "Weather in " + city + ": sunny, 22°C";
        }
    }

    /**
     * Pins the injectables-aware dispatch contract: an @AiTool method may
     * declare a framework-scoped parameter (here {@code StreamingSession})
     * and the reflective executor must
     *  (a) exclude that parameter from the JSON schema (the LLM never supplies
     *      it), and
     *  (b) inject the live instance from the injectables map at call time.
     *
     * <p>This is the regression surface for the "no ThreadLocal" shift — a
     * @AiTool method should reach the live session/fleet/identity via typed
     * parameters, not via a static ThreadLocal smuggled from @Prompt. If this
     * test fails, @AiTool methods fall back to asking the LLM for a
     * session-shaped argument and {@code invoke()} throws with a null.</p>
     */
    @Test
    public void frameworkTypedParamsAreInjectedAndHiddenFromSchema() throws Exception {
        var registry = new DefaultToolRegistry();
        var provider = new SessionAwareToolProvider();
        registry.register(provider);

        var tool = registry.getTool("echo_session_id").orElseThrow();
        // (a) The session parameter must NOT leak into the JSON schema — the
        //     LLM only sees the business arg.
        assertEquals(1, tool.parameters().size());
        assertEquals("note", tool.parameters().get(0).name());

        // (b) A live session from the injectables map reaches the method.
        var fakeSession = new StubSession("sess-42");
        var result = tool.executor().execute(
                Map.of("note", "hello"),
                Map.<Class<?>, Object>of(
                        org.atmosphere.ai.StreamingSession.class, fakeSession));
        assertEquals("sess-42: hello", result);
    }

    static class SessionAwareToolProvider {
        @AiTool(name = "echo_session_id", description = "Echoes a note prefixed with the session id")
        public String echo(org.atmosphere.ai.StreamingSession session,
                           @Param("note") String note) {
            return session.sessionId() + ": " + note;
        }
    }

    /** Minimal {@code StreamingSession} for the injectables test. */
    static final class StubSession implements org.atmosphere.ai.StreamingSession {
        private final String id;
        StubSession(String id) { this.id = id; }
        @Override public String sessionId() { return id; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public void emit(org.atmosphere.ai.AiEvent event) { }
        @Override public boolean isClosed() { return false; }
        @Override public boolean hasErrored() { return false; }
    }
}
