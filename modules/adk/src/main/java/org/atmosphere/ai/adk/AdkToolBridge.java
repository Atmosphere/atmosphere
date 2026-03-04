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
import org.atmosphere.ai.tool.ToolDefinition;
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
 * <p>ADK handles the tool call loop automatically — when the model
 * requests a tool call, ADK invokes {@link BaseTool#runAsync} and
 * feeds the result back. This bridge wraps our
 * {@link org.atmosphere.ai.tool.ToolExecutor} in ADK's tool contract.</p>
 *
 * <p>Usage with agent builder:</p>
 * <pre>{@code
 * var adkTools = AdkToolBridge.toAdkTools(toolDefinitions);
 * var agent = LlmAgent.builder()
 *     .name("assistant")
 *     .model("gemini-2.0-flash")
 *     .tools(adkTools.toArray(new BaseTool[0]))
 *     .build();
 * }</pre>
 */
public final class AdkToolBridge {

    private static final Logger logger = LoggerFactory.getLogger(AdkToolBridge.class);

    private AdkToolBridge() {
    }

    /**
     * Convert Atmosphere tool definitions to ADK BaseTool instances.
     *
     * @param tools the framework-agnostic tool definitions
     * @return ADK tools for registration with {@code LlmAgent.builder().tools(...)}
     */
    public static List<BaseTool> toAdkTools(List<ToolDefinition> tools) {
        return tools.stream()
                .map(AdkToolBridge::toAdkTool)
                .toList();
    }

    private static BaseTool toAdkTool(ToolDefinition tool) {
        return new AtmosphereAdkTool(tool);
    }

    /**
     * ADK BaseTool implementation that delegates to an Atmosphere ToolExecutor.
     */
    private static final class AtmosphereAdkTool extends BaseTool {

        private final ToolDefinition atmosphereTool;

        AtmosphereAdkTool(ToolDefinition tool) {
            super(tool.name(), tool.description());
            this.atmosphereTool = tool;
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
            try {
                var result = atmosphereTool.executor().execute(args != null ? args : Map.of());
                logger.debug("Tool {} executed: {}", name(), result);

                var response = new HashMap<String, Object>();
                response.put("status", "success");
                if (result instanceof Map) {
                    @SuppressWarnings("unchecked")
                    var mapResult = (Map<String, Object>) result;
                    response.putAll(mapResult);
                } else {
                    response.put("result", result != null ? result.toString() : "null");
                }
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
