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

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalRegistry;
import org.atmosphere.ai.approval.ApprovalResolution;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.atmosphere.ai.resume.DurableRunContext;
import org.atmosphere.ai.resume.DurableRunScopeHolder;
import org.atmosphere.ai.resume.EffectJournal;
import org.atmosphere.ai.resume.EffectKeys;
import org.atmosphere.ai.resume.EffectKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /** Serializes reviewer-supplied structured approval responses to JSON. */
    private static final ObjectMapper RESPONSE_JSON = JsonMapper.builder().build();

    /**
     * Default tool-output offload threshold, in characters. A tool result
     * longer than this is written in full to the agent workspace and only a
     * truncated preview is returned to the model, keeping large outputs out of
     * the context window (deepagents-style disk offload). Overridable via
     * {@link #TOOL_OUTPUT_OFFLOAD_THRESHOLD_PROPERTY} /
     * {@link #TOOL_OUTPUT_OFFLOAD_THRESHOLD_ENV}; a resolved value {@code <= 0}
     * disables offload entirely.
     */
    public static final int DEFAULT_TOOL_OUTPUT_OFFLOAD_THRESHOLD = 8000;

    /**
     * System property overriding {@link #DEFAULT_TOOL_OUTPUT_OFFLOAD_THRESHOLD}.
     * Resolved sysprop-first, then {@link #TOOL_OUTPUT_OFFLOAD_THRESHOLD_ENV},
     * mirroring {@code AiConfig}'s dual-resolution knobs.
     */
    public static final String TOOL_OUTPUT_OFFLOAD_THRESHOLD_PROPERTY =
            "org.atmosphere.ai.toolOutputOffloadThreshold";

    /**
     * Environment variable overriding {@link #DEFAULT_TOOL_OUTPUT_OFFLOAD_THRESHOLD}.
     * See {@link #TOOL_OUTPUT_OFFLOAD_THRESHOLD_PROPERTY}.
     */
    public static final String TOOL_OUTPUT_OFFLOAD_THRESHOLD_ENV =
            "LLM_TOOL_OUTPUT_OFFLOAD_THRESHOLD";

    /**
     * Default per-tool execution bound, in seconds. A tool call is external
     * input — the model chooses it — so an unbounded blocking executor hangs
     * the agent turn forever (Correctness Invariant #3). The default matches
     * the coordinator's {@code AgentLimits.DEFAULT} 120s so a tool bound and
     * an agent-dispatch bound are the same order of magnitude. Override per
     * tool via {@link ToolDefinition.Builder#executionTimeout(long)}, globally
     * via {@link #TOOL_EXECUTION_TIMEOUT_PROPERTY} /
     * {@link #TOOL_EXECUTION_TIMEOUT_ENV}; a resolved value {@code <= 0}
     * disables the bound and restores the pure inline call.
     */
    public static final long DEFAULT_TOOL_EXECUTION_TIMEOUT_SECONDS = 120L;

    /**
     * System property overriding {@link #DEFAULT_TOOL_EXECUTION_TIMEOUT_SECONDS}.
     * Resolved sysprop-first, then {@link #TOOL_EXECUTION_TIMEOUT_ENV},
     * mirroring the offload-threshold knobs.
     */
    public static final String TOOL_EXECUTION_TIMEOUT_PROPERTY =
            "org.atmosphere.ai.toolExecutionTimeout";

    /**
     * Environment variable overriding {@link #DEFAULT_TOOL_EXECUTION_TIMEOUT_SECONDS}.
     * See {@link #TOOL_EXECUTION_TIMEOUT_PROPERTY}.
     */
    public static final String TOOL_EXECUTION_TIMEOUT_ENV = "LLM_TOOL_EXECUTION_TIMEOUT";

    /** Characters of the full result kept in the preview returned to the model when offloading. */
    private static final int OFFLOAD_PREVIEW_CHARS = 1500;

    /** Characters not permitted in an offload file name (replaced with {@code _}). */
    private static final java.util.regex.Pattern UNSAFE_OFFLOAD_NAME_CHARS =
            java.util.regex.Pattern.compile("[^A-Za-z0-9_.-]");

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
        return executeAndFormat(toolName, executor, args, Map.of());
    }

    /**
     * Injectables-aware variant of {@link #executeAndFormat(String, ToolExecutor, Map)}.
     * Passes framework-scoped instances ({@code StreamingSession},
     * {@code AgentFleet}, {@code AtmosphereResource}, ...) to the executor so
     * {@code @AiTool} methods can declare them as parameters and receive the
     * live instances without a {@link ThreadLocal} shim. Existing executors
     * unaware of injectables fall through to the legacy 1-arg
     * {@link ToolExecutor#execute(Map)} via the default method.
     */
    public static String executeAndFormat(String toolName, ToolExecutor executor,
                                          Map<String, Object> args,
                                          Map<Class<?>, Object> injectables) {
        return executeAndFormat(toolName, executor, args, injectables, 0L);
    }

    /**
     * Execution-bounded variant. {@code toolExecutionTimeout} is the tool's own
     * {@link ToolDefinition#executionTimeout()} (0 = framework default,
     * negative = unbounded); the effective bound is resolved by
     * {@link #resolveExecutionTimeout(long)}.
     */
    static String executeAndFormat(String toolName, ToolExecutor executor,
                                   Map<String, Object> args,
                                   Map<Class<?>, Object> injectables,
                                   long toolExecutionTimeout) {
        var scope = injectables != null ? injectables : Map.<Class<?>, Object>of();
        var timeoutSeconds = resolveExecutionTimeout(toolExecutionTimeout);
        if (timeoutSeconds <= 0) {
            // Bound disabled: byte-identical inline call on the caller thread,
            // preserving thread affinity for executors that rely on it.
            return invokeAndFormat(toolName, executor, args, scope);
        }
        return invokeBounded(toolName, executor, args, scope, timeoutSeconds);
    }

    /** The raw executor invocation + result/error formatting, no time bound. */
    private static String invokeAndFormat(String toolName, ToolExecutor executor,
                                          Map<String, Object> args,
                                          Map<Class<?>, Object> scope) {
        try {
            var result = executor.execute(args, scope);
            logger.debug("Tool {} executed: {}", toolName, result);
            return result != null ? result.toString() : "null";
        } catch (Exception e) {
            logger.error("Tool {} execution failed", toolName, e);
            return "{\"error\":\"" + ToolBridgeUtils.escapeJson(errorMessage(e)) + "\"}";
        }
    }

    /**
     * Run the executor on a per-call virtual thread bounded by
     * {@code timeoutSeconds}. A tool that overruns is abandoned (interrupted)
     * and the model receives a structured {@code tool_timeout} error, so the
     * agent loop continues instead of hanging forever on a blocking executor
     * (Correctness Invariant #3).
     *
     * <p>Mirrors the coordinator's proven bounded-dispatch mechanism: cancel
     * plus {@code shutdownNow()} <em>while the executor is live</em> so the
     * interrupt actually reaches the blocked worker, an interruptible await so
     * an outer cancellation propagates inward, and a relay of a
     * worker-observed interrupt back to the calling thread so a cancel raised
     * inside the tool body is not swallowed by the thread hop.</p>
     */
    private static String invokeBounded(String toolName, ToolExecutor executor,
                                        Map<String, Object> args,
                                        Map<Class<?>, Object> scope,
                                        long timeoutSeconds) {
        var vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var workerInterrupted = new java.util.concurrent.atomic.AtomicBoolean();
            var future = CompletableFuture.supplyAsync(() -> {
                try {
                    return invokeAndFormat(toolName, executor, args, scope);
                } finally {
                    if (Thread.currentThread().isInterrupted()) {
                        workerInterrupted.set(true);
                    }
                }
            }, vtExecutor).orTimeout(timeoutSeconds, TimeUnit.SECONDS);
            try {
                var result = future.get();
                if (workerInterrupted.get()) {
                    Thread.currentThread().interrupt();
                }
                return result;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                vtExecutor.shutdownNow();
                return "{\"error\":\"tool_interrupted\",\"tool\":\""
                        + ToolBridgeUtils.escapeJson(toolName) + "\"}";
            } catch (ExecutionException e) {
                if (e.getCause() instanceof TimeoutException) {
                    future.cancel(true);
                    vtExecutor.shutdownNow();
                    logger.warn("Tool {} exceeded its {}s execution bound and was abandoned. "
                            + "Raise it per tool via ToolDefinition.executionTimeout, globally "
                            + "via {}, or set a value <= 0 to disable the bound.",
                            toolName, timeoutSeconds, TOOL_EXECUTION_TIMEOUT_PROPERTY);
                    return "{\"error\":\"tool_timeout\",\"tool\":\""
                            + ToolBridgeUtils.escapeJson(toolName)
                            + "\",\"timeoutSeconds\":" + timeoutSeconds + "}";
                }
                // invokeAndFormat catches Exception itself, so a throw here is
                // an Error or a JVM-level failure — surface it as a tool error
                // rather than propagating into the runtime bridge.
                var cause = e.getCause() != null ? e.getCause() : e;
                logger.error("Tool {} execution failed", toolName, cause);
                return "{\"error\":\"" + ToolBridgeUtils.escapeJson(errorMessage(cause)) + "\"}";
            }
        } finally {
            // Non-blocking teardown on every terminal path; shutdownNow never
            // awaits, so it cannot re-introduce the hang this guards against.
            vtExecutor.shutdownNow();
        }
    }

    /**
     * Resolve the effective execution bound: the tool's own value when
     * positive, else the {@link #TOOL_EXECUTION_TIMEOUT_PROPERTY} system
     * property, else {@link #TOOL_EXECUTION_TIMEOUT_ENV}, else
     * {@link #DEFAULT_TOOL_EXECUTION_TIMEOUT_SECONDS}. A negative tool value
     * disables the bound for that tool; a resolved global value {@code <= 0}
     * disables it everywhere. Parsing is lenient — a malformed value falls back
     * to the default rather than throwing (Correctness Invariant #4).
     */
    private static long resolveExecutionTimeout(long toolExecutionTimeout) {
        if (toolExecutionTimeout != 0) {
            return toolExecutionTimeout;
        }
        var raw = System.getProperty(TOOL_EXECUTION_TIMEOUT_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(TOOL_EXECUTION_TIMEOUT_ENV);
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TOOL_EXECUTION_TIMEOUT_SECONDS;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            logger.warn("Ignoring malformed tool execution timeout '{}' (expected seconds)", raw);
            return DEFAULT_TOOL_EXECUTION_TIMEOUT_SECONDS;
        }
    }

    /**
     * Return a non-null, useful error description for a thrown exception.
     * Falls back to the simple class name when {@link Throwable#getMessage()}
     * is null or blank — NPEs and other no-message throws would otherwise
     * surface to the model as {@code "error":"null"}, which is opaque on
     * the wire and hides the actual failure class from observers.
     */
    private static String errorMessage(Throwable t) {
        var msg = t.getMessage();
        if (msg != null && !msg.isBlank()) {
            return msg;
        }
        return t.getClass().getSimpleName();
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
        return executeAndFormat(tool.name(), tool.executor(), args, Map.of(),
                tool.executionTimeout());
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
        // The session's own injectables ARE the tool scope on the runtime
        // bridge paths: every bridge funnels through this overload, and the
        // pipeline threads the harness stores (AgentPlanStore,
        // AgentFileSystemProvider, ...) into the session via
        // ToolInjectablesSession. Dropping them here left the builtin
        // plan/file tool floors dead on every non-builtin runtime while the
        // console reported ACTIVE(builtin) (Invariants #5/#7).
        return executeWithApproval(toolName, tool, args, session, strategy, policy,
                session != null ? session.injectables() : Map.of());
    }

    /**
     * Injectables-aware overload — same semantics as
     * {@link #executeWithApproval(String, ToolDefinition, Map, StreamingSession, ApprovalStrategy, ToolApprovalPolicy)}
     * but threads framework-scoped instances through to
     * {@link ToolExecutor#execute(Map, Map)}. The session is already in the
     * injectables map when the caller supplies one keyed by {@code
     * StreamingSession.class}; adding it to {@code injectables} explicitly
     * lets {@code @AiTool} methods declare the concrete type too.
     *
     * <p>This is the one cross-runtime tool choke point, so it is also the
     * durable-execution memo seam. When a {@link DurableRunContext} is installed
     * for the session's run (durable runs enabled), each tool call is recorded in
     * the run's {@link EffectJournal} keyed by a content-addressed idempotency key
     * ({@link EffectKeys#toolCall}). A replay that re-walks the same tool sequence
     * gets a {@code COMMITTED} hit, skips the executor and every gate, re-emits
     * both the {@code ToolStart} and {@code ToolResult} frames, and returns the
     * recorded outcome — so the side effect runs at most once across a crash and
     * the wire sees an identical round. When durable runs are off (no scope, or
     * the {@link EffectJournal#NOOP} journal) this takes the byte-identical live
     * path with no memoization overhead.</p>
     */
    public static String executeWithApproval(String toolName, ToolDefinition tool,
                                             Map<String, Object> args,
                                             StreamingSession session,
                                             ApprovalStrategy strategy,
                                             ToolApprovalPolicy policy,
                                             Map<Class<?>, Object> injectables) {
        var ctx = DurableRunScopeHolder.current(session);
        if (ctx == null || ctx.journal() == EffectJournal.NOOP) {
            // Durable runs off: byte-identical live path, no memoization.
            return executeWithApprovalLive(toolName, tool, args, session, strategy, policy, injectables);
        }
        var journal = ctx.journal();
        // Advance the per-(tool,args) occurrence cursor exactly once per call, on
        // BOTH first-drive and replay, so identical repeated calls get distinct,
        // reproducible keys (delete_row(7) twice -> ordinals 0, 1).
        var canonicalArgs = EffectKeys.canonicalJson(args);
        var occurrence = ctx.nextToolOccurrence(toolName, canonicalArgs);
        var key = EffectKeys.toolCall(ctx.runId(), toolName, args, occurrence);
        // The run principal is part of the request digest, so a re-drive under a
        // different principal sees a digest mismatch on every prior tool effect
        // and re-executes live (re-running any approval gate) instead of
        // inheriting this run's recorded — possibly human-approved — outcomes
        // (Correctness Invariant #6, default-deny on cross-principal replay).
        var digest = EffectKeys.sha256Hex(toolName, canonicalArgs, ctx.userId());

        var hit = journal.lookupCommitted(ctx.runId(), key);
        if (hit.isPresent() && digest.equals(hit.get().requestDigest())) {
            // Replay hit: re-emit both frames and return the recorded outcome
            // without touching the executor or any gate.
            var recorded = hit.get().resultPayload();
            if (session != null) {
                session.emit(new AiEvent.ToolStart(toolName, args));
                session.emit(new AiEvent.ToolResult(toolName, recorded));
            }
            return recorded;
        }
        if (hit.isPresent()) {
            // Same key, different inputs: a divergence tripwire. Never replay a
            // stale result — fall through to live execution.
            logger.warn("Tool {} effect digest mismatch on replay (key {}); "
                    + "executing live instead of replaying a stale result", toolName, key);
        }

        // First execution: record PENDING before the side effect, commit the
        // terminal outcome after. A crash in between leaves it PENDING, so a
        // resume re-runs it (at-least-once). A RejectedExecutionException from the
        // per-run effect cap propagates and fails the run (Invariant #3).
        journal.appendPending(ctx.runId(), EffectKind.TOOL_CALL, key, digest);
        String result;
        try {
            result = executeWithApprovalLive(toolName, tool, args, session, strategy, policy, injectables);
        } catch (RuntimeException e) {
            // Helper-level failure (a tool error is encoded as JSON by the live
            // path, not thrown): record FAILED so a resume re-runs rather than
            // replaying a non-result.
            journal.markFailed(ctx.runId(), key, errorMessage(e));
            throw e;
        }
        journal.commit(ctx.runId(), key, result);
        return result;
    }

    /**
     * The live (non-memoized) tool-execution path: argument validation,
     * governance admission, {@code @Authorize}, the {@code ToolStart} frame, the
     * permission/approval gates, and the {@code ToolResult} frame on every
     * terminal path. Wrapped by
     * {@link #executeWithApproval(String, ToolDefinition, Map, StreamingSession, ApprovalStrategy, ToolApprovalPolicy, Map)}
     * for durable-run memoization; that wrapper is the only caller on the durable
     * path, but every non-durable call also routes here unchanged.
     */
    static String executeWithApprovalLive(String toolName, ToolDefinition tool,
                                          Map<String, Object> args,
                                          StreamingSession session,
                                          ApprovalStrategy strategy,
                                          ToolApprovalPolicy policy,
                                          Map<Class<?>, Object> injectables) {
        var errors = ToolArgumentValidator.validate(tool, args);
        if (!errors.isEmpty()) {
            logger.info("Tool {} argument validation failed: {}", toolName, errors);
            return buildValidationErrorJson(toolName, errors);
        }
        var effectivePolicy = policy != null ? policy : ToolApprovalPolicy.annotated();
        var scope = injectables != null ? injectables : Map.<Class<?>, Object>of();

        // Governance policy plane admission on the tool-call intent. MS-schema
        // rules that target tool_name (the canonical MS Agent Governance example
        // {field: tool_name, operator: eq, value: delete_database, action: deny})
        // fire here, before the tool executor runs. Covers OWASP Agentic
        // Top-10 #A02 (Tool Misuse). Safe when no policies are installed —
        // PolicyAdmissionGate admits implicitly on empty chains.
        //
        // Two resolution paths, mutually exclusive: the @AiEndpoint path carries
        // an AtmosphereResource and reads policies off the framework behind it;
        // the resource-free AiPipeline paths (channel bridges, A2A, AG-UI,
        // coordinator-local) instead thread the pipeline's effective policy chain
        // through the injectables as a GovernancePolicyChain. Before this, the
        // resource-free paths skipped tool-call admission entirely — a Mode
        // Parity (#7) and Security (#6) gap.
        var resource = (org.atmosphere.cpr.AtmosphereResource) scope.get(
                org.atmosphere.cpr.AtmosphereResource.class);
        org.atmosphere.ai.governance.PolicyAdmissionGate.Result gateResult = null;
        if (resource != null) {
            gateResult = org.atmosphere.ai.governance.PolicyAdmissionGate
                    .admitToolCall(resource, toolName, args);
        } else if (scope.get(org.atmosphere.ai.governance.GovernancePolicyChain.class)
                instanceof org.atmosphere.ai.governance.GovernancePolicyChain chain) {
            gateResult = org.atmosphere.ai.governance.PolicyAdmissionGate
                    .admitToolCall(chain.policies(), toolName, args);
        }
        if (gateResult instanceof org.atmosphere.ai.governance.PolicyAdmissionGate.Result.Denied denied) {
            logger.info("Tool {} denied by governance policy {}: {}",
                    toolName, denied.policyName(), denied.reason());
            return buildGovernanceDenyJson(toolName,
                    denied.policyName(), denied.reason());
        }

        // Tool-level @Authorize check (Correctness Invariant #6: default-deny
        // when caller's roles/permissions cannot satisfy the requirement).
        // Runs AFTER governance admission and BEFORE the ToolStart frame so a
        // denied call never shows up in the UI as started-but-never-finished
        // and never reaches the approval registry where a disconnect would
        // have to release it.
        var authorization = ToolAuthorizationRegistry.get(toolName);
        if (!authorization.isEmpty()) {
            var callerRoles = extractCallerSet(resource, "ai.userRoles");
            var callerPermissions = extractCallerSet(resource, "ai.userPermissions");
            if (!authorization.isAuthorized(callerRoles, callerPermissions)) {
                logger.info("Tool {} denied by @Authorize: required roles={}, "
                                + "required permissions={}, caller roles={}, caller permissions={}",
                        toolName, authorization.requiredRoles(), authorization.requiredPermissions(),
                        callerRoles, callerPermissions);
                return finishAndEmit(toolName, session, scope,
                        "{\"status\":\"cancelled\",\"message\":\"Tool execution denied: insufficient authorization\"}");
            }
        }

        // Emit a single ToolStart frame at the shared execution seam so all
        // runtime bridges (LC4j, Spring AI, ADK, SK) surface tool activity to
        // the client uniformly. OpenAiCompatibleClient used to emit its own
        // ToolStart before delegating here — that duplicate was removed in
        // favor of this centralized frame (Correctness Invariant #7, Mode
        // Parity). ToolResult is emitted by {@link #finishAndEmit} on every
        // terminal path. Emitted only after governance admission so denied
        // tool calls do not show up as started-but-never-finished in the UI.
        if (session != null) {
            session.emit(new AiEvent.ToolStart(toolName, args));
        }

        // Outer gate: the session's PermissionMode (from AgentIdentity, if
        // present in the injectables map) is the per-user authorization
        // override. The mode shadows per-tool @RequiresApproval — an explicit
        // DENY_ALL overrides any tool-local permissive setting per Correctness
        // Invariant #6 (default deny). When no identity is wired (tests,
        // ad-hoc pipelines), the behaviour is unchanged from the per-tool
        // gate below.
        var permissionMode = resolveMode(scope);
        boolean forceApproval = false;
        boolean acceptEdits = false;
        switch (permissionMode) {
            case DENY_ALL -> {
                logger.info("Tool {} blocked by AgentIdentity PermissionMode.DENY_ALL", toolName);
                return finishAndEmit(toolName, session, scope,
                        "{\"status\":\"cancelled\",\"message\":\"Tool execution denied by PermissionMode.DENY_ALL\"}");
            }
            case BYPASS -> {
                // Auto-approve every tool. Explicit opt-in only.
                return finishAndEmit(toolName, session, scope,
                        executeAndFormat(toolName, tool.executor(), args, scope, tool.executionTimeout()));
            }
            case PLAN -> {
                // Force approval on every tool regardless of tool-local
                // @RequiresApproval — the mode IS the approval gate.
                forceApproval = true;
            }
            case ACCEPT_EDITS -> {
                // Auto-approve edit-shaped tools (kind == EDIT); every other
                // tool falls through to the per-tool @RequiresApproval gate,
                // identical to DEFAULT. The bypass is applied at the approval
                // gate below so it still honours an explicit ToolPermissionPolicy
                // DENY/CONFIRM and a DenyAll policy — ACCEPT_EDITS relaxes a
                // tool's own approval prompt, never an operator-configured deny.
                acceptEdits = true;
            }
            case DEFAULT -> {
                // Fall through to per-tool @RequiresApproval.
            }
        }

        // Config-driven per-tool permission gate. ToolPermissionPolicy.global()
        // resolves to ALLOW_ALL by default so existing deployments are
        // unaffected; opt-in via atmosphere.tools.permissions.<tool>=deny|confirm
        // (or a ServiceLoader-registered ToolPermissionPolicy) lets operators
        // restrict trusted-by-default tools without re-annotating @AiTool
        // methods. DENY emits a JFR ToolInvocationEvent with outcome=DENIED so
        // observers can distinguish refusals from failed executions.
        var permissionDecision = org.atmosphere.ai.tool.ToolPermissionPolicy.global()
                .decide(toolName, args);
        switch (permissionDecision) {
            case DENY -> {
                logger.info("Tool {} denied by ToolPermissionPolicy", toolName);
                emitDeniedJfrEvent(toolName);
                return finishAndEmit(toolName, session, scope,
                        "{\"status\":\"cancelled\",\"message\":\"Tool execution denied by ToolPermissionPolicy\"}");
            }
            case CONFIRM -> forceApproval = true;
            case ALLOW -> {
                // No-op; per-tool @RequiresApproval / effectivePolicy still apply.
            }
        }

        // ACCEPT_EDITS auto-approves edit-shaped tools: it relaxes the tool's
        // own @RequiresApproval prompt, but never a DenyAll policy (which still
        // denies below) nor an explicit forceApproval from PLAN mode or a
        // CONFIRM policy decision.
        boolean acceptEditAutoApprove = acceptEdits
                && tool.kind() == ToolKind.EDIT
                && !(effectivePolicy instanceof ToolApprovalPolicy.DenyAll);

        // Fast-path: nothing requires approval (or it is auto-approved by
        // ACCEPT_EDITS) — execute directly.
        if (!forceApproval && (acceptEditAutoApprove || !effectivePolicy.requiresApproval(tool))) {
            if (acceptEditAutoApprove && effectivePolicy.requiresApproval(tool)) {
                logger.info("Tool {} auto-approved under PermissionMode.ACCEPT_EDITS (kind=EDIT)", toolName);
            }
            return finishAndEmit(toolName, session, scope,
                    executeAndFormat(toolName, tool.executor(), args, scope, tool.executionTimeout()));
        }
        // DenyAll is evaluated BEFORE the strategy-null fall-through so that
        // a null strategy cannot bypass a deny-by-policy decision. Previously
        // {@code !requiresApproval || strategy == null} short-circuited here
        // and let DenyAll-flagged tools execute unguarded whenever no
        // ApprovalStrategy was wired — a Security Invariant #6 (default deny)
        // violation.
        if (effectivePolicy instanceof ToolApprovalPolicy.DenyAll) {
            logger.info("Tool {} blocked by DenyAll policy", toolName);
            return finishAndEmit(toolName, session, scope,
                    "{\"status\":\"cancelled\",\"message\":\"Tool execution denied by policy\"}");
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
            return finishAndEmit(toolName, session, scope,
                    "{\"status\":\"cancelled\",\"message\":\"Tool requires approval but no "
                    + "ApprovalStrategy is configured on this execution path\"}");
        }

        var resolution = resolveApprovalDurably(toolName, tool, args, session, strategy);
        return finishAndEmit(toolName, session, scope, switch (resolution.outcome()) {
            case APPROVED -> {
                if (resolution.hasResponsePayload()) {
                    // Reviewer answered on the tool's behalf — do NOT run the
                    // tool; hand their structured / free-form value back to the
                    // model as the tool result.
                    logger.info("Tool {} approved with a reviewer-supplied response (tool not run)",
                            toolName);
                    yield formatResponsePayload(resolution.responsePayload());
                }
                // Approve-with-edited-args: the reviewer's arguments replace the
                // model's proposal when supplied.
                var effectiveArgs = resolution.hasModifiedArguments()
                        ? resolution.modifiedArguments() : args;
                logger.info("Tool {} approved, executing{}", toolName,
                        resolution.hasModifiedArguments() ? " with reviewer-edited arguments" : "");
                yield executeAndFormat(toolName, tool.executor(), effectiveArgs, scope, tool.executionTimeout());
            }
            case DENIED -> {
                logger.info("Tool {} denied by user", toolName);
                yield "{\"status\":\"cancelled\",\"message\":\"Action cancelled by user\"}";
            }
            case TIMED_OUT -> {
                logger.info("Tool {} approval timed out", toolName);
                yield "{\"status\":\"timeout\",\"message\":\"Approval timed out\"}";
            }
        });
    }

    /**
     * Format a reviewer-supplied response payload as a tool result string. A
     * plain {@link String} is returned verbatim (free-form answer); anything
     * else is serialized to JSON (structured answer), matching the
     * {@code ToolExecutor} "result serialized to JSON" contract.
     */
    private static String formatResponsePayload(Object payload) {
        if (payload == null) {
            return "null";
        }
        if (payload instanceof String s) {
            return s;
        }
        try {
            return RESPONSE_JSON.writeValueAsString(payload);
        } catch (RuntimeException e) {
            return payload.toString();
        }
    }

    /**
     * Durable JSON payload of one approval decision, committed to the
     * {@link EffectJournal} under {@link EffectKind#APPROVAL}. Package-private
     * so Jackson (and the regression test) can reach it.
     */
    record ApprovalEffectPayload(String outcome, Map<String, Object> modifiedArguments,
                                 Object responsePayload) {
    }

    /**
     * Await the HITL approval decision, durably when a {@link DurableRunContext}
     * is installed for the session's run. Before this seam existed, pending
     * approvals lived only in a per-instance in-memory registry: a node crash
     * after a human approved — but before the tool result committed — forced
     * the resumed run to re-prompt with a fresh approval id, and the original
     * decision was lost (the Tier-1 HITL durability P1).
     *
     * <p>With durable runs on, the decision is recorded as an
     * {@link EffectKind#APPROVAL} effect keyed by
     * {@link EffectKeys#approval} (its own occurrence cursor, its own
     * namespace — never colliding with the enclosing TOOL_CALL effect):</p>
     * <ul>
     *   <li><b>Replay hit</b> (digest match, Inv #6: the digest folds the run
     *       principal so another user's re-drive can never inherit a recorded
     *       decision): the recorded APPROVED / DENIED decision is returned
     *       without re-prompting the reviewer.</li>
     *   <li><b>Miss</b>: {@code appendPending} (idempotent — a crash while
     *       parked re-appends safely) → live await → the decision is committed
     *       <em>before</em> the tool executes, so the approval commit always
     *       precedes the enclosing TOOL_CALL commit (Inv #2) and a crash
     *       mid-tool re-drives without re-prompting.</li>
     *   <li><b>Timeout</b> is <em>not</em> committed as a decision — the
     *       effect is marked FAILED so a resumed run re-prompts: an expiry is
     *       the absence of a human decision, not a decision to replay.</li>
     * </ul>
     *
     * <p>With durable runs off (no scope or the NOOP journal), this is the
     * byte-identical live path. Journal bookkeeping failures degrade to the
     * live decision with a WARN — losing durability, never the decision.</p>
     */
    private static ApprovalResolution resolveApprovalDurably(String toolName, ToolDefinition tool,
                                                             Map<String, Object> args,
                                                             StreamingSession session,
                                                             ApprovalStrategy strategy) {
        var ctx = DurableRunScopeHolder.current(session);
        if (ctx == null || ctx.journal() == EffectJournal.NOOP) {
            return awaitLiveApproval(toolName, tool, args, session, strategy);
        }
        var journal = ctx.journal();
        var canonicalArgs = EffectKeys.canonicalJson(args);
        var occurrence = ctx.nextApprovalOccurrence(toolName, canonicalArgs);
        var key = EffectKeys.approval(ctx.runId(), toolName, args, occurrence);
        var digest = EffectKeys.sha256Hex("approval", toolName, canonicalArgs, ctx.userId());

        var hit = journal.lookupCommitted(ctx.runId(), key);
        if (hit.isPresent() && digest.equals(hit.get().requestDigest())) {
            var replayed = parseApprovalPayload(hit.get().resultPayload());
            if (replayed != null) {
                logger.info("Tool {} approval decision replayed from the effect journal "
                        + "(outcome={}) — reviewer not re-prompted", toolName, replayed.outcome());
                return replayed;
            }
            // Unparseable recorded decision: never guess — fall through to a
            // live prompt (at-least-once prompting is safe; a wrong replayed
            // decision is not).
            logger.warn("Tool {} recorded approval decision was unreadable — re-prompting live",
                    toolName);
        }
        try {
            journal.appendPending(ctx.runId(), EffectKind.APPROVAL, key, digest);
        } catch (RuntimeException e) {
            // Per-run effect cap or journal fault: degrade to a live,
            // non-durable decision rather than blocking the gate (the cap
            // failing the run happens at the TOOL_CALL seam, not here).
            logger.warn("Tool {} approval effect could not be recorded ({}); "
                    + "the decision will not survive a crash", toolName, e.toString());
            return awaitLiveApproval(toolName, tool, args, session, strategy);
        }
        var resolution = awaitLiveApproval(toolName, tool, args, session, strategy);
        try {
            if (resolution.outcome() == ApprovalStrategy.ApprovalOutcome.TIMED_OUT) {
                journal.markFailed(ctx.runId(), key, "approval timed out before a decision");
            } else {
                journal.commit(ctx.runId(), key, RESPONSE_JSON.writeValueAsString(
                        new ApprovalEffectPayload(resolution.outcome().name(),
                                resolution.modifiedArguments(), resolution.responsePayload())));
            }
        } catch (RuntimeException e) {
            logger.warn("Tool {} approval decision could not be committed to the effect "
                    + "journal ({}); the decision stands but will not survive a crash",
                    toolName, e.toString());
        }
        return resolution;
    }

    /** The pre-durability live await, unchanged: park until approved / denied / expired. */
    private static ApprovalResolution awaitLiveApproval(String toolName, ToolDefinition tool,
                                                        Map<String, Object> args,
                                                        StreamingSession session,
                                                        ApprovalStrategy strategy) {
        var timeout = tool.approvalTimeout() > 0 ? tool.approvalTimeout() : 300;
        var approval = new PendingApproval(
                ApprovalRegistry.generateId(),
                toolName,
                args,
                tool.approvalMessage(),
                session.sessionId(),
                Instant.now().plusSeconds(timeout)
        );
        return strategy.awaitApprovalDetailed(approval, session);
    }

    /** Parse a recorded {@link ApprovalEffectPayload}; null when unreadable. */
    private static ApprovalResolution parseApprovalPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            var recorded = RESPONSE_JSON.readValue(payload, ApprovalEffectPayload.class);
            var outcome = ApprovalStrategy.ApprovalOutcome.valueOf(recorded.outcome());
            return new ApprovalResolution(outcome, recorded.modifiedArguments(),
                    recorded.responsePayload());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Emit the terminal {@link AiEvent.ToolResult} frame and return the tool
     * result. Large results are first disk-offloaded (see
     * {@link #maybeOffload}); the emitted frame carries the SAME value that is
     * returned to the model (the preview when offloaded), so the console shows
     * exactly what the model saw — the emit and the return never diverge.
     */
    private static String finishAndEmit(String toolName, StreamingSession session,
                                        Map<Class<?>, Object> scope, String result) {
        // Screen the tool output for indirect prompt injection BEFORE offload so
        // the screened value is what both reaches disk and re-enters the model
        // (the emit==return invariant). Opt-in and off by default — see
        // ToolOutputSafetyScreen for the posture rationale.
        var screened = ToolOutputSafetyScreen.screen(toolName, result);
        var effective = maybeOffload(toolName, screened, scope);
        if (session != null) {
            session.emit(new AiEvent.ToolResult(toolName, effective));
        }
        return effective;
    }

    /**
     * Disk-offload a large tool result (deepagents-style context management):
     * when the result exceeds the configured threshold AND an
     * {@link org.atmosphere.ai.fs.AgentFileSystem} is bound to the tool scope,
     * write the FULL result to a stable
     * {@code tool-output/{toolName}-{shortId}.txt} path in the agent workspace
     * and return a truncated preview that points the model at the saved file
     * (it can {@code read_file} the full output on demand). This keeps a
     * multi-kilobyte tool result out of the model's context window without
     * ever losing data.
     *
     * <p>Never throws and never loses data: a disabled threshold
     * ({@code <= 0}), a below-threshold result, no filesystem in scope, or a
     * rejected write (e.g. the workspace {@code AgentFileSystem.Limits} bounds
     * — Correctness Invariant #3) all return the original result unchanged.
     * The write failure is logged at debug, never surfaced to the model.</p>
     */
    private static String maybeOffload(String toolName, String result,
                                       Map<Class<?>, Object> scope) {
        if (result == null) {
            return null;
        }
        var threshold = resolveOffloadThreshold();
        if (threshold <= 0 || result.length() <= threshold) {
            return result;
        }
        var fs = org.atmosphere.ai.fs.FileSystemTools
                .resolveFileSystem(scope != null ? scope : Map.<Class<?>, Object>of())
                .orElse(null);
        if (fs == null) {
            logger.trace("Tool {} produced a {}-char result but no AgentFileSystem is in "
                    + "scope — returning it inline (no offload)", toolName, result.length());
            return result;
        }
        var path = offloadPath(toolName);
        try {
            fs.write(path, result);
        } catch (RuntimeException e) {
            // A bounds rejection (Invariant #3) or any other write failure must
            // never lose the result and never throw out of the offload path —
            // fall back to returning the full result inline.
            logger.debug("Tool {} output offload to {} failed ({}); returning the result inline",
                    toolName, path, e.toString());
            return result;
        }
        var previewLength = Math.min(OFFLOAD_PREVIEW_CHARS, result.length());
        var truncated = result.length() - previewLength;
        var preview = result.substring(0, previewLength)
                + "\n\n[... " + truncated + " chars truncated. Full output saved to "
                + path + " — use " + org.atmosphere.ai.fs.FileSystemTools.READ_FILE
                + " to read it.]";
        logger.debug("Tool {} output ({} chars) offloaded to {}; returning a {}-char preview",
                toolName, result.length(), path, preview.length());
        return preview;
    }

    /**
     * Build the workspace path a large tool output is offloaded to. The tool
     * name is sanitized to a single safe file-name segment (Correctness
     * Invariant #4, Boundary Safety) and disambiguated with a short random id
     * so repeated large results from the same tool never collide.
     */
    private static String offloadPath(String toolName) {
        var base = (toolName == null || toolName.isBlank()) ? "tool"
                : UNSAFE_OFFLOAD_NAME_CHARS.matcher(toolName).replaceAll("_");
        var shortId = java.util.UUID.randomUUID().toString().substring(0, 8);
        return "tool-output/" + base + "-" + shortId + ".txt";
    }

    /**
     * Resolve the tool-output offload threshold from the
     * {@code org.atmosphere.ai.toolOutputOffloadThreshold} system property,
     * falling back to the {@code LLM_TOOL_OUTPUT_OFFLOAD_THRESHOLD} environment
     * variable, then to {@link #DEFAULT_TOOL_OUTPUT_OFFLOAD_THRESHOLD}. The
     * sysprop wins over the env var, mirroring {@code AiConfig}'s dual
     * resolution. Parsing is lenient — a malformed value falls back to the
     * default rather than throwing (Correctness Invariant #4). A resolved value
     * {@code <= 0} disables offload.
     */
    private static int resolveOffloadThreshold() {
        var raw = System.getProperty(TOOL_OUTPUT_OFFLOAD_THRESHOLD_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(TOOL_OUTPUT_OFFLOAD_THRESHOLD_ENV);
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TOOL_OUTPUT_OFFLOAD_THRESHOLD;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            logger.warn("Ignoring malformed tool-output offload threshold '{}' (expected an integer)",
                    raw);
            return DEFAULT_TOOL_OUTPUT_OFFLOAD_THRESHOLD;
        }
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
     * Pull the caller's {@link org.atmosphere.ai.identity.PermissionMode} from
     * the injectable scope, falling back to {@code DEFAULT} when no identity
     * is wired. Resolution order: (1) explicit {@code PermissionMode} entry,
     * (2) derived from an {@code AgentIdentity} plus a {@code userId} on an
     * {@link org.atmosphere.cpr.AtmosphereResource} request attribute,
     * (3) {@code DEFAULT}.
     */
    private static org.atmosphere.ai.identity.PermissionMode resolveMode(
            Map<Class<?>, Object> scope) {
        if (scope.isEmpty()) {
            return org.atmosphere.ai.identity.PermissionMode.DEFAULT;
        }
        if (scope.get(org.atmosphere.ai.identity.PermissionMode.class)
                instanceof org.atmosphere.ai.identity.PermissionMode explicit) {
            return explicit;
        }
        var identity = (org.atmosphere.ai.identity.AgentIdentity)
                scope.get(org.atmosphere.ai.identity.AgentIdentity.class);
        if (identity == null) {
            return org.atmosphere.ai.identity.PermissionMode.DEFAULT;
        }
        String userId = null;
        if (scope.get(org.atmosphere.cpr.AtmosphereResource.class)
                instanceof org.atmosphere.cpr.AtmosphereResource resource
                && resource.getRequest() != null
                && resource.getRequest().getAttribute("ai.userId") != null) {
            var attr = resource.getRequest().getAttribute("ai.userId").toString();
            if (!attr.isBlank()) {
                userId = attr;
            }
        }
        // No userId on the request → there's nothing to authorize against.
        // Fall back to DEFAULT rather than forcing AgentIdentity to accept a
        // blank userId (most implementations validate).
        if (userId == null) {
            return org.atmosphere.ai.identity.PermissionMode.DEFAULT;
        }
        var mode = identity.permissionMode(userId);
        return mode != null ? mode : org.atmosphere.ai.identity.PermissionMode.DEFAULT;
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

    /**
     * Extract a set of strings from an {@link org.atmosphere.cpr.AtmosphereResource}
     * request attribute. Used by the {@link org.atmosphere.ai.annotation.Authorize}
     * gate to read {@code ai.userRoles} and {@code ai.userPermissions}.
     *
     * <p>Accepts three concrete shapes: a {@link java.util.Set Set&lt;String&gt;},
     * any other {@link java.util.Collection} of strings, or a comma-separated
     * {@link String}. Anything else (including null) yields an empty set so
     * the authorization check is the single source of decision — every
     * pathological shape becomes an unauthenticated caller, which fails
     * closed against any non-empty {@link ToolAuthorization}.</p>
     */
    @SuppressWarnings("unchecked")
    private static java.util.Set<String> extractCallerSet(
            org.atmosphere.cpr.AtmosphereResource resource, String attributeName) {
        if (resource == null || resource.getRequest() == null) {
            return java.util.Set.of();
        }
        var raw = resource.getRequest().getAttribute(attributeName);
        if (raw == null) {
            return java.util.Set.of();
        }
        if (raw instanceof java.util.Set<?> set) {
            return set.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        if (raw instanceof java.util.Collection<?> collection) {
            return collection.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        if (raw instanceof String csv) {
            if (csv.isBlank()) {
                return java.util.Set.of();
            }
            return java.util.Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        return java.util.Set.of();
    }

    /**
     * Build the cancellation payload returned when a governance policy denies
     * a tool call. Every interpolated field passes through
     * {@link ToolBridgeUtils#escapeJson} so backslashes, newlines, tabs, and
     * embedded quotes in tool / policy names or reasons cannot produce
     * malformed JSON for the downstream parser. Package-private so the
     * regression test can pin the wire format directly.
     */
    static String buildGovernanceDenyJson(String toolName, String policyName, String reason) {
        String message = "Tool " + (toolName == null ? "" : toolName)
                + " denied by policy '" + (policyName == null ? "" : policyName)
                + "': " + (reason == null ? "" : reason);
        return "{\"status\":\"cancelled\",\"message\":\""
                + ToolBridgeUtils.escapeJson(message) + "\"}";
    }

    private static void emitDeniedJfrEvent(String toolName) {
        var event = new org.atmosphere.ai.jfr.ToolInvocationEvent();
        if (!event.shouldCommit()) {
            return;
        }
        event.tool = toolName != null ? toolName : "unknown";
        event.model = "unknown";
        event.outcome = org.atmosphere.ai.jfr.ToolInvocationEvent.OUTCOME_DENIED;
        event.durationNanos = 0L;
        event.commit();
    }
}
