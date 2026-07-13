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

import org.atmosphere.ai.approval.ApprovalRegistry;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.GovernanceTracer;
import org.atmosphere.ai.governance.PolicyAsGuardrail;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.governance.scope.ScopePolicyInstaller;
import org.atmosphere.ai.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resource-free AI pipeline that processes messages through the full AI chain
 * (memory, tools, guardrails, RAG, metrics) without requiring an
 * {@link org.atmosphere.cpr.AtmosphereResource}.
 *
 * <p>Used by the channel bridge to route external messaging platform messages
 * (Slack, Telegram, etc.) through the same pipeline as WebSocket clients.
 * {@link AiInterceptor} is intentionally excluded because its interface
 * requires {@code AtmosphereResource}.</p>
 */
public class AiPipeline {

    private static final Logger logger = LoggerFactory.getLogger(AiPipeline.class);

    private final AgentRuntime runtime;
    private final String systemPrompt;
    private final String model;
    private final AiConversationMemory memory;
    private final ToolRegistry toolRegistry;
    private final List<AiGuardrail> guardrails;
    private final List<GovernancePolicy> policies;
    private final List<ContextProvider> contextProviders;
    private final AiMetrics metrics;
    private final Class<?> responseType;
    // Phase 0 HITL gap fix: the pipeline owns its own ApprovalRegistry so
    // @Agent / @Coordinator / AG-UI / channel paths honor @RequiresApproval
    // tools identically to the @AiEndpoint websocket path. Each execute()
    // call derives a strategy from this registry and threads it into the
    // 15-arg AgentExecutionContext constructor so ToolExecutionHelper's
    // unified gate actually fires. Callers can route protocol-specific
    // approval messages through {@link #tryResolveApproval(String)}.
    private final ApprovalRegistry approvalRegistry = new ApprovalRegistry();
    /** Pipeline-level response cache. Null by default (cache disabled). */
    private volatile org.atmosphere.ai.cache.ResponseCache responseCache;
    private volatile java.time.Duration responseCacheTtl = java.time.Duration.ofMinutes(5);

    /**
     * Registration-time injectables threaded into the tool-execution scope of
     * the resource-free dispatch paths — see {@link #setToolInjectables}.
     */
    private volatile java.util.Map<Class<?>, Object> toolInjectables = java.util.Map.of();
    /**
     * Default pipeline-level caching policy. When non-null and non-NONE,
     * every {@link #execute(String, String, StreamingSession)} call seeds a
     * {@link org.atmosphere.ai.llm.CacheHint} into the request metadata so
     * the cache gate becomes reachable on the channel-bridge path. Callers
     * that want per-request control use
     * {@link #execute(String, String, StreamingSession, java.util.Map)}
     * and pass their own {@code ai.cache.hint} entry in the metadata map.
     */
    private volatile org.atmosphere.ai.llm.CacheHint.CachePolicy defaultCachePolicy;
    /**
     * Pipeline-level default {@link AiBudget}. When set and {@link AiBudget#enforced()},
     * every {@link #execute(String, String, StreamingSession)} call wraps its
     * session in a {@link BudgetCapturingSession}. Per-request callers can
     * override via the {@code ai.budget} metadata key supplied to the
     * 4-arg execute overload, mirroring how {@link org.atmosphere.ai.llm.CacheHint}
     * is threaded.
     */
    private volatile AiBudget defaultBudget;
    /**
     * Pipeline-level default {@link AiConfidenceElicitation}. When set,
     * every {@link #execute(String, String, StreamingSession)} call wraps
     * its session in a {@link ConfidenceCapturingSession} and augments
     * the system prompt with the elicitation cue. Per-request callers
     * can override via the {@code ai.confidence.elicitation} metadata
     * key, mirroring how {@link AiBudget} and
     * {@link org.atmosphere.ai.llm.CacheHint} are threaded.
     */
    private volatile AiConfidenceElicitation defaultConfidenceElicitation;
    /**
     * Pipeline-level default {@link AiStructuredRetry}. When set and enabled,
     * every {@link #execute(String, String, StreamingSession)} call seeds it
     * into the request metadata so structured-output turns self-heal on schema
     * failures. Per-request callers override via the {@code ai.structured.retry}
     * metadata key, mirroring how {@link AiBudget} is threaded.
     */
    private volatile AiStructuredRetry defaultStructuredRetry;
    /**
     * Cap on tools handed to the model per turn; {@code <= 0} injects every
     * registered tool. When a larger catalog exists, the tools are pre-filtered
     * to the most relevant for the message via {@link org.atmosphere.ai.tool.ToolSelection}.
     */
    private volatile int maxToolsPerRequest;
    /**
     * Metadata key emitted on both cache hit ({@code true}) and cache miss
     * ({@code false}) when the pipeline's cache gate fires. Mirrors the
     * naming convention of {@code ai.tokens.input}/{@code ai.tokens.output}
     * — a single canonical framework-level signal that observers
     * (e2e specs, metrics, audit) can read without reaching into
     * sample-level shims.
     */
    public static final String CACHE_HIT_METADATA_KEY = "ai.cache.hit";

    public AiPipeline(AgentRuntime runtime, String systemPrompt, String model,
                      AiConversationMemory memory, ToolRegistry toolRegistry,
                      List<AiGuardrail> guardrails, List<ContextProvider> contextProviders,
                      AiMetrics metrics) {
        this(runtime, systemPrompt, model, memory, toolRegistry, guardrails,
                List.of(), contextProviders, metrics, null);
    }

