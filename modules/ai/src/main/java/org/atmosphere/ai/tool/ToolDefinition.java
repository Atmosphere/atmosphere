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
 * @param executionTimeout per-tool execution bound in seconds (0 = use the
 *                        framework default, negative = unbounded)
 * @param kind            behavioural category used by the outer
 *                        {@link org.atmosphere.ai.identity.PermissionMode}
 *                        (e.g. {@code ACCEPT_EDITS} auto-approves
 *                        {@link ToolKind#EDIT} tools); never {@code null}
 */
public record ToolDefinition(
        String name,
        String description,
        List<ToolParameter> parameters,
        String returnType,
        ToolExecutor executor,
        String approvalMessage,
        long approvalTimeout,
        long executionTimeout,
        ToolKind kind
) {
    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be null or blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("tool description must not be null or blank");
        }
        parameters = List.copyOf(parameters);
        if (kind == null) {
            kind = ToolKind.OTHER;
        }
    }

    /**
     * Backwards-compatible constructor for callers predating the {@code kind}
     * component. Defaults the tool kind to {@link ToolKind#OTHER} and the
     * execution bound to the framework default.
     */
    public ToolDefinition(String name, String description, List<ToolParameter> parameters,
                          String returnType, ToolExecutor executor, String approvalMessage,
                          long approvalTimeout) {
        this(name, description, parameters, returnType, executor, approvalMessage,
                approvalTimeout, 0L, ToolKind.OTHER);
    }

    /**
     * Backwards-compatible constructor for callers predating the
     * {@code executionTimeout} component. Defaults it to the framework-wide
     * execution bound.
     */
    public ToolDefinition(String name, String description, List<ToolParameter> parameters,
                          String returnType, ToolExecutor executor, String approvalMessage,
                          long approvalTimeout, ToolKind kind) {
        this(name, description, parameters, returnType, executor, approvalMessage,
                approvalTimeout, 0L, kind);
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
        private long executionTimeout;
        private ToolKind kind = ToolKind.OTHER;

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

        /**
         * Bound this tool's execution to {@code seconds}. A tool that exceeds
         * it is abandoned and the model receives a structured timeout error
         * instead of the agent turn hanging (Correctness Invariant #3 —
         * model-chosen tool calls are external input and must not block
         * unbounded). {@code 0} uses the framework default
         * ({@code org.atmosphere.ai.toolExecutionTimeout}); a negative value
         * disables the bound for this tool.
         *
         * @param seconds the execution bound in seconds
         * @return this builder
         */
        public Builder executionTimeout(long seconds) {
            this.executionTimeout = seconds;
            return this;
        }

        public Builder requiresApproval(String message, long timeoutSeconds) {
            this.approvalMessage = message;
            this.approvalTimeout = timeoutSeconds;
            return this;
        }

        /** Set the tool's behavioural category. Defaults to {@link ToolKind#OTHER}. */
        public Builder kind(ToolKind kind) {
            this.kind = kind == null ? ToolKind.OTHER : kind;
            return this;
        }

        public ToolDefinition build() {
            if (executor == null) {
                throw new IllegalStateException("executor must be set");
            }
            return new ToolDefinition(name, description, parameters, returnType,
                    executor, approvalMessage, approvalTimeout, executionTimeout, kind);
        }
    }
}
