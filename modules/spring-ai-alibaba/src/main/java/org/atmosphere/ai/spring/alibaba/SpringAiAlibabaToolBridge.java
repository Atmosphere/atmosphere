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
package org.atmosphere.ai.spring.alibaba;

import org.atmosphere.ai.AgentLifecycleListener;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.atmosphere.ai.tool.ToolBridgeUtils;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * Bridges Atmosphere tools to Spring AI callbacks consumed by Spring AI
 * Alibaba's {@code ReactAgent.Builder.tools(...)} surface.
 */
public final class SpringAiAlibabaToolBridge {

    private SpringAiAlibabaToolBridge() {
    }

    public static List<ToolCallback> toToolCallbacks(
            List<ToolDefinition> tools,
            StreamingSession session,
            ApprovalStrategy strategy,
            List<AgentLifecycleListener> listeners,
            ToolApprovalPolicy policy) {
        return tools.stream()
                .map(tool -> toToolCallback(tool, session, strategy, listeners, policy))
                .toList();
    }

    private static ToolCallback toToolCallback(
            ToolDefinition tool,
            StreamingSession session,
            ApprovalStrategy strategy,
            List<AgentLifecycleListener> listeners,
            ToolApprovalPolicy policy) {
        var springToolDef = DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(ToolBridgeUtils.buildJsonSchemaString(tool.parameters()))
                .build();
        return new AtmosphereToolCallback(springToolDef, tool, session, strategy, listeners, policy);
    }

    private static final class AtmosphereToolCallback implements ToolCallback {
        private final org.springframework.ai.tool.definition.ToolDefinition springToolDef;
        private final ToolDefinition atmosphereTool;
        private final StreamingSession session;
        private final ApprovalStrategy approvalStrategy;
        private final List<AgentLifecycleListener> listeners;
        private final ToolApprovalPolicy approvalPolicy;

        private AtmosphereToolCallback(
                org.springframework.ai.tool.definition.ToolDefinition springToolDef,
                ToolDefinition atmosphereTool,
                StreamingSession session,
                ApprovalStrategy approvalStrategy,
                List<AgentLifecycleListener> listeners,
                ToolApprovalPolicy approvalPolicy) {
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
            AgentLifecycleListener.fireToolCall(listeners, atmosphereTool.name(), args);
            var result = ToolExecutionHelper.executeWithApproval(
                    atmosphereTool.name(), atmosphereTool, args, session,
                    approvalStrategy, approvalPolicy);
            AgentLifecycleListener.fireToolResult(listeners, atmosphereTool.name(), result);
            return result;
        }
    }
}
