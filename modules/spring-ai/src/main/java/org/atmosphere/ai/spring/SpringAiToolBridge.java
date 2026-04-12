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
package org.atmosphere.ai.spring;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.tool.ToolBridgeUtils;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.atmosphere.ai.tool.ToolParameter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * Bridges Atmosphere {@link ToolDefinition} to Spring AI {@link ToolCallback}.
 *
 * <p>Spring AI handles the tool call loop automatically — when the model
 * requests a tool call, it invokes the callback and feeds the result back.
 * This bridge wraps our {@link org.atmosphere.ai.tool.ToolExecutor} in Spring AI's
 * callback contract and routes every invocation through
 * {@link ToolExecutionHelper#executeWithApproval} so {@code @RequiresApproval}
 * tools park the virtual thread on the session-scoped HITL gate.</p>
 *
 * <p>Callbacks are created per request (capturing the caller's
 * {@link StreamingSession} and {@link ApprovalStrategy} in their fields) because
 * Spring AI's {@code ToolCallback.call(String)} contract carries no context —
 * threading the session via a ThreadLocal would be fragile across reactive
 * publishers, so we just instantiate fresh callbacks inside
 * {@code SpringAiAgentRuntime.doExecute()}.</p>
 */
public final class SpringAiToolBridge {

    private SpringAiToolBridge() {
    }

    /**
     * Convert Atmosphere tool definitions to Spring AI tool callbacks bound to a
     * session-scoped HITL gate.
     *
     * @param tools    the framework-agnostic tool definitions
     * @param session  the streaming session (for emitting approval events)
     * @param strategy session-scoped HITL gate (may be null — falls back to direct execution)
     * @return Spring AI callbacks ready for {@code promptSpec.toolCallbacks(...)}
     */
    public static List<ToolCallback> toToolCallbacks(
            List<ToolDefinition> tools, StreamingSession session, ApprovalStrategy strategy,
            List<org.atmosphere.ai.AgentLifecycleListener> listeners,
            org.atmosphere.ai.approval.ToolApprovalPolicy policy) {
        return tools.stream()
                .map(tool -> toToolCallback(tool, session, strategy, listeners, policy))
                .toList();
    }

    private static ToolCallback toToolCallback(
            ToolDefinition tool, StreamingSession session, ApprovalStrategy strategy,
            List<org.atmosphere.ai.AgentLifecycleListener> listeners,
            org.atmosphere.ai.approval.ToolApprovalPolicy policy) {
        var springToolDef = DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(buildInputSchema(tool.parameters()))
                .build();

        return new AtmosphereToolCallback(springToolDef, tool, session, strategy, listeners, policy);
    }

    /**
     * Build a JSON Schema string from the parameter list.
     * Spring AI expects the inputSchema as a JSON string.
     *
     * <p>Delegates to {@link ToolBridgeUtils#buildJsonSchemaString(List)}.</p>
     */
    static String buildInputSchema(List<ToolParameter> parameters) {
        return ToolBridgeUtils.buildJsonSchemaString(parameters);
    }

    /**
     * Minimal JSON object parser for tool arguments.
     * Spring AI passes a JSON string like {"key":"value","num":42}.
     *
     * <p>Delegates to {@link ToolBridgeUtils#parseJsonArgs(String)}.</p>
     */
    static Map<String, Object> parseJsonArgs(String json) {
        return ToolBridgeUtils.parseJsonArgs(json);
    }

    /**
     * ToolCallback implementation that delegates to an Atmosphere ToolExecutor,
     * honouring the session-scoped HITL gate.
     */
    private static final class AtmosphereToolCallback implements ToolCallback {

        private final org.springframework.ai.tool.definition.ToolDefinition springToolDef;
        private final ToolDefinition atmosphereTool;
        private final StreamingSession session;
        private final ApprovalStrategy approvalStrategy;
        private final List<org.atmosphere.ai.AgentLifecycleListener> listeners;
        private final org.atmosphere.ai.approval.ToolApprovalPolicy approvalPolicy;

        AtmosphereToolCallback(
                org.springframework.ai.tool.definition.ToolDefinition springToolDef,
                ToolDefinition atmosphereTool,
                StreamingSession session,
                ApprovalStrategy approvalStrategy,
                List<org.atmosphere.ai.AgentLifecycleListener> listeners,
                org.atmosphere.ai.approval.ToolApprovalPolicy approvalPolicy) {
            this.springToolDef = springToolDef;
            this.atmosphereTool = atmosphereTool;
            this.session = session;
            this.approvalStrategy = approvalStrategy;
            this.listeners = listeners != null ? listeners : List.of();
            this.approvalPolicy = approvalPolicy;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return springToolDef;
        }

        @Override
        public String call(String toolInput) {
            Map<String, Object> args = ToolBridgeUtils.parseJsonArgs(toolInput);
            org.atmosphere.ai.AgentLifecycleListener.fireToolCall(
                    listeners, atmosphereTool.name(), args);
            var result = ToolExecutionHelper.executeWithApproval(
                    atmosphereTool.name(), atmosphereTool, args, session, approvalStrategy,
                    approvalPolicy);
            org.atmosphere.ai.AgentLifecycleListener.fireToolResult(
                    listeners, atmosphereTool.name(), result);
            return result;
        }
    }
}
