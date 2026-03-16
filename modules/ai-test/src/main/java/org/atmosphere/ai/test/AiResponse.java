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
package org.atmosphere.ai.test;

import org.atmosphere.ai.AiEvent;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Captured response from an AI endpoint, providing accessors for assertions.
 *
 * @param text      the full text response
 * @param events    all AiEvent instances emitted during streaming
 * @param metadata  metadata key-value pairs sent during streaming
 * @param errors    error messages, if any
 * @param elapsed   total wall-clock time for the response
 * @param completed whether the stream completed normally (vs error/timeout)
 */
public record AiResponse(
        String text,
        List<AiEvent> events,
        Map<String, Object> metadata,
        List<String> errors,
        Duration elapsed,
        boolean completed
) {
    /**
     * Get all events of a specific type.
     */
    @SuppressWarnings("unchecked")
    public <T extends AiEvent> List<T> eventsOfType(Class<T> type) {
        return events.stream()
                .filter(type::isInstance)
                .map(e -> (T) e)
                .toList();
    }

    /**
     * Check if a tool was called by name.
     */
    public boolean hasToolCall(String toolName) {
        return events.stream()
                .filter(e -> e instanceof AiEvent.ToolStart ts && ts.toolName().equals(toolName))
                .findAny()
                .isPresent();
    }

    /**
     * Check if a tool returned a result.
     */
    public boolean hasToolResult(String toolName) {
        return events.stream()
                .filter(e -> e instanceof AiEvent.ToolResult tr && tr.toolName().equals(toolName))
                .findAny()
                .isPresent();
    }

    /**
     * Check if any errors occurred.
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
