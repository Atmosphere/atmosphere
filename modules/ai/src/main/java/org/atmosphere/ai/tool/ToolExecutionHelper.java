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

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalRegistry;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared helper for executing Atmosphere tools and formatting results.
 * Extracted from the duplicated try/catch/log/format pattern found in
 * {@code SpringAiToolBridge}, {@code LangChain4jToolBridge}, and
 * {@code AdkToolBridge}.
 *
 * <p>Each adapter's tool bridge still handles the framework-specific
 * wrapping (Spring AI {@code ToolCallback}, LangChain4j
 * {@code ToolExecutionResultMessage}, ADK {@code Single<Map>}), but
 * this helper provides the common execution and formatting logic.</p>
 */
public final class ToolExecutionHelper {

    private static final Logger logger = LoggerFactory.getLogger(ToolExecutionHelper.class);

    private ToolExecutionHelper() {
    }

    /**
     * Execute a tool and return the result as a string, without argument
     * validation. Prefer {@link #executeAndFormat(ToolDefinition, Map)} for
     * new callers so {@link ToolArgumentValidator} runs at the boundary.
     *
     * <p>On success, returns the result's {@code toString()} representation
     * (or {@code "null"} if the result is null). On failure, returns a JSON
     * error object with the exception message.</p>
     *
     * @param toolName the tool name (for logging)
     * @param executor the tool executor
     * @param args     the arguments to pass to the executor
     * @return the result string, or a JSON error object on failure
     */
    public static String executeAndFormat(String toolName, ToolExecutor executor,
                                          Map<String, Object> args) {
        try {
            var result = executor.execute(args);
            logger.debug("Tool {} executed: {}", toolName, result);
            return result != null ? result.toString() : "null";
        } catch (Exception e) {
            logger.error("Tool {} execution failed", toolName, e);
            return "{\"error\":\"" + ToolBridgeUtils.escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * Execute a tool with schema validation against the declared
     * {@link ToolDefinition#parameters()}. On validation failure, returns a
     * structured JSON error so the LLM can retry with corrected arguments
     * rather than receiving a runtime exception.
     *
     * <p>This is the uniform entry point for Phase 10 (Boundary Safety /
     * Correctness Invariant #4). Runtime bridges should call this method
     * rather than {@link #executeAndFormat(String, ToolExecutor, Map)} so
     * malformed LLM-supplied arguments are caught once at the shared seam
     * instead of varying per framework.</p>
     *
     * @param tool the tool definition (schema source)
     * @param args the arguments to pass to the executor
     * @return the result string, a validation error JSON, or an execution error JSON
     */
    public static String executeAndFormat(ToolDefinition tool, Map<String, Object> args) {
        var errors = ToolArgumentValidator.validate(tool, args);
        if (!errors.isEmpty()) {
            logger.info("Tool {} argument validation failed: {}", tool.name(), errors);
            return buildValidationErrorJson(tool.name(), errors);
        }
        return executeAndFormat(tool.name(), tool.executor(), args);
    }

    /**
     * Execute a tool with approval gate support. If the tool definition has
     * {@link ToolDefinition#requiresApproval()}, the execution pauses (parks
     * the virtual thread) until the client approves or denies. Before the
     * approval gate (and before any executor invocation) arguments are
     * validated against the tool's declared parameter schema; a validation
     * failure returns a structured JSON error without waking the approver
     * or firing the executor.
     *
     * @param toolName the tool name
     * @param tool     the tool definition
     * @param args     the arguments
     * @param session  the streaming session (for emitting approval events)
     * @param strategy the approval strategy (may be null if no approval support)
     * @return the result string, or a cancellation/timeout/validation error JSON
     */
    public static String executeWithApproval(String toolName, ToolDefinition tool,
                                             Map<String, Object> args,
                                             StreamingSession session,
                                             ApprovalStrategy strategy) {
        return executeWithApproval(toolName, tool, args, session, strategy, null);
    }

    /**
     * Policy-aware variant of {@link #executeWithApproval(String, ToolDefinition, Map, StreamingSession, ApprovalStrategy)}.
     * Phase 6 of the unified {@code @Agent} API: consult the session-scoped
     * {@link ToolApprovalPolicy} before deciding whether to gate the invocation.
     * The policy replaces the hardcoded {@code tool.requiresApproval()} check,
     * so callers can force-allow trusted tools, force-deny every tool
     * (shadow/preview mode), or supply a runtime predicate without touching
     * the {@code @AiTool} annotations.
     *
     * <p>When {@code policy} is {@code null}, falls back to
     * {@link ToolApprovalPolicy#annotated()} — identical behavior to the
     * legacy 5-arg overload.</p>
     *
     * @param toolName the tool name
     * @param tool     the tool definition
     * @param args     the arguments
     * @param session  the streaming session (for emitting approval events)
     * @param strategy the approval strategy (may be null if no approval support)
     * @param policy   the approval policy (may be null to default to annotated)
     * @return the result string, or a cancellation/timeout/validation error JSON
     */
    public static String executeWithApproval(String toolName, ToolDefinition tool,
                                             Map<String, Object> args,
                                             StreamingSession session,
                                             ApprovalStrategy strategy,
                                             ToolApprovalPolicy policy) {
        var errors = ToolArgumentValidator.validate(tool, args);
        if (!errors.isEmpty()) {
            logger.info("Tool {} argument validation failed: {}", toolName, errors);
            return buildValidationErrorJson(toolName, errors);
        }
        var effectivePolicy = policy != null ? policy : ToolApprovalPolicy.annotated();
        // Fast-path: policy says no approval needed for this tool → execute directly.
        if (!effectivePolicy.requiresApproval(tool)) {
            return executeAndFormat(toolName, tool.executor(), args);
        }
        // DenyAll is evaluated BEFORE the strategy-null fall-through so that
        // a null strategy cannot bypass a deny-by-policy decision. Previously
        // {@code !requiresApproval || strategy == null} short-circuited here
        // and let DenyAll-flagged tools execute unguarded whenever no
        // ApprovalStrategy was wired — a Security Invariant #6 (default deny)
        // violation.
        if (effectivePolicy instanceof ToolApprovalPolicy.DenyAll) {
            logger.info("Tool {} blocked by DenyAll policy", toolName);
            return "{\"status\":\"cancelled\",\"message\":\"Tool execution denied by policy\"}";
        }
        // Fail-closed when a tool needs approval but no strategy is available.
        // The prior behaviour was fail-open (execute unguarded), which let
        // {@code @RequiresApproval} tools run on any code path that forgot to
        // wire an ApprovalStrategy (channel bridge, @Coordinator, ad-hoc
        // helper invocations). Fail-closed surfaces the misconfiguration at
        // the point of use and protects the gate even when the plumbing is
        // incomplete.
        if (strategy == null) {
            logger.warn("Tool {} requires approval but no ApprovalStrategy is wired — "
                    + "failing closed. Wire an ApprovalStrategy on the pipeline/session "
                    + "to honor @RequiresApproval tools.", toolName);
            return "{\"status\":\"cancelled\",\"message\":\"Tool requires approval but no "
                    + "ApprovalStrategy is configured on this execution path\"}";
        }

        var timeout = tool.approvalTimeout() > 0 ? tool.approvalTimeout() : 300;
        var approval = new PendingApproval(
                ApprovalRegistry.generateId(),
                toolName,
                args,
                tool.approvalMessage(),
                session.sessionId(),
                Instant.now().plusSeconds(timeout)
        );

        var outcome = strategy.awaitApproval(approval, session);
        return switch (outcome) {
            case APPROVED -> {
                logger.info("Tool {} approved, executing", toolName);
                yield executeAndFormat(toolName, tool.executor(), args);
            }
            case DENIED -> {
                logger.info("Tool {} denied by user", toolName);
                yield "{\"status\":\"cancelled\",\"message\":\"Action cancelled by user\"}";
            }
            case TIMED_OUT -> {
                logger.info("Tool {} approval timed out", toolName);
                yield "{\"status\":\"timeout\",\"message\":\"Approval timed out\"}";
            }
        };
    }

    /**
     * Build a tool map from a list of definitions for quick lookup by name.
     *
     * @param tools the tool definitions
     * @return a map keyed by tool name
     */
    public static Map<String, ToolDefinition> toToolMap(List<ToolDefinition> tools) {
        var map = new HashMap<String, ToolDefinition>();
        for (var tool : tools) {
            map.put(tool.name(), tool);
        }
        return map;
    }

    /**
     * Serialize validator errors as a structured JSON object the LLM can parse
     * and use to retry with corrected arguments. Keeping the shape uniform
     * across every runtime bridge closes Correctness Invariant #4 (Boundary
     * Safety) — no framework-specific error shapes leak to the model.
     */
    private static String buildValidationErrorJson(String toolName, List<String> errors) {
        var details = new StringBuilder();
        for (int i = 0; i < errors.size(); i++) {
            if (i > 0) {
                details.append(',');
            }
            details.append('"').append(ToolBridgeUtils.escapeJson(errors.get(i))).append('"');
        }
        return "{\"error\":\"invalid_arguments\",\"tool\":\""
                + ToolBridgeUtils.escapeJson(toolName)
                + "\",\"details\":[" + details + "]}";
    }
}
