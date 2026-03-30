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

import java.util.List;

/**
 * Framework-agnostic definition of an AI-callable tool. This is the common
 * representation that adapters translate to their native tool format
 * (LangChain4j {@code ToolSpecification}, Spring AI function callbacks,
 * ADK {@code BaseTool}, etc.).
 *
 * <p>Created automatically by scanning {@link org.atmosphere.ai.annotation.AiTool}
 * annotations, or manually via the builder.</p>
 *
 * @param name            unique tool name (snake_case convention)
 * @param description     human-readable description for the model
 * @param parameters      ordered list of parameter definitions
 * @param returnType      the JSON Schema type of the return value
 * @param executor        the function that executes the tool
 * @param approvalMessage if non-null, this tool requires human approval before execution
 * @param approvalTimeout approval timeout in seconds (0 = use default)
 */
public record ToolDefinition(
        String name,
        String description,
        List<ToolParameter> parameters,
        String returnType,
        ToolExecutor executor,
        String approvalMessage,
        long approvalTimeout
) {
    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be null or blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("tool description must not be null or blank");
        }
        parameters = List.copyOf(parameters);
    }

    /**
     * Create a builder for a tool definition.
     */
    public static Builder builder(String name, String description) {
        return new Builder(name, description);
    }

    /** Check if this tool requires human approval before execution. */
    public boolean requiresApproval() {
        return approvalMessage != null && !approvalMessage.isBlank();
    }

    public static final class Builder {
        private final String name;
        private final String description;
        private final java.util.ArrayList<ToolParameter> parameters = new java.util.ArrayList<>();
        private String returnType = "string";
        private ToolExecutor executor;
        private String approvalMessage;
        private long approvalTimeout;

        private Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public Builder parameter(String paramName, String paramDescription, String type, boolean required) {
            parameters.add(new ToolParameter(paramName, paramDescription, type, required));
            return this;
        }

        public Builder parameter(String paramName, String paramDescription, String type) {
            return parameter(paramName, paramDescription, type, true);
        }

        public Builder returnType(String returnType) {
            this.returnType = returnType;
            return this;
        }

        public Builder executor(ToolExecutor executor) {
            this.executor = executor;
            return this;
        }

        public Builder requiresApproval(String message) {
            this.approvalMessage = message;
            return this;
        }

        public Builder requiresApproval(String message, long timeoutSeconds) {
            this.approvalMessage = message;
            this.approvalTimeout = timeoutSeconds;
            return this;
        }

        public ToolDefinition build() {
            if (executor == null) {
                throw new IllegalStateException("executor must be set");
            }
            return new ToolDefinition(name, description, parameters, returnType,
                    executor, approvalMessage, approvalTimeout);
        }
    }
}