    public AiPipeline(AgentRuntime runtime, String systemPrompt, String model,
                      AiConversationMemory memory, ToolRegistry toolRegistry,
                      List<AiGuardrail> guardrails, List<ContextProvider> contextProviders,
                      AiMetrics metrics, Class<?> responseType) {
        this(runtime, systemPrompt, model, memory, toolRegistry, guardrails,
                List.of(), contextProviders, metrics, responseType);
    }

    /**
     * Canonical constructor that accepts both imperative guardrails and declarative
     * governance policies. Policies are evaluated after guardrails on the admission
     * path (matching the configured order) and piggy-back on
     * {@link GuardrailCapturingSession} for post-response evaluation via a
     * {@link PolicyAsGuardrail} adapter.
     */
    public AiPipeline(AgentRuntime runtime, String systemPrompt, String model,
                      AiConversationMemory memory, ToolRegistry toolRegistry,
                      List<AiGuardrail> guardrails,
                      List<GovernancePolicy> policies,
                      List<ContextProvider> contextProviders,
                      AiMetrics metrics, Class<?> responseType) {
        this.runtime = runtime;
        this.systemPrompt = systemPrompt != null ? systemPrompt : "";
        this.model = model;
        this.memory = memory;
        this.toolRegistry = toolRegistry;
        this.guardrails = guardrails != null ? guardrails : List.of();
        this.policies = policies != null ? List.copyOf(policies) : List.of();
        this.contextProviders = contextProviders != null ? contextProviders : List.of();
        this.metrics = org.atmosphere.ai.jfr.CompositeAiMetrics.withJfr(
                metrics != null ? metrics : AiMetrics.NOOP);
        this.responseType = responseType;
    }

    /** Declarative policies installed on this pipeline (never {@code null}, may be empty). */
    public List<GovernancePolicy> policies() {
        return policies;
    }

    /**
     * Route a protocol-specific approval message ({@code /__approval/<id>/approve}
     * or {@code /deny}) through the pipeline's approval registry so the parked
     * virtual thread in {@code ToolExecutionHelper.executeWithApproval} resumes.
     * Callers on A2A / @Coordinator / AG-UI / Slack / Telegram / Discord /
     * WhatsApp wire their protocol's approval decision to this method.
     *
     * <p>Only returns {@code true} when the registry actually matched and
     * resolved a pending approval. {@link ApprovalRegistry.ResolveResult#UNKNOWN_ID}
     * (stale, expired, or owned by another registry) and
     * {@link ApprovalRegistry.ResolveResult#NOT_AN_APPROVAL} both fall through so
     * the message propagates to the rest of the pipeline as normal input.
     *
     * @param message the incoming approval-protocol message
     * @return {@code true} if the message resolved a pending approval in this pipeline
     */
    public boolean tryResolveApproval(String message) {
        return approvalRegistry.resolve(message) == ApprovalRegistry.ResolveResult.RESOLVED;
    }

    /**
     * Attach a pipeline-level {@link org.atmosphere.ai.cache.ResponseCache}.
     * When set AND the request's {@link org.atmosphere.ai.llm.CacheHint} is
     * opted in, the pipeline consults the cache before calling the runtime;
     * on cache miss, it wraps the target session in a
     * {@link org.atmosphere.ai.cache.CachingStreamingSession} to capture the
     * response for later replay. Works across all runtimes because it lives
     * at the pipeline layer, not the provider wire.
     *
     * @param cache cache instance, or {@code null} to disable
     * @param ttl   entry TTL; defaults to 5 minutes when {@code null}
     */
    public void setResponseCache(org.atmosphere.ai.cache.ResponseCache cache,
                                 java.time.Duration ttl) {
        this.responseCache = cache;
        if (ttl != null) {
            this.responseCacheTtl = ttl;
        }
    }

    public org.atmosphere.ai.cache.ResponseCache responseCache() {
        return responseCache;
    }

    /**
     * Install a pipeline-level default cache policy. Every
     * {@link #execute(String, String, StreamingSession)} call seeds a
     * {@link org.atmosphere.ai.llm.CacheHint} with this policy into the
     * request metadata before the cache gate runs, so the channel-bridge
     * public entry becomes a first-class cache consumer — closing the gap
     * where {@code execute(...)} previously hardcoded {@code Map.of()} and
     * left the {@code cacheSafe} branch unreachable via the public API.
     *
     * <p>Pass {@link org.atmosphere.ai.llm.CacheHint.CachePolicy#NONE} or
     * {@code null} to disable the default; per-request callers can still
     * supply their own {@link org.atmosphere.ai.llm.CacheHint}
     * via {@link #execute(String, String, StreamingSession, java.util.Map)}.
     *
     * @param policy caller intent; ignored when {@code null}
     */
    public void setDefaultCachePolicy(org.atmosphere.ai.llm.CacheHint.CachePolicy policy) {
        this.defaultCachePolicy = policy;
    }

