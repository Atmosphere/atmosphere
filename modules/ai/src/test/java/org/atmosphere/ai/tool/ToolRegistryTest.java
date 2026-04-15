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

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for the {@link ToolRegistry} interface, specifically its default
 * {@code execute(toolName, args, session)} method which emits AiEvents.
 */
class ToolRegistryTest {

    /**
     * Minimal concrete implementation for testing the default method.
     */
    private static class StubToolRegistry implements ToolRegistry {

        private final ToolResult resultToReturn;

        StubToolRegistry(ToolResult resultToReturn) {
            this.resultToReturn = resultToReturn;
        }

        @Override
        public void register(ToolDefinition tool) {
            // no-op
        }

        @Override
        public void register(Object toolProvider) {
            // no-op
        }

        @Override
        public Optional<ToolDefinition> getTool(String name) {
            return Optional.empty();
        }

        @Override
        public Collection<ToolDefinition> getTools(Collection<String> names) {
            return java.util.List.of();
        }

        @Override
        public Collection<ToolDefinition> allTools() {
            return java.util.List.of();
        }

        @Override
        public boolean unregister(String name) {
            return false;
        }

        @Override
        public ToolResult execute(String toolName, Map<String, Object> arguments) {
            return resultToReturn;
        }
    }

    @Test
    void executeWithSessionEmitsToolStartAndToolResultOnSuccess() {
        var successResult = ToolResult.success("weather", "{\"temp\":22}");
        var registry = new StubToolRegistry(successResult);
        var session = Mockito.mock(StreamingSession.class);
        var args = Map.<String, Object>of("city", "Montreal");

        var result = registry.execute("weather", args, session);

        assertSame(successResult, result);

        var captor = ArgumentCaptor.forClass(AiEvent.class);
        verify(session, times(2)).emit(captor.capture());

        var events = captor.getAllValues();
        var toolStart = (AiEvent.ToolStart) events.get(0);
        assertEquals("weather", toolStart.toolName());
        assertEquals(Map.of("city", "Montreal"), toolStart.arguments());

        var toolResult = (AiEvent.ToolResult) events.get(1);
        assertEquals("weather", toolResult.toolName());
        assertEquals("{\"temp\":22}", toolResult.result());
    }

    @Test
    void executeWithSessionEmitsToolStartAndToolErrorOnFailure() {
        var failureResult = ToolResult.failure("weather", "API timeout");
        var registry = new StubToolRegistry(failureResult);
        var session = Mockito.mock(StreamingSession.class);
        var args = Map.<String, Object>of("city", "Montreal");

        var result = registry.execute("weather", args, session);

        assertSame(failureResult, result);

        var captor = ArgumentCaptor.forClass(AiEvent.class);
        verify(session, times(2)).emit(captor.capture());

        var events = captor.getAllValues();
        var toolStart = (AiEvent.ToolStart) events.get(0);
        assertEquals("weather", toolStart.toolName());

        var toolError = (AiEvent.ToolError) events.get(1);
        assertEquals("weather", toolError.toolName());
        assertEquals("API timeout", toolError.error());
    }

    @Test
    void executeWithSessionPassesEmptyArgsCorrectly() {
        var successResult = ToolResult.success("noop", "done");
        var registry = new StubToolRegistry(successResult);
        var session = Mockito.mock(StreamingSession.class);

        registry.execute("noop", Map.of(), session);

        var captor = ArgumentCaptor.forClass(AiEvent.class);
        verify(session, times(2)).emit(captor.capture());

        var toolStart = (AiEvent.ToolStart) captor.getAllValues().get(0);
        assertEquals(Map.of(), toolStart.arguments());
    }
}
