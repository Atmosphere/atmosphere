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

import java.util.Collection;
import java.util.Optional;

/**
 * Global registry for AI-callable tools. Tools are registered at startup
 * (via {@link org.atmosphere.ai.annotation.AiTool} scanning or manual
 * registration) and selected per-endpoint.
 *
 * <p>Usage pattern:</p>
 * <pre>{@code
 * // At startup — scan annotated classes
 * registry.register(WeatherTools.class);
 * registry.register(CalendarTools.class);
 *
 * // Per endpoint — select tools
 * var tools = registry.getTools(Set.of("get_weather", "get_forecast"));
 *
 * // Or get all tools
 * var allTools = registry.allTools();
 * }</pre>
 *
 * @see org.atmosphere.ai.annotation.AiTool
 * @see ToolDefinition
 */
public interface ToolRegistry {

    /**
     * Register a tool definition.
     *
     * @throws IllegalArgumentException if a tool with the same name is already registered
     */
    void register(ToolDefinition tool);

    /**
     * Register all {@link org.atmosphere.ai.annotation.AiTool}-annotated methods
     * on the given object instance.
     *
     * @param toolProvider an object with {@code @AiTool}-annotated methods
     */
    void register(Object toolProvider);

    /**
     * Look up a tool by name.
     */
    Optional<ToolDefinition> getTool(String name);

    /**
     * Get tools matching the given names. Tools not found are silently skipped.
     */
    Collection<ToolDefinition> getTools(Collection<String> names);

    /**
     * Get all registered tools.
     */
    Collection<ToolDefinition> allTools();

    /**
     * Unregister a tool by name.
     *
     * @return true if the tool was found and removed
     */
    boolean unregister(String name);

    /**
     * Execute a tool by name with the given arguments.
     *
     * @param toolName  the tool name
     * @param arguments the arguments from the AI model
     * @return the tool result
     */
    ToolResult execute(String toolName, java.util.Map<String, Object> arguments);

    /**
     * Execute a tool and emit {@link AiEvent.ToolStart} / {@link AiEvent.ToolResult}
     * or {@link AiEvent.ToolError} events through the session. This provides
     * automatic tool call visibility in the frontend without adapters needing
     * to manually emit events.
     *
     * @param toolName  the tool name
     * @param arguments the arguments from the AI model
     * @param session   the streaming session to emit events through
     * @return the tool result
     */
    default ToolResult execute(String toolName, java.util.Map<String, Object> arguments,
                               StreamingSession session) {
        session.emit(new AiEvent.ToolStart(toolName, arguments));
        var result = execute(toolName, arguments);
        if (result.success()) {
            session.emit(new AiEvent.ToolResult(toolName, result.result()));
        } else {
            session.emit(new AiEvent.ToolError(toolName, result.error()));
        }
        return result;
    }
}
