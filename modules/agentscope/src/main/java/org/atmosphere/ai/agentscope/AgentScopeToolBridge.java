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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges Atmosphere {@link ToolDefinition}s into AgentScope's
 * {@link Toolkit}. Every invocation routes through
 * {@link ToolExecutionHelper#executeWithApproval} so HITL approval, argument
 * validation, and tool observability match the other adapters.
 */
public final class AgentScopeToolBridge {

    private AgentScopeToolBridge() {
    }

    public static Toolkit toToolkit(
            List<ToolDefinition> tools,
            StreamingSession session,
            ApprovalStrategy strategy,
            ToolApprovalPolicy policy) {
        var toolkit = new Toolkit();
        for (var tool : tools) {
            var agentTool = new AtmosphereAgentScopeTool(tool, session, strategy, policy);
            toolkit.registerAgentTool(agentTool);
        }
        return toolkit;
    }

    static ToolSchema toSchema(ToolDefinition tool) {
        return ToolSchema.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(toParameters(tool))
                .strict(Boolean.TRUE)
                .build();
    }

    private static Map<String, Object> toParameters(ToolDefinition tool) {
        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        for (var parameter : tool.parameters()) {
            properties.put(parameter.name(), Map.of(
                    "type", parameter.type(),
                    "description", parameter.description()));
            if (parameter.required()) {
                required.add(parameter.name());
            }
        }
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required);
    }

    private static final class AtmosphereAgentScopeTool implements AgentTool {
        private final ToolDefinition tool;
        private final StreamingSession session;
        private final ApprovalStrategy strategy;
        private final ToolApprovalPolicy policy;

        private AtmosphereAgentScopeTool(
                ToolDefinition tool,
                StreamingSession session,
                ApprovalStrategy strategy,
                ToolApprovalPolicy policy) {
            this.tool = tool;
            this.session = session;
            this.strategy = strategy;
            this.policy = policy;
        }

        @Override
        public String getName() {
            return tool.name();
        }

        @Override
        public String getDescription() {
            return tool.description();
        }

        @Override
        public Map<String, Object> getParameters() {
            return toParameters(tool);
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            var input = param == null || param.getInput() == null ? Map.<String, Object>of() : param.getInput();
            var result = ToolExecutionHelper.executeWithApproval(
                    tool.name(), tool, input, session, strategy, policy);
            if (param != null && param.getToolUseBlock() != null) {
                var use = param.getToolUseBlock();
                return Mono.just(ToolResultBlock.text(result).withIdAndName(use.getId(), use.getName()));
            }
            return Mono.just(ToolResultBlock.text(result));
        }
    }
}