    /**
     * Install a pipeline-level default {@link AiBudget}. The budget is the
     * framework-level circuit breaker that prevents runaway tool-loops and
     * cost spirals — when an in-flight call exceeds any configured limit
     * (input/output/total tokens, step count, wall clock), the pipeline
     * routes an {@link AiBudgetExceededException} through
     * {@link StreamingSession#error(Throwable)} and short-circuits the
     * remaining stream.
     *
     * <p>Pass {@code null} or {@link AiBudget#UNLIMITED} to disable enforcement.
     * Per-request callers can supply their own budget via the {@code ai.budget}
     * metadata key in the 4-arg {@link #execute(String, String, StreamingSession, java.util.Map)}
     * overload — caller-supplied budgets win over the pipeline default.</p>
     *
     * @param budget budget to enforce, or {@code null} to disable
     */
    public void setDefaultBudget(AiBudget budget) {
        this.defaultBudget = budget;
    }

    /** The currently configured pipeline default budget; {@code null} when none. */
    public AiBudget defaultBudget() {
        return defaultBudget;
    }

    /**
     * Install a pipeline-level default {@link AiConfidenceElicitation}.
     * When non-null, every {@link #execute(String, String, StreamingSession)}
     * call wraps its session in a {@link ConfidenceCapturingSession} that
     * (a) parses the model-reported confidence field on stream completion
     * and (b) fires {@link StreamingSession#confidence(AiConfidence)} with
     * {@link AiConfidence.Source#MODEL_REPORTED_FIELD}. The pipeline also
     * augments the system prompt with {@link AiConfidenceElicitation#effectiveCue()}
     * so the model knows to emit the field.
     *
     * <p>Per-request callers can override via the
     * {@code ai.confidence.elicitation} metadata key in the 4-arg
     * {@link #execute(String, String, StreamingSession, java.util.Map)}
     * overload — caller-supplied elicitations win.</p>
     *
     * @param elicitation elicitation to install, or {@code null} to disable
     */
    public void setDefaultConfidenceElicitation(AiConfidenceElicitation elicitation) {
        this.defaultConfidenceElicitation = elicitation;
    }

    /** The currently configured pipeline default elicitation; {@code null} when none. */
    public AiConfidenceElicitation defaultConfidenceElicitation() {
        return defaultConfidenceElicitation;
    }

    /**
     * Install a pipeline-level default {@link AiStructuredRetry}. Applies only
     * to structured-output turns (those with a declared response type). Per-request
     * callers override via the {@code ai.structured.retry} metadata key in the
     * 4-arg execute overload — caller-supplied retry wins.
     *
     * @param retry retry to install, or {@code null} to disable
     */
    public void setDefaultStructuredRetry(AiStructuredRetry retry) {
        this.defaultStructuredRetry = retry;
    }

    /** The currently configured pipeline default structured retry; {@code null} when none. */
    public AiStructuredRetry defaultStructuredRetry() {
        return defaultStructuredRetry;
    }

    /**
     * Cap the number of tools handed to the model per turn (dynamic pre-filtering
     * to the most relevant for the message). {@code <= 0} disables the cap and
     * injects every registered tool.
     */
    public void setMaxToolsPerRequest(int maxToolsPerRequest) {
        this.maxToolsPerRequest = Math.max(0, maxToolsPerRequest);
    }

    /** Exposed so callers can share the registry for cross-pipeline deduplication. */
    public ApprovalRegistry approvalRegistry() {
        return approvalRegistry;
    }

    /**
     * Thread the endpoint's registration-time injectables (AgentFleet,
     * AgentState, AgentPlanStore, AgentFileSystemProvider, ...) through the
     * tool-execution scope of this pipeline's resource-free dispatch paths
     * (channel bridges, A2A, AG-UI, coordinator-local). The web path gets the
     * same map from {@code AiEndpointHandler}; setting it here keeps the
     * registered tools working identically on every invocation mode
     * (Correctness Invariant #7, Mode Parity). Dispatch-time session entries
     * win on key conflict.
     *
     * @param injectables type-to-instance map, defensively copied;
     *                    {@code null} clears
     */
    public void setToolInjectables(java.util.Map<Class<?>, Object> injectables) {
        this.toolInjectables = injectables == null
                ? java.util.Map.of() : java.util.Map.copyOf(injectables);
    }

    /** The configured tool injectables (never {@code null}, may be empty). */
    public java.util.Map<Class<?>, Object> toolInjectables() {
        return toolInjectables;
    }

    /**
     * Execute the full AI pipeline for the given message. Runs on the caller's
     * thread (typically a virtual thread from the channel bridge).
     *
     * <p>Equivalent to
     * {@link #execute(String, String, StreamingSession, java.util.Map)} with
     * an empty extra-metadata map; callers that want to ride a per-request
     * {@link org.atmosphere.ai.llm.CacheHint} or any other
     * {@code AgentExecutionContext} sidecar must use the 4-arg overload so
     * the metadata actually reaches the cache gate and the runtime bridge.</p>
     *
     * @param clientId conversation key for memory (e.g., "telegram:12345")
     * @param message  the user's message
     * @param session  the streaming session to push tokens through
     */
    public void execute(String clientId, String message, StreamingSession session) {
        execute(clientId, message, session, Map.of());
    }

