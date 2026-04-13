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
import org.atmosphere.ai.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public AiPipeline(AgentRuntime runtime, String systemPrompt, String model,
                      AiConversationMemory memory, ToolRegistry toolRegistry,
                      List<AiGuardrail> guardrails, List<ContextProvider> contextProviders,
                      AiMetrics metrics) {
        this(runtime, systemPrompt, model, memory, toolRegistry, guardrails,
                contextProviders, metrics, null);
    }

    public AiPipeline(AgentRuntime runtime, String systemPrompt, String model,
                      AiConversationMemory memory, ToolRegistry toolRegistry,
                      List<AiGuardrail> guardrails, List<ContextProvider> contextProviders,
                      AiMetrics metrics, Class<?> responseType) {
        this.runtime = runtime;
        this.systemPrompt = systemPrompt != null ? systemPrompt : "";
        this.model = model;
        this.memory = memory;
        this.toolRegistry = toolRegistry;
        this.guardrails = guardrails != null ? guardrails : List.of();
        this.contextProviders = contextProviders != null ? contextProviders : List.of();
        this.metrics = metrics != null ? metrics : AiMetrics.NOOP;
        this.responseType = responseType;
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

    /** Exposed so callers can share the registry for cross-pipeline deduplication. */
    public ApprovalRegistry approvalRegistry() {
        return approvalRegistry;
    }

    /**
     * Execute the full AI pipeline for the given message. Runs on the caller's
     * thread (typically a virtual thread from the channel bridge).
     *
     * @param clientId conversation key for memory (e.g., "telegram:12345")
     * @param message  the user's message
     * @param session  the streaming session to push tokens through
     */
    public void execute(String clientId, String message, StreamingSession session) {
        var history = memory != null
                ? memory.getHistory(clientId)
                : List.<org.atmosphere.ai.llm.ChatMessage>of();

        var request = new AiRequest(message, systemPrompt, model,
                null, clientId, null, clientId, Map.of(), history);

        // Attach available tools
        if (toolRegistry != null && !toolRegistry.allTools().isEmpty()) {
            request = request.withTools(toolRegistry.allTools());
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

        // Wrap session in decorators
        StreamingSession target = session;
        if (memory != null) {
            target = new MemoryCapturingSession(target, memory, clientId, message);
        }
        if (metrics != AiMetrics.NOOP) {
            target = new MetricsCapturingSession(target, metrics, model);
        }
        if (!guardrails.isEmpty()) {
            target = new GuardrailCapturingSession(target, guardrails);
        }

        // Wrap in StructuredOutputCapturingSession for typed response parsing
        var effectiveResponseType = responseType != null ? responseType : request.responseType();
        if (effectiveResponseType != null && effectiveResponseType != Void.class) {
            var parser = StructuredOutputParser.resolve();
            target = new StructuredOutputCapturingSession(target, parser, effectiveResponseType);
            request = request.withSystemPrompt(
                    request.systemPrompt() + "\n\n" + parser.schemaInstructions(effectiveResponseType));
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
        var hasGuardrails = !guardrails.isEmpty();
        var cacheSafe = !hasTools && !registryHasTools && !hasStructured && !hasRag && !hasGuardrails;
        StreamingSession effectiveTarget = target;
        if (cache != null && cacheHint.enabled() && cacheSafe) {
            var key = org.atmosphere.ai.cache.CacheKey.compute(context);
            var hit = cache.get(key);
            if (hit.isPresent()) {
                logger.debug("Pipeline response-cache HIT: key={}", key);
                var cached = hit.get();
                // Fire lifecycle listeners so observers registered on the
                // context see a clean start/completion pair even on the hit
                // path — matches the cache-miss path where
                // AbstractAgentRuntime.execute fires these internally. Without
                // this, onStart/onCompletion fire on ~50% of traffic and
                // audit/metrics observers go blind on cache hits.
                AbstractAgentRuntime.fireStart(context);
                try {
                    target.send(cached.text());
                    if (cached.usage() != null) {
                        target.usage(cached.usage());
                    }
                    target.complete();
                    AbstractAgentRuntime.fireCompletion(context);
                } catch (RuntimeException e) {
                    AbstractAgentRuntime.fireError(context, e);
                    throw e;
                }
                return;
            }
            logger.debug("Pipeline response-cache MISS: key={}", key);
            effectiveTarget = new org.atmosphere.ai.cache.CachingStreamingSession(
                    target, key, responseCacheTtl, cache::put);
        } else if (cache != null && cacheHint.enabled() && logger.isDebugEnabled()) {
            logger.debug("Pipeline response-cache SKIP: hasTools={} registryHasTools={} "
                            + "hasStructured={} hasRag={} hasGuardrails={}",
                    hasTools, registryHasTools, hasStructured, hasRag, hasGuardrails);
        }

        try {
            runtime.execute(context, effectiveTarget);
        } catch (Exception e) {
            metrics.recordError(model != null ? model : "unknown", "stream_error");
            throw e;
        }
    }
}
