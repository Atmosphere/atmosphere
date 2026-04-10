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
package org.atmosphere.ai.adk;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.atmosphere.ai.tool.ToolParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bridges Atmosphere {@link ToolDefinition} to ADK {@link BaseTool}.
 *
 * <p>ADK handles the tool call loop automatically — when the model requests a tool call,
 * ADK invokes {@link BaseTool#runAsync} and feeds the result back. This bridge wraps our
 * {@link org.atmosphere.ai.tool.ToolExecutor} in ADK's tool contract and routes every
 * invocation through the unified
 * {@link ToolExecutionHelper#executeWithApproval} call site so {@code @RequiresApproval}
 * tools park on Atmosphere's session-scoped HITL gate.</p>
 *
 * <p>ADK's native {@code ToolContext.requestConfirmation(...)} is still emitted as a
 * fire-and-forget side effect so ADK runner listeners see a confirmation event, but the
 * authoritative gate is {@code executeWithApproval}. Tools are built per-request in
 * {@link AdkAgentRuntime#doExecute} so each invocation captures its own
 * {@link StreamingSession} and {@link ApprovalStrategy}.</p>
 */
public final class AdkToolBridge {

    private static final Logger logger = LoggerFactory.getLogger(AdkToolBridge.class);

    private AdkToolBridge() {
    }

    /**
     * Convert Atmosphere tool definitions to ADK BaseTool instances bound to the
     * caller's streaming session and HITL strategy.
     *
     * @param tools    the framework-agnostic tool definitions
     * @param session  the streaming session (for emitting approval events)
     * @param strategy session-scoped HITL gate (may be null — falls back to direct execution)
     * @return ADK tools for registration with {@code LlmAgent.builder().tools(...)}
     */
    public static List<BaseTool> toAdkTools(
            List<ToolDefinition> tools, StreamingSession session, ApprovalStrategy strategy) {
        return tools.stream()
                .map(tool -> (BaseTool) new AtmosphereAdkTool(tool, session, strategy))
                .toList();
    }

    /**
     * ADK BaseTool implementation that delegates to Atmosphere's unified HITL call site.
     */
    private static final class AtmosphereAdkTool extends BaseTool {

        private final ToolDefinition atmosphereTool;
        private final StreamingSession session;
        private final ApprovalStrategy approvalStrategy;

        AtmosphereAdkTool(ToolDefinition tool, StreamingSession session, ApprovalStrategy approvalStrategy) {
            super(tool.name(), tool.description());
            this.atmosphereTool = tool;
            this.session = session;
            this.approvalStrategy = approvalStrategy;
        }

        @Override
        public Optional<FunctionDeclaration> declaration() {
            var builder = FunctionDeclaration.builder()
                    .name(name())
                    .description(description());

            if (!atmosphereTool.parameters().isEmpty()) {
                builder.parameters(buildParameterSchema(atmosphereTool.parameters()));
            }

            return Optional.of(builder.build());
        }

        @Override
        public Single<Map<String, Object>> runAsync(
                Map<String, Object> args, ToolContext toolContext) {
            var safeArgs = args != null ? args : Map.<String, Object>of();

            // Fire ADK's native confirmation event for listeners on the Runner's event stream.
            // This is observability-only; the authoritative gate is executeWithApproval below,
            // so ADK's two-step confirmation protocol is bypassed intentionally and we never
            // wait on toolContext.toolConfirmation() — Atmosphere's ApprovalStrategy is the
            // single source of truth for HITL decisions across all runtimes.
            if (atmosphereTool.requiresApproval() && toolContext != null) {
                try {
                    var hint = atmosphereTool.approvalMessage();
                    toolContext.requestConfirmation(
                            hint != null ? hint : "Approve tool execution?",
                            safeArgs);
                } catch (Exception e) {
                    // Native confirmation emission is best-effort; do not fail the tool call
                    // if ADK's event path is not available.
                    logger.trace("ADK requestConfirmation side-effect failed for {}: {}",
                            name(), e.getMessage());
                }
            }

            try {
                var resultStr = ToolExecutionHelper.executeWithApproval(
                        name(), atmosphereTool, safeArgs, session, approvalStrategy);
                logger.debug("Tool {} executed: {}", name(), resultStr);

                var response = new HashMap<String, Object>();
                response.put("status", "success");
                response.put("result", resultStr);
                return Single.just(response);
            } catch (Exception e) {
                logger.error("Tool {} execution failed", name(), e);
                return Single.just(Map.of(
                        "status", "error",
                        "error", e.getMessage() != null ? e.getMessage() : "Unknown error"
                ));
            }
        }
    }

    private static Schema buildParameterSchema(List<ToolParameter> parameters) {
        var properties = new LinkedHashMap<String, Schema>();
        var required = new java.util.ArrayList<String>();

        for (var param : parameters) {
            properties.put(param.name(), Schema.builder()
                    .type(toAdkType(param.type()))
                    .description(param.description())
                    .build());
            if (param.required()) {
                required.add(param.name());
            }
        }

        return Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(properties)
                .required(required)
                .build();
    }

    private static Type.Known toAdkType(String jsonSchemaType) {
        return switch (jsonSchemaType) {
            case "integer", "number" -> Type.Known.NUMBER;
            case "boolean" -> Type.Known.BOOLEAN;
            case "array" -> Type.Known.ARRAY;
            case "object" -> Type.Known.OBJECT;
            default -> Type.Known.STRING;
        };
    }
}