    /**
     * Execute the full AI pipeline with caller-supplied request metadata.
     *
     * <p>The {@code extraMetadata} map is merged with the pipeline's default
     * cache policy (if any) and threaded through as
     * {@link AiRequest#metadata()} and {@link AgentExecutionContext#metadata()}
     * so downstream components — cache gate, runtime bridge, observability —
     * observe the caller's intent. When both the caller and the pipeline
     * default carry an {@code ai.cache.hint} key, the caller wins.</p>
     *
     * <p>Before this overload existed, {@link #execute(String, String, StreamingSession)}
     * hardcoded an empty metadata map, so
     * {@link org.atmosphere.ai.llm.CacheHint#from(AgentExecutionContext)}
     * always returned {@link org.atmosphere.ai.llm.CacheHint#none()} and the
     * cache branch in this method was dead code via the public API. The 4-arg
     * entry closes that gap and makes the {@code ResponseCache} SPI reachable
     * from channel bridges (Slack, Telegram, Discord, A2A) and from direct
     * test callers.</p>
     *
     * @param clientId      conversation key for memory (e.g., "telegram:12345")
     * @param message       the user's message
     * @param session       the streaming session to push tokens through
     * @param extraMetadata caller-supplied {@code AgentExecutionContext} metadata;
     *                      merged with any pipeline-default {@link org.atmosphere.ai.llm.CacheHint}
     */
    public void execute(String clientId, String message, StreamingSession session,
                        Map<String, Object> extraMetadata) {
        // Session tape: wrap at METHOD ENTRY — before the guardrail / policy
        // admission loops — so pre-admission session.error() denials are taped
        // as ERROR runs (endpoint-path parity: AiStreamingSession routes
        // denials through its delegate, which the endpoint tape wraps). The
        // pipeline has no RunRegistry, so a tape-scoped run id is minted at
        // wrap and the conversation key is the clientId. Zero-cost no-op when
        // nothing is installed.
        if (org.atmosphere.ai.tape.TapeSupport.installed()) {
            session = org.atmosphere.ai.tape.TapeSupport.wrap(session,
                    org.atmosphere.ai.tape.TapeRunInfo.pipeline(clientId, model,
                            runtime != null ? runtime.name() : null));
        }
        var history = memory != null
                ? memory.getHistory(clientId)
                : List.<org.atmosphere.ai.llm.ChatMessage>of();

        // Build the initial request metadata from the caller's map plus the
        // pipeline's default cache policy. When both sides carry an
        // {@code ai.cache.hint} the caller wins (putIfAbsent semantics on the
        // default path so callers can explicitly downgrade to
        // {@code CacheHint.none()} via the 4-arg execute overload).
        var baseMetadata = extraMetadata != null && !extraMetadata.isEmpty()
                ? new java.util.HashMap<String, Object>(extraMetadata)
                : new java.util.HashMap<String, Object>();
        var pipelinePolicy = this.defaultCachePolicy;
        if (pipelinePolicy != null
                && pipelinePolicy != org.atmosphere.ai.llm.CacheHint.CachePolicy.NONE) {
            baseMetadata.putIfAbsent(
                    org.atmosphere.ai.llm.CacheHint.METADATA_KEY,
                    new org.atmosphere.ai.llm.CacheHint(pipelinePolicy,
                            java.util.Optional.empty(), java.util.Optional.empty()));
        }
        // Seed the pipeline-level default budget into request metadata only
        // when the caller has not provided their own. Caller wins, identical
        // semantics to the cache-hint seeding above.
        var pipelineBudget = this.defaultBudget;
        if (pipelineBudget != null && pipelineBudget.enforced()) {
            baseMetadata.putIfAbsent(AiBudget.METADATA_KEY, pipelineBudget);
        }
        // Same seeding pattern for the confidence elicitation. Caller-supplied
        // elicitation wins over the pipeline default.
        var pipelineElicitation = this.defaultConfidenceElicitation;
        if (pipelineElicitation != null) {
            baseMetadata.putIfAbsent(
                    AiConfidenceElicitation.METADATA_KEY, pipelineElicitation);
        }
        // Seed the structured-output retry default. Caller-supplied retry wins.
        var pipelineRetry = this.defaultStructuredRetry;
        if (pipelineRetry != null && pipelineRetry.enabled()) {
            baseMetadata.putIfAbsent(AiStructuredRetry.METADATA_KEY, pipelineRetry);
        }

        // Per-request ScopePolicy install — an interceptor (e.g., classroom's
        // RoomContextInterceptor) may set a ScopeConfig under
        // ScopePolicy.REQUEST_SCOPE_METADATA_KEY to narrow scope for this
        // one turn. Consume the key here so it doesn't leak into the
        // provider request payload, then compose the transient policy ahead
        // of the endpoint-level chain so the scope-hardening preamble and the
        // pre/post-admission seams all observe it.
        var requestScopePolicy = ScopePolicyInstaller.extract(baseMetadata);
        var effectivePolicies = ScopePolicyInstaller.compose(requestScopePolicy, policies);

        // System-prompt hardening — ScopePolicy in the policy chain triggers
        // an unbypassable confinement preamble prepended to the developer's
        // system prompt. Runs at the pipeline layer (not the processor) so
        // sample code that calls session.stream(...) directly can't forget
        // or bypass it. Unrestricted ScopePolicies contribute nothing.
        var effectiveSystemPrompt = ScopePolicyInstaller.hardenSystemPrompt(
                systemPrompt, effectivePolicies);

        var request = new AiRequest(message, effectiveSystemPrompt, model,
                null, clientId, null, clientId,
                java.util.Map.copyOf(baseMetadata), history);

        // Attach available tools, dynamically pre-filtered to the most relevant
        // when maxToolsPerRequest caps a large catalog (Mode Parity with
        // AiStreamingSession; <= 0 injects every tool).
        if (toolRegistry != null && !toolRegistry.allTools().isEmpty()) {
            request = request.withTools(org.atmosphere.ai.tool.ToolSelection.select(
                    toolRegistry, message, maxToolsPerRequest));
        }

        // Guardrails: inspect request (pre)
        for (var guardrail : guardrails) {
            try {
                var result = guardrail.inspectRequest(request);
                switch (result) {
                    case AiGuardrail.GuardrailResult.Block block -> {
                        logger.warn("Request blocked by guardrail {}: {}",
                                guardrail.getClass().getSimpleName(), block.reason());
                        session.error(new SecurityException("Request blocked: " + block.reason()));
                        return;
                    }
                    case AiGuardrail.GuardrailResult.Modify modify ->
                            request = modify.modifiedRequest();
                    case AiGuardrail.GuardrailResult.Pass ignored -> { }
                }
            } catch (Exception e) {
                logger.error("AiGuardrail.inspectRequest failed: {}",
                        guardrail.getClass().getName(), e);
                session.error(e);
                return;
            }
        }

        // Governance policies: pre-admission (runs after guardrails so a guardrail
        // redaction is visible to the policy evaluation). Exceptions fail-closed
        // — a policy that throws denies the turn, matching the guardrail contract.
        // Each evaluation emits an AuditEntry to GovernanceDecisionLog (ring-
        // buffered for /api/admin/governance/decisions) and an OpenTelemetry span.
        for (var policy : effectivePolicies) {
            var ctx = PolicyContext.preAdmission(request);
            var tracer = GovernanceTracer.start(policy, ctx);
            var startNs = System.nanoTime();
            try {
                var decision = policy.evaluate(ctx);
                var evalMs = (System.nanoTime() - startNs) / 1_000_000.0;
                switch (decision) {
                    case PolicyDecision.Deny deny -> {
                        logger.warn("Request denied by policy {} (source={}, version={}): {}",
                                policy.name(), policy.source(), policy.version(), deny.reason());
                        GovernanceDecisionLog.installed().record(
                                GovernanceDecisionLog.entry(policy, ctx, "deny", deny.reason(), evalMs));
                        tracer.end("deny", deny.reason());
                        session.error(new SecurityException("Request denied by policy "
                                + policy.name() + ": " + deny.reason()));
                        return;
                    }
                    case PolicyDecision.Transform transform -> {
                        GovernanceDecisionLog.installed().record(
                                GovernanceDecisionLog.entry(policy, ctx, "transform",
                                        "request rewritten", evalMs));
                        tracer.end("transform", "request rewritten");
                        request = transform.modifiedRequest();
                    }
                    case PolicyDecision.Prefer prefer -> {
                        // Soft governance: admit unchanged (flow identical to Admit) but
                        // record the preferred alternative so it can be fed back to the
                        // agent on a later turn (GovernanceFeedbackInterceptor).
                        GovernanceDecisionLog.installed().record(
                                GovernanceDecisionLog.preferEntry(policy, ctx,
                                        prefer.preferred(), prefer.reason(), evalMs));
                        tracer.end("prefer", prefer.reason());
                    }
                    case PolicyDecision.Admit ignored -> {
                        GovernanceDecisionLog.installed().record(
                                GovernanceDecisionLog.entry(policy, ctx, "admit", "", evalMs));
                        tracer.end("admit", "");
                    }
                }
            } catch (Exception e) {
                var evalMs = (System.nanoTime() - startNs) / 1_000_000.0;
                logger.error("GovernancePolicy.evaluate failed (policy={}): fail-closed",
                        policy.name(), e);
                GovernanceDecisionLog.installed().record(
                        GovernanceDecisionLog.entry(policy, ctx, "error",
                                "evaluate threw: " + e.getMessage(), evalMs));
                tracer.end("error", e.getMessage(), e);
                session.error(new SecurityException("Policy " + policy.name()
                        + " evaluation failed: " + e.getMessage(), e));
                return;
            }
        }

        // Wrap session in decorators
        StreamingSession target = session;
        if (memory != null) {
            target = new MemoryCapturingSession(target, memory, clientId, message);
        }
        if (metrics != AiMetrics.NOOP) {
            target = new MetricsCapturingSession(target, metrics, model,
                    runtime != null ? runtime.name() : "unknown");
        }
        // Budget circuit breaker — wraps the session so token/step/wall-clock
        // accounting taps every runtime turn. Slotted after MetricsCapturingSession
        // so the metrics layer still sees raw counts on the breaching call (the
        // budget event is data, not a reason to drop the metric). Caller-supplied
        // budget in request metadata wins over the pipeline default; both flow
        // through {@code baseMetadata} above.
        var requestBudget = AiBudget.from(baseMetadata);
        BudgetCapturingSession budgetSession = null;
        if (requestBudget != null && requestBudget.enforced()) {
            budgetSession = new BudgetCapturingSession(target, requestBudget);
            target = budgetSession;
        }
        // Post-response evaluation: guardrails + policies (the latter wrapped so
        // their post-response path flows through the existing capturing session).
        var postResponseChecks = mergeForPostResponse(guardrails, effectivePolicies);
        if (!postResponseChecks.isEmpty()) {
            target = new GuardrailCapturingSession(target, postResponseChecks);
        }

        // Snapshot of the system prompt as the developer (ScopePolicy +
        // guardrails + governance policies) shaped it, captured AFTER all
        // request-mutating evaluations have run but BEFORE any pipeline-driven
        // augmentation. The structured-output and confidence branches below
        // append schema / cue text onto the prompt; we capture each
        // augmentation separately so InputAssemblyTelemetry can attribute the
        // input bill to the stage that introduced it instead of lumping
        // everything into "system" (or worse, missing guardrail / policy
        // mutations entirely — which a snapshot taken before the loops would).
        var baseSystemPrompt = request.systemPrompt();
        String structuredSchemaText = null;
        String confidenceCueText = null;

        // Wrap in StructuredOutputCapturingSession for typed response parsing
        var effectiveResponseType = responseType != null ? responseType : request.responseType();
        if (effectiveResponseType != null && effectiveResponseType != Void.class) {
            var parser = StructuredOutputParser.resolve();
            target = new StructuredOutputCapturingSession(target, parser, effectiveResponseType);
            structuredSchemaText = parser.schemaInstructions(effectiveResponseType);
            // appendStableText keeps a trailing grounded-facts block the
            // absolute suffix: schema text is stable per endpoint, so it must
            // land inside the provider's cacheable prompt prefix, not after
            // the volatile facts (cache-prefix contract).
            request = request.withSystemPrompt(
                    org.atmosphere.ai.facts.FactResolver.FactBundle.appendStableText(
                            request.systemPrompt(), structuredSchemaText));
        }

        // Confidence elicitation — model-reported-field path. Skipped when
        // structured output is in play because the structured-output parser
        // expects the entire response to be a single JSON object matching
        // the declared schema; appending a separate {"confidence": …} block
        // would break the parse. Callers that want confidence alongside
        // structured output should add a {@code confidence} field to their
        // record schema and read it post-parse.
        var requestElicitation = AiConfidenceElicitation.from(baseMetadata);
        if (requestElicitation != null
                && (effectiveResponseType == null || effectiveResponseType == Void.class)) {
            target = new ConfidenceCapturingSession(target, requestElicitation);
            confidenceCueText = requestElicitation.effectiveCue();
            // Same cache-prefix splice as the schema branch above: the cue is
            // stable, so keep it ahead of any trailing grounded-facts block.
            request = request.withSystemPrompt(
                    org.atmosphere.ai.facts.FactResolver.FactBundle.appendStableText(
                            request.systemPrompt(), confidenceCueText));
        }

        // Build execution context from the (potentially guardrail-modified) request
        // so that guardrail modifications to systemPrompt/model AND to the tool
        // list are honored. Pulling tools from the request (not re-reading the
        // registry) means a guardrail that narrows the tool set via
        // {@code AiRequest#withTools} actually takes effect — without this,
        // guardrails could only block the whole request, not drop specific
        // sensitive tools.
        // Phase 0 HITL gap fix: derive a strategy from the pipeline's registry
        // and pass it into the 15-arg constructor so runtime bridges honor
        // @RequiresApproval on this execution path (Mode Parity Invariant #7).
        var tools = request.tools() != null
                ? request.tools()
                : List.<org.atmosphere.ai.tool.ToolDefinition>of();
        var strategy = ApprovalStrategy.virtualThread(approvalRegistry);
        var context = new AgentExecutionContext(
                request.message(), request.systemPrompt(), request.model(),
                null, clientId, null, clientId,
                List.copyOf(tools), null, memory,
                contextProviders, request.metadata(), history,
                effectiveResponseType, strategy);

        if (Boolean.TRUE.equals(request.metadata().get(AiEventForwardingListener.METADATA_KEY))) {
            context = context.withListeners(List.of(new AiEventForwardingListener(target)));
        }

        // Session tape mode parity (Invariant #7): record the input prompt as an
        // `input` step BEFORE the cache gate, so both cache-miss and cache-hit
        // turns produce the same self-contained (prompt → completion) tape. The
        // tape is `session` (wrapped at method entry); no-op when not installed.
        if (session instanceof org.atmosphere.ai.tape.TapeRecordingSession tape) {
            tape.recordInput(request.systemPrompt(), history, request.message());
        }

        // Pipeline-level response cache: when the request opts in via
        // CacheHint and a ResponseCache is installed, check for a prior
        // identical response first. On hit, replay through the session
        // without touching the runtime. On miss, wrap the target session
        // so send() calls are captured for storage on complete().
        //
        // Caching is skipped in any of these cases because CachingStreamingSession
        // only captures text via send() and usage() — tool calls, tool results,
        // structured-output field events, sendMetadata calls, and intermediate
        // lifecycle events are NOT replayable. Serving a cached text-only
        // response for any of these flows would silently drop the non-text
        // frames and could produce wrong answers:
        //   1. context.tools() non-empty — a tool round-trip was intended
        //   2. pipeline has a non-empty toolRegistry — latent tool capability
        //      even on requests where this call's context.tools() happens to
        //      be empty (future request might add tools and share the key)
        //   3. responseType non-null — structured output emits StructuredField
        //      events via StructuredOutputCapturingSession, not plain text
        //   4. contextProviders non-empty — RAG augments the prompt with
        //      retrieved docs at runtime, so identical user messages may map
        //      to different retrieved contexts and thus different answers
        //   5. guardrails non-empty — guardrails may mutate the request at
        //      runtime; a cached response would bypass subsequent filtering
        var cache = responseCache;
        var cacheHint = org.atmosphere.ai.llm.CacheHint.from(context);
        var hasTools = context.tools() != null && !context.tools().isEmpty();
        var registryHasTools = toolRegistry != null && !toolRegistry.allTools().isEmpty();
        var hasStructured = effectiveResponseType != null && effectiveResponseType != Void.class;
        var hasRag = contextProviders != null && !contextProviders.isEmpty();
        var hasGuardrails = !guardrails.isEmpty() || !effectivePolicies.isEmpty();
        var cacheSafe = !hasTools && !registryHasTools && !hasStructured && !hasRag && !hasGuardrails;
        StreamingSession effectiveTarget = target;
        if (cache != null && cacheHint.enabled() && cacheSafe) {
            // A SemanticResponseCache matches near-duplicate prompts by embedding
            // similarity, so it keys on the RAW message rather than the exact
            // CacheKey hash. Exact caches (the default) key on the hash.
            var key = cache instanceof org.atmosphere.ai.cache.SemanticResponseCache
                    ? message
                    : org.atmosphere.ai.cache.CacheKey.compute(context);
            var hit = cache.get(key);
            if (hit.isPresent()) {
                logger.debug("Pipeline response-cache HIT: key={}", key);
                var cached = hit.get();
                var cacheTurn = newTurnEvent(clientId, request.model(), true);
                cacheTurn.begin();
                // Fire lifecycle listeners so observers registered on the
                // context see a clean start/completion pair even on the hit
                // path — matches the cache-miss path where
                // AbstractAgentRuntime.execute fires these internally. Without
                // this, onStart/onCompletion fire on ~50% of traffic and
                // audit/metrics observers go blind on cache hits.
                AbstractAgentRuntime.fireStart(context);
                try {
                    // Gap #7a — framework-level wire signal for a cache hit.
                    // Emitted BEFORE send() so clients see the cache
                    // attribution alongside the replayed text; matches the
                    // naming convention of ai.tokens.input / ai.tokens.output
                    // so observers (specs, metrics, audit) read a single
                    // canonical key instead of sample-level shims.
                    target.sendMetadata(CACHE_HIT_METADATA_KEY, Boolean.TRUE);
                    target.send(cached.text());
                    if (cached.usage() != null) {
                        target.usage(cached.usage());
                    }
                    target.complete();
                    AbstractAgentRuntime.fireCompletion(context);
                    cacheTurn.status = "success";
                } catch (RuntimeException e) {
                    cacheTurn.status = "error";
                    cacheTurn.errorType = e.getClass().getSimpleName();
                    AbstractAgentRuntime.fireError(context, e);
                    throw e;
                } finally {
                    cacheTurn.commit();
                }
                return;
            }
            logger.debug("Pipeline response-cache MISS: key={}", key);
            // Also emit the false wire signal on miss so specs can
            // disambiguate "cache was consulted but missed" from "cache was
            // skipped entirely" (gate short-circuited by tools/RAG/structured).
            target.sendMetadata(CACHE_HIT_METADATA_KEY, Boolean.FALSE);
            effectiveTarget = new org.atmosphere.ai.cache.CachingStreamingSession(
                    target, key, responseCacheTtl, cache::put);
        } else if (cache != null && cacheHint.enabled() && logger.isDebugEnabled()) {
            logger.debug("Pipeline response-cache SKIP: hasTools={} registryHasTools={} "
                            + "hasStructured={} hasRag={} hasGuardrails={}",
                    hasTools, registryHasTools, hasStructured, hasRag, hasGuardrails);
        }

        // Thread the effective governance policy chain through the tool-execution
        // injectables so ToolExecutionHelper runs per-tool-call admission on this
        // resource-free dispatch path (channel bridges, A2A, AG-UI, coordinator-
        // local). The pipeline has no AtmosphereResource to read policies off, so
        // without this the MS-schema tool_name deny rules that fire on the
        // @AiEndpoint path would be silently skipped here (Mode Parity #7,
        // Security #6). Mutually exclusive with the resource branch in
        // ToolExecutionHelper — no double admission.
        if (!effectivePolicies.isEmpty()) {
            effectiveTarget = new GovernancePolicyInjectingSession(
                    effectiveTarget,
                    new org.atmosphere.ai.governance.GovernancePolicyChain(effectivePolicies));
        }

        // Thread the endpoint's registration-time injectables (AgentFleet,
        // AgentState, AgentPlanStore, AgentFileSystemProvider, ...) through
        // the tool-execution scope on this resource-free path, exactly as
        // AiEndpointHandler publishes them on the web path — the same
        // registered tools must resolve their framework parameters on every
        // invocation mode (Mode Parity #7). Dispatch-time entries win on
        // key conflict. The stable clientId (channel client / AG-UI thread)
        // rides along as the ConversationScope: collector sessions mint a
        // fresh sessionId per message, so without it plans and workspace
        // files would silently reset every turn (Invariants #7/#3).
        if (!toolInjectables.isEmpty()) {
            var scoped = new java.util.LinkedHashMap<Class<?>, Object>(toolInjectables);
            if (clientId != null && !clientId.isBlank()) {
                scoped.putIfAbsent(org.atmosphere.ai.tool.ToolScopes.ConversationScope.class,
                        new org.atmosphere.ai.tool.ToolScopes.ConversationScope(clientId));
            }
            effectiveTarget = new ToolInjectablesSession(effectiveTarget, scoped);
        }

        // Per-stage input breakdown — only emitted on the path that actually
        // dispatches to the runtime (the cache-hit branch returned above).
        // Cache misses through CachingStreamingSession DO reach the runtime,
        // so the breakdown is correctly attributed there.
        InputAssemblyTelemetry.emit(metrics, request.model(),
                baseSystemPrompt, structuredSchemaText, confidenceCueText,
                tools, history, request.message());

        var turnEvent = newTurnEvent(clientId, request.model(), false);
        turnEvent.begin();
        try {
            // executeWithHandle parity with AiStreamingSession: runtimes that
            // override get a real cancel handle, default-impl runtimes return a
            // pre-completed handle so whenDone().join() is a no-op. This
            // matches Mode Parity (Invariant #7) — the pipeline path now
            // exposes the same cancel seam @AiEndpoint uses, even though the
            // pipeline has no AtmosphereResource and therefore no transport
            // disconnect to drive cancel. Direct callers can now hold the
            // handle externally if they want it.
            // Self-healing structured-output reprompt loop. When the request
            // declares a response type AND opts in via AiStructuredRetry, a
            // schema/parse failure re-invokes the runtime with the validation
            // error as feedback (bounded, fail-closed) instead of silently
            // emitting nothing. Disabled by default → identical single-shot
            // behavior. Lives at the runtime seam because the structured
            // capturing session can't re-invoke the runtime itself.
            var structuredRetry = hasStructured ? AiStructuredRetry.from(baseMetadata) : null;
            // Provider-native structured output: when the request declares a
            // response type and the resolved runtime advertises
            // NATIVE_STRUCTURED_OUTPUT, thread the generated JSON Schema into the
            // provider's native schema field so non-conforming output cannot be
            // emitted by the model — rather than relying solely on the
            // prompt-injection + parse path above. Governed by the tri-state
            // NativeStructuredOutputMode (AUTO default, with graceful fall-back).
            var nativeMode = AiConfig.resolveNativeStructuredOutputMode();
            var nativeEligible = hasStructured
                    && nativeMode != NativeStructuredOutputMode.DISABLED
                    && runtime.capabilities().contains(AiCapability.NATIVE_STRUCTURED_OUTPUT);
            ExecutionHandle handle;
            if (structuredRetry != null && structuredRetry.enabled()) {
                // Opt-in reprompt loop. Apply native enforcement on each attempt
                // when eligible by stamping the apply flag; the loop's schema-parse
                // retries remain the safety net (a hard provider rejection
                // propagates here rather than falling back — the AUTO graceful
                // fall-back lives on the default, non-reprompt path below).
                var retryContext = nativeEligible
                        ? NativeStructuredOutput.withApply(context,
                                NativeStructuredOutput.schemaFor(effectiveResponseType))
                        : context;
                handle = StructuredOutputRetry.executeWithHandle(runtime, retryContext, effectiveTarget,
                        StructuredOutputParser.resolve(), effectiveResponseType,
                        structuredRetry.maxRetries());
            } else if (nativeEligible) {
                handle = NativeStructuredDispatch.executeWithHandle(
                        runtime, context, effectiveTarget, nativeMode);
            } else {
                handle = runtime.executeWithHandle(context, effectiveTarget);
            }
            // Bind the budget's cancel hook to the runtime handle so a
            // wall-clock or token trip cancels the in-flight runtime call
            // instead of leaving the provider hung after the error frame.
            if (budgetSession != null) {
                budgetSession.setOnTrip(handle::cancel);
            }
            try {
                handle.whenDone().join();
            } catch (java.util.concurrent.CompletionException ce) {
                // Runtime completed exceptionally — the captured target's
                // hasErrored() check below skips the commit. The original
                // error was already routed to session.error() by the runtime.
                if (logger.isTraceEnabled()) {
                    var cause = ce.getCause();
                    logger.trace("Pipeline execution completed exceptionally: {}",
                            cause != null ? cause.getMessage() : ce.getMessage());
                }
            }
            // Commit the captured response only after the runtime has
            // terminated AND the session was not marked errored during the
            // stream. A cancel-shaped session.complete() (from a runtime
            // bridge's caller-initiated cancel path) is NOT a valid commit
            // signal because the captured text is guaranteed partial. The
            // cache captor no longer auto-persists on complete() — the
            // pipeline decides here, post-execute, when to persist.
            if (effectiveTarget instanceof org.atmosphere.ai.cache.CachingStreamingSession captor
                    && !effectiveTarget.hasErrored()) {
                captor.commit();
            }
            turnEvent.status = effectiveTarget.hasErrored() ? "error" : "success";
        } catch (Exception e) {
            turnEvent.status = "error";
            turnEvent.errorType = e.getClass().getSimpleName();
            metrics.recordError(model != null ? model : "unknown", "stream_error");
            throw e;
        } finally {
            turnEvent.commit();
        }
    }

    private org.atmosphere.ai.jfr.AgentTurnEvent newTurnEvent(String clientId,
                                                              String requestModel,
                                                              boolean cacheHit) {
        var event = new org.atmosphere.ai.jfr.AgentTurnEvent();
        event.runtime = runtime != null ? runtime.name() : "unknown";
        event.model = requestModel != null ? requestModel : (model != null ? model : "unknown");
        event.clientId = clientId;
        event.cacheHit = cacheHit;
        return event;
    }

    private static List<AiGuardrail> mergeForPostResponse(List<AiGuardrail> guardrails,
                                                         List<GovernancePolicy> policies) {
        if (policies.isEmpty()) {
            return guardrails;
        }
        var merged = new ArrayList<AiGuardrail>(guardrails.size() + policies.size());
        merged.addAll(guardrails);
        for (var policy : policies) {
            merged.add(new PolicyAsGuardrail(policy));
        }
        return List.copyOf(merged);
    }
}
