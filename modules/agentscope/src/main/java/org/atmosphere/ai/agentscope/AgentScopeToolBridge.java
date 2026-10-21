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
import io.agentscope.core.message.ToolUseBlock;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
        return toToolkit(tools, session, strategy, policy, false);
    }

    /**
     * As {@link #toToolkit(List, StreamingSession, ApprovalStrategy, ToolApprovalPolicy)};
     * with {@code trackDispatch}, each tool remembers the calls that reached
     * it until {@link #reachedTool} is asked about them, so a
     * {@link RejectedToolCallHook} on the same agent can report the calls
     * AgentScope answered without running the tool. Only a toolkit whose agent
     * carries that hook tracks: nothing else would forget the calls.
     */
    static Toolkit toToolkit(
            List<ToolDefinition> tools,
            StreamingSession session,
            ApprovalStrategy strategy,
            ToolApprovalPolicy policy,
            boolean trackDispatch) {
        var toolkit = new Toolkit();
        for (var tool : tools) {
            var agentTool = new AtmosphereAgentScopeTool(tool, session, strategy, policy, trackDispatch);
            toolkit.registerAgentTool(agentTool);
        }
        return toolkit;
    }

    /**
     * Whether {@code agentTool} is one of this bridge's tools that tracks its
     * calls; when it is, whether the call {@code use} reached it (the tool
     * forgets the call either way).
     *
     * @return {@code null} when {@code agentTool} is not a tracking bridge tool
     */
    static Boolean reachedTool(AgentTool agentTool, ToolUseBlock use) {
        if (agentTool instanceof AtmosphereAgentScopeTool bridged && bridged.dispatched != null) {
            return bridged.dispatched.remove(callKey(use));
        }
        return null;
    }

    /** A call's id, or the call itself when the model gave it none. */
    private static Object callKey(ToolUseBlock use) {
        var id = use.getId();
        return id != null && !id.isBlank() ? id : use;
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
            // Shared emitter so the enum / array-items / nested-object facets
            // reach AgentScope with the same fidelity every other bridge sees.
            properties.put(parameter.name(),
                    org.atmosphere.ai.tool.ToolBridgeUtils.parameterSchemaMap(parameter));
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
        /**
         * The calls that reached this tool and that the agent's
         * {@link RejectedToolCallHook} has not asked about yet; {@code null}
         * when not tracking. Bounded by the calls of one round: the hook
         * removes each call once it is acted on.
         */
        private final Set<Object> dispatched;

        private AtmosphereAgentScopeTool(
                ToolDefinition tool,
                StreamingSession session,
                ApprovalStrategy strategy,
                ToolApprovalPolicy policy,
                boolean trackDispatch) {
            this.tool = tool;
            this.session = session;
            this.strategy = strategy;
            this.policy = policy;
            this.dispatched = trackDispatch ? ConcurrentHashMap.newKeySet() : null;
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
            if (dispatched != null && param != null && param.getToolUseBlock() != null) {
                dispatched.add(callKey(param.getToolUseBlock()));
            }
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
