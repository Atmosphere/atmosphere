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
package org.atmosphere.ai;

import java.util.Map;

/**
 * Normalized event model for AI streaming interactions. All AI framework adapters
 * (Spring AI, LangChain4j, Google ADK, Embabel, built-in) translate their native
 * events into these common types, enabling rich real-time UIs that display tool
 * calls, agent steps, structured output fields, and routing decisions.
 *
 * <p>Events are delivered through {@link StreamingSession#emit(AiEvent)} and
 * serialized as JSON frames on the wire:</p>
 * <pre>{@code
 * {"event":"text-delta","data":{"text":"Hello"}, "sessionId":"abc","seq":1}
 * {"event":"tool-start","data":{"toolName":"weather","arguments":{"city":"Montreal"}}, "sessionId":"abc","seq":2}
 * {"event":"tool-result","data":{"toolName":"weather","result":{"temp":22}}, "sessionId":"abc","seq":3}
 * }</pre>
 *
 * <p>Sealed to ensure exhaustive pattern matching in switch expressions.</p>
 *
 * @see StreamingSession#emit(AiEvent)
 */
public sealed interface AiEvent {

    /**
     * Returns the wire protocol event type name (e.g., "text-delta", "tool-start").
     */
    String eventType();

    /**
     * A chunk of streaming text from the LLM.
     *
     * @param text the text chunk (typically a single token or word)
     */
    record TextDelta(String text) implements AiEvent {
        @Override
        public String eventType() {
            return "text-delta";
        }
    }

    /**
     * The full text response is complete.
     *
     * @param fullText the accumulated complete response text
     */
    record TextComplete(String fullText) implements AiEvent {
        @Override
        public String eventType() {
            return "text-complete";
        }
    }

    /**
     * A tool invocation has started.
     *
     * @param toolName  the tool being called
     * @param arguments the arguments passed to the tool
     */
    record ToolStart(String toolName, Map<String, Object> arguments) implements AiEvent {
        public ToolStart {
            arguments = arguments != null ? Map.copyOf(arguments) : Map.of();
        }

        @Override
        public String eventType() {
            return "tool-start";
        }
    }

    /**
     * A tool invocation has completed successfully.
     *
     * @param toolName the tool that was called
     * @param result   the tool result (must be JSON-serializable)
     */
    record ToolResult(String toolName, Object result) implements AiEvent {
        @Override
        public String eventType() {
            return "tool-result";
        }
    }

    /**
     * A tool invocation has failed.
     *
     * @param toolName the tool that was called
     * @param error    the error message
     */
    record ToolError(String toolName, String error) implements AiEvent {
        @Override
        public String eventType() {
            return "tool-error";
        }
    }

    /**
     * An agent has started or completed a step in a multi-step workflow.
     * Adapters for agent runtimes (ADK, Embabel) translate their native
     * step/action events into this common type.
     *
     * @param stepName    the step identifier
     * @param description human-readable description of the step
     * @param data        additional structured data about the step
     */
    record AgentStep(String stepName, String description, Map<String, Object> data) implements AiEvent {
        public AgentStep {
            data = data != null ? Map.copyOf(data) : Map.of();
        }

        @Override
        public String eventType() {
            return "agent-step";
        }
    }

    /**
     * A single field of a structured entity has been parsed from the stream.
     * Enables progressive UI rendering as typed fields arrive.
     *
     * @param fieldName  the field name
     * @param value      the parsed field value
     * @param schemaType the JSON Schema type (e.g., "string", "integer", "boolean")
     */
    record StructuredField(String fieldName, Object value, String schemaType) implements AiEvent {
        @Override
        public String eventType() {
            return "structured-field";
        }
    }

    /**
     * Structured entity streaming has started. Sent before any
     * {@link StructuredField} events for this entity type.
     *
     * @param typeName   the entity class name
     * @param jsonSchema the JSON Schema for the entity
     */
    record EntityStart(String typeName, String jsonSchema) implements AiEvent {
        @Override
        public String eventType() {
            return "entity-start";
        }
    }

    /**
     * A structured entity has been fully assembled from the stream.
     *
     * @param typeName the entity class name
     * @param entity   the complete entity (JSON-serializable)
     */
    record EntityComplete(String typeName, Object entity) implements AiEvent {
        @Override
        public String eventType() {
            return "entity-complete";
        }
    }

    /**
     * The framework has routed a request to a different backend.
     *
     * @param fromBackend the originally selected backend name
     * @param toBackend   the backend the request was routed to
     * @param reason      why the routing decision was made
     */
    record RoutingDecision(String fromBackend, String toBackend, String reason) implements AiEvent {
        @Override
        public String eventType() {
            return "routing-decision";
        }
    }

    /**
     * A progress update during a long-running operation.
     *
     * @param message    human-readable progress message
     * @param percentage completion percentage (0.0 to 1.0), or null if indeterminate
     */
    record Progress(String message, Double percentage) implements AiEvent {
        @Override
        public String eventType() {
            return "progress";
        }
    }

    /**
     * An agent handoff is in progress. Emitted to the client so the UI can
     * display a transition message (e.g., "Transferring to billing...").
     *
     * @param fromAgent  the agent initiating the handoff
     * @param toAgent    the target agent name
     * @param reason     human-readable reason for the handoff (may be null)
     */
    record Handoff(String fromAgent, String toAgent, String reason) implements AiEvent {
        @Override
        public String eventType() {
            return "handoff";
        }
    }

    /**
     * An error occurred during streaming.
     *
     * @param message     human-readable error message
     * @param code        error code for programmatic handling (e.g., "rate_limit", "timeout")
     * @param recoverable whether the client should retry
     */
    record Error(String message, String code, boolean recoverable) implements AiEvent {
        @Override
        public String eventType() {
            return "error";
        }
    }

    /**
     * The streaming interaction has completed.
     *
     * @param summary aggregated final response (may be null)
     * @param usage   usage statistics (tokens, cost, etc.)
     */
    record Complete(String summary, Map<String, Object> usage) implements AiEvent {
        public Complete {
            usage = usage != null ? Map.copyOf(usage) : Map.of();
        }

        @Override
        public String eventType() {
            return "complete";
        }
    }

    /**
     * A tool execution requires human approval before proceeding.
     * The client should render an approve/deny UI and respond with
     * {@code /__approval/<approvalId>/approve} or {@code /__approval/<approvalId>/deny}.
     *
     * @param approvalId unique identifier for this approval request
     * @param toolName   the tool awaiting approval
     * @param arguments  the arguments the LLM wants to pass to the tool
     * @param message    the approval prompt to display to the user
     * @param expiresIn  seconds until this approval expires
     */
    record ApprovalRequired(
            String approvalId,
            String toolName,
            Map<String, Object> arguments,
            String message,
            long expiresIn
    ) implements AiEvent {
        public ApprovalRequired {
            arguments = arguments != null ? Map.copyOf(arguments) : Map.of();
        }

        @Override
        public String eventType() {
            return "approval-required";
        }
    }
}
