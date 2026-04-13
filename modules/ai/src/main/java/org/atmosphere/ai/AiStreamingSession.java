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
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link StreamingSession} wrapper that adds {@link #stream(String)} support.
 * Created by the {@code @AiEndpoint} infrastructure to enable the simple pattern:
 *
 * <pre>{@code
 * @Prompt
 * public void onPrompt(String message, StreamingSession session) {
 *     session.stream(message);
 * }
 * }</pre>
 *
 * <p>When {@code stream(message)} is called, this session:</p>
 * <ol>
 *   <li>Loads conversation history from {@link AiConversationMemory} (if enabled)</li>
 *   <li>Builds an {@link AiRequest} from the message + stored system prompt + model + history</li>
 *   <li>Runs {@link AiInterceptor#preProcess} in FIFO order</li>
 *   <li>Wraps the delegate in a {@link MemoryCapturingSession} to capture the response</li>
 *   <li>Delegates to {@link AgentRuntime#stream(AiRequest, StreamingSession)}</li>
 *   <li>Runs {@link AiInterceptor#postProcess} in LIFO order</li>
 * </ol>
 */
public class AiStreamingSession implements StreamingSession {

    private static final Logger logger = LoggerFactory.getLogger(AiStreamingSession.class);

    /**
     * Active sessions keyed by {@link AtmosphereResource#uuid()}. The value is a
     * {@link java.util.concurrent.CopyOnWriteArrayList} because Atmosphere
     * explicitly allows overlapping prompts on a single resource (AG-UI,
     * streaming-chat): two concurrent {@code @Prompt} invocations register two
     * sessions under the same UUID, and registering the second must not clobber
     * the first. Identity-checked removal on complete/error ensures prompt A
     * finishing after prompt B started does not remove B's mapping. Approval
     * routing walks every session in the list for the target UUID and
     * short-circuits only on {@link ApprovalRegistry.ResolveResult#RESOLVED}.
     */
    private static final ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<AiStreamingSession>> ACTIVE_SESSIONS =
            new ConcurrentHashMap<>();

    /**
     * Request attribute key under which the active {@link StreamingSession} is stored,
     * allowing {@link AiInterceptor#postProcess} to send metadata to the client.
     */
    public static final String STREAMING_SESSION_ATTR = "ai.streaming.session";

    private final StreamingSession delegate;
    private final AgentRuntime runtime;
    private final String systemPrompt;
    private final String model;
    private final List<AiInterceptor> interceptors;
    private final AtmosphereResource resource;
    private final AiConversationMemory memory;
    private final ToolRegistry toolRegistry;
    private final List<AiGuardrail> guardrails;
    private final List<ContextProvider> contextProviders;
    private final AiMetrics metrics;
    private final Class<?> responseType;
    private final AtomicReference<PostPromptHook> preStreamHook = new AtomicReference<>();
    private final AtomicBoolean handoffInProgress = new AtomicBoolean();
    private final ApprovalRegistry approvalRegistry = new ApprovalRegistry();
    /** Endpoint-configured prompt cache policy from {@code @AiEndpoint.promptCache()}. */
    private volatile org.atmosphere.ai.llm.CacheHint.CachePolicy cachePolicy;
    /** Endpoint-configured per-request retry policy from {@code @AiEndpoint.retry()}. */
    private volatile org.atmosphere.ai.RetryPolicy endpointRetryPolicy;

    /**
     * @param delegate     the underlying streaming session
     * @param runtime    the resolved AI support implementation
     * @param systemPrompt the system prompt from {@code @AiEndpoint}
     * @param model        the model name (may be null for provider default)
     * @param interceptors the interceptor chain
     * @param resource     the atmosphere resource for this client
     */
    public AiStreamingSession(StreamingSession delegate, AgentRuntime runtime,
                              String systemPrompt, String model,
                              List<AiInterceptor> interceptors,
                              AtmosphereResource resource) {
        this(delegate, runtime, systemPrompt, model, interceptors, resource, null,
                null, List.of(), List.of(), AiMetrics.NOOP, null);
    }

    /**
     * @param delegate     the underlying streaming session
     * @param runtime    the resolved AI support implementation
     * @param systemPrompt the system prompt from {@code @AiEndpoint}
     * @param model        the model name (may be null for provider default)
     * @param interceptors the interceptor chain
     * @param resource     the atmosphere resource for this client
     * @param memory       conversation memory (may be null if disabled)
     */
    public AiStreamingSession(StreamingSession delegate, AgentRuntime runtime,
                              String systemPrompt, String model,
                              List<AiInterceptor> interceptors,
                              AtmosphereResource resource,
                              AiConversationMemory memory) {
        this(delegate, runtime, systemPrompt, model, interceptors, resource, memory,
                null, List.of(), List.of(), AiMetrics.NOOP, null);
    }

    /**
     * Full constructor with tools, guardrails, context providers, and metrics.
     */
    public AiStreamingSession(StreamingSession delegate, AgentRuntime runtime,
                              String systemPrompt, String model,
                              List<AiInterceptor> interceptors,
                              AtmosphereResource resource,
                              AiConversationMemory memory,
                              ToolRegistry toolRegistry,
                              List<AiGuardrail> guardrails,
                              List<ContextProvider> contextProviders) {
        this(delegate, runtime, systemPrompt, model, interceptors, resource, memory,
                toolRegistry, guardrails, contextProviders, AiMetrics.NOOP, null);
    }

    /**
     * Full constructor with metrics and structured output.
     */
    public AiStreamingSession(StreamingSession delegate, AgentRuntime runtime,
                              String systemPrompt, String model,
                              List<AiInterceptor> interceptors,
                              AtmosphereResource resource,
                              AiConversationMemory memory,
                              ToolRegistry toolRegistry,
                              List<AiGuardrail> guardrails,
                              List<ContextProvider> contextProviders,
                              AiMetrics metrics,
                              Class<?> responseType) {
        this.delegate = delegate;
        this.runtime = runtime;
        this.systemPrompt = systemPrompt != null ? systemPrompt : "";
        this.model = model;
        this.interceptors = interceptors != null ? interceptors : List.of();
        this.resource = resource;
        this.memory = memory;
        this.toolRegistry = toolRegistry;
        this.guardrails = guardrails != null ? guardrails : List.of();
        this.contextProviders = contextProviders != null ? contextProviders : List.of();
        this.metrics = metrics != null ? metrics : AiMetrics.NOOP;
        this.responseType = responseType;
    }

    /**
     * Register a session as an active session for its resource. Called by the
     * handler after construction so approval responses can be routed to it.
     * Multiple sessions may be registered under the same UUID (overlapping
     * prompts on one resource); each registration appends to the list.
     *
     * @param session the session to register
     */
    public static void registerActive(AiStreamingSession session) {
        var uuid = session.resource.uuid();
        if (uuid != null) {
            ACTIVE_SESSIONS
                    .computeIfAbsent(uuid, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .add(session);
        }
    }

    /**
     * Try to resolve an approval message against every session currently
     * registered for the given resource UUID. Walks the list in registration
     * order and short-circuits only on
     * {@link ApprovalRegistry.ResolveResult#RESOLVED} so a newer session whose
     * registry does not own the pending ID cannot swallow the message before
     * an older overlapping session has a chance to claim it.
     *
     * @param resourceUuid the atmosphere resource UUID
     * @param message      the incoming approval message
     * @return {@code true} iff some session's registry owned the pending ID and
     *     completed the approval future
     */
    public static boolean tryResolveApprovalForResource(String resourceUuid, String message) {
        if (resourceUuid == null) {
            return false;
        }
        var sessions = ACTIVE_SESSIONS.get(resourceUuid);
        if (sessions == null) {
            return false;
        }
        for (var session : sessions) {
            if (session.approvalRegistry.resolve(message) == ApprovalRegistry.ResolveResult.RESOLVED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reconnect fallback: try to resolve an approval message against every
     * active session across every resource UUID. Used when the resource UUID
     * changed due to transport reconnect and the primary lookup returns null.
     * Short-circuits only on {@link ApprovalRegistry.ResolveResult#RESOLVED};
     * an {@link ApprovalRegistry.ResolveResult#UNKNOWN_ID} from one session is
     * not allowed to consume the message — the next session may own it.
     *
     * @param message the incoming approval message
     * @return {@code true} iff some session's registry owned the pending ID and
     *     completed the approval future
     */
    public static boolean tryResolveAnySession(String message) {
        for (var sessions : ACTIVE_SESSIONS.values()) {
            for (var session : sessions) {
                if (session.approvalRegistry.resolve(message) == ApprovalRegistry.ResolveResult.RESOLVED) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Remove every session registered for a given resource UUID. Called by
     * {@code AiEndpointHandler#handleDisconnect} when the underlying socket
     * is going away and all prompts on it are done.
     *
     * @param resourceUuid the atmosphere resource UUID (may be null)
     */
    public static void removeAllForResource(String resourceUuid) {
        if (resourceUuid != null) {
            ACTIVE_SESSIONS.remove(resourceUuid);
        }
    }

    /**
     * Remove a specific session from the active map on completion/error. Uses
     * identity-checked removal so prompt A finishing after prompt B started on
     * the same resource does not clobber B's mapping. When the list for a UUID
     * becomes empty, the entry is removed from the outer map.
     *
     * @param session the session to remove (identity-checked)
     */
    public static void removeActiveSession(AiStreamingSession session) {
        if (session == null || session.resource == null) {
            return;
        }
        var uuid = session.resource.uuid();
        if (uuid == null) {
            return;
        }
        var sessions = ACTIVE_SESSIONS.get(uuid);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            // Only remove the outer entry if the list is still empty — another
            // thread may have appended between the isEmpty check and the remove.
            ACTIVE_SESSIONS.remove(uuid, sessions);
        }
    }

    /** Sets a hook to fire once at the start of the first {@code stream()} call. */
    public void setPreStreamHook(PostPromptHook hook) {
        this.preStreamHook.set(hook);
    }

    /**
     * Set the endpoint-scoped prompt cache policy from {@code @AiEndpoint.promptCache()}.
     * Every request built by this session attaches a {@link org.atmosphere.ai.llm.CacheHint}
     * with the given policy to the context metadata.
     */
    public void setCachePolicy(org.atmosphere.ai.llm.CacheHint.CachePolicy policy) {
        this.cachePolicy = policy;
    }

    /**
     * Set the endpoint-scoped per-request retry policy from {@code @AiEndpoint.retry()}.
     * Every context built by this session uses this policy instead of the client-level default.
     */
    public void setRetryPolicy(org.atmosphere.ai.RetryPolicy policy) {
        this.endpointRetryPolicy = policy;
    }

    @Override
    public void stream(String message) {
        // Fire pre-stream hook (e.g., journal emit) before LLM call starts.
        // By the time stream() is called, all coordinator/agent work is done.
        var hook = preStreamHook.getAndSet(null);
        if (hook != null) {
            hook.afterPrompt(this);
        }

        // Load conversation history if memory is enabled
        var history = memory != null
                ? memory.getHistory(resource.uuid())
                : List.<org.atmosphere.ai.llm.ChatMessage>of();

        // Populate identity fields from the AtmosphereResource
        var userId = extractAttribute(resource, "ai.userId");
        var sessionId = resource.uuid();
        var agentId = extractAttribute(resource, "ai.agentId");
        var conversationId = extractAttribute(resource, "ai.conversationId");
        if (conversationId == null) {
            conversationId = sessionId;
        }

        var request = new AiRequest(message, systemPrompt, model,
                userId, sessionId, agentId, conversationId, Map.of(), history);

        // Attach available tools to the request
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
                        delegate.error(new SecurityException("Request blocked: " + block.reason()));
                        return;
                    }
                    case AiGuardrail.GuardrailResult.Modify modify ->
                            request = modify.modifiedRequest();
                    case AiGuardrail.GuardrailResult.Pass ignored -> { }
                }
            } catch (Exception e) {
                logger.error("AiGuardrail.inspectRequest failed: {}",
                        guardrail.getClass().getName(), e);
                delegate.error(e);
                return;
            }
        }

        // Context providers: RAG augmentation with query transform + reranking
        if (!contextProviders.isEmpty()) {
            var contextBuilder = new StringBuilder();
            for (var provider : contextProviders) {
                try {
                    var query = provider.transformQuery(request.message());
                    var docs = provider.retrieve(query, 5);
                    docs = provider.rerank(query, docs);
                    for (var doc : docs) {
                        contextBuilder.append("\n---\nSource: ").append(doc.source())
                                .append("\n").append(doc.content());
                    }
                } catch (Exception e) {
                    logger.error("ContextProvider.retrieve failed: {}",
                            provider.getClass().getName(), e);
                    delegate.sendMetadata("rag.error",
                            provider.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            if (!contextBuilder.isEmpty()) {
                var augmented = request.message()
                        + "\n\nRelevant context:" + contextBuilder;
                request = request.withMessage(augmented);
            }
        }

        // Pre-process: FIFO order
        for (var interceptor : interceptors) {
            try {
                request = interceptor.preProcess(request, resource);
            } catch (Exception e) {
                logger.error("AiInterceptor.preProcess failed: {}", interceptor.getClass().getName(), e);
                delegate.error(e);
                return;
            }
        }

        // Wrap delegate in MemoryCapturingSession if memory is enabled
        StreamingSession target = delegate;
        if (memory != null) {
            target = new MemoryCapturingSession(delegate, memory, resource.uuid(), message);
        }

        // Wrap in MetricsCapturingSession for latency/streaming text tracking
        if (metrics != AiMetrics.NOOP) {
            target = new MetricsCapturingSession(target, metrics, model);
        }

        // Wrap in GuardrailCapturingSession for post-LLM response inspection
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

        // Expose the session to interceptors via request attribute
        resource.getRequest().setAttribute(STREAMING_SESSION_ATTR, target);

        // Build execution context and delegate to runtime.
        // The session-scoped ApprovalStrategy is carried on the context so every
        // runtime bridge routes tool execution through ToolExecutionHelper.executeWithApproval().
        // Tools come from the (potentially guardrail-modified) request, NOT from
        // re-reading the registry, so a guardrail that narrows the tool set via
        // {@code AiRequest#withTools} actually takes effect — without this,
        // guardrails could only block the whole request, not drop specific tools.
        var finalRequest = request;
        var tools = finalRequest.tools() != null
                ? finalRequest.tools()
                : java.util.List.<org.atmosphere.ai.tool.ToolDefinition>of();
        var strategy = ApprovalStrategy.virtualThread(approvalRegistry);
        // Merge endpoint-scoped CacheHint into request metadata if configured.
        java.util.Map<String, Object> effectiveMetadata = request.metadata();
        var policy = this.cachePolicy;
        if (policy != null && policy != org.atmosphere.ai.llm.CacheHint.CachePolicy.NONE) {
            var merged = new java.util.HashMap<String, Object>(
                    effectiveMetadata != null ? effectiveMetadata : java.util.Map.of());
            merged.putIfAbsent(org.atmosphere.ai.llm.CacheHint.METADATA_KEY,
                    new org.atmosphere.ai.llm.CacheHint(policy,
                            java.util.Optional.empty(), java.util.Optional.empty()));
            effectiveMetadata = merged;
        }
        var context = new AgentExecutionContext(
                request.message(), request.systemPrompt(), request.model(),
                request.agentId(), request.sessionId(), request.userId(),
                request.conversationId(),
                java.util.List.copyOf(tools), null, memory,
                contextProviders, effectiveMetadata, request.history(),
                effectiveResponseType, strategy);
        // Apply endpoint-scoped retry policy if configured.
        var endpointRetry = this.endpointRetryPolicy;
        if (endpointRetry != null) {
            context = context.withRetryPolicy(endpointRetry);
        }
        var streamingTarget = target;
        try {
            runtime.execute(context, streamingTarget);
        } catch (Exception e) {
            metrics.recordError(model != null ? model : "unknown", "stream_error");
            logger.error("Streaming error", e);
            streamingTarget.error(e);
        }
        try {
            // Post-process follows execution
        } finally {
            // Post-process: LIFO order (matching AtmosphereInterceptor convention)
            for (int i = interceptors.size() - 1; i >= 0; i--) {
                try {
                    interceptors.get(i).postProcess(finalRequest, resource);
                } catch (Exception e) {
                    logger.error("AiInterceptor.postProcess failed: {}",
                            interceptors.get(i).getClass().getName(), e);
                }
            }
        }
    }

    @Override
    public void handoff(String agentName, String message) {
        if (!handoffInProgress.compareAndSet(false, true)) {
            throw new IllegalStateException("Nested handoffs not supported");
        }

        logger.info("Handoff from {} to agent '{}'", resource.uuid(), agentName);
        emit(new AiEvent.Handoff(
                extractAttribute(resource, "ai.agentId"),
                agentName,
                "User request routed to " + agentName));

        // Copy conversation history to the target agent's memory
        if (memory != null) {
            var fromId = resource.uuid();
            var toId = agentName + ":" + fromId;
            memory.copyTo(fromId, toId);
        }

        // Dispatch to the target agent via its handler. We look up the
        // AtmosphereHandlerWrapper and invoke the handler's onStateChange
        // with the message, so the target's @Prompt method runs — including
        // demo mode, tool registration, and all pipeline hooks.
        var framework = resource.getAtmosphereConfig().framework();
        var targetPath = "/atmosphere/agent/" + agentName;
        var handlerWrapper = framework.getAtmosphereHandlers().get(targetPath);
        if (handlerWrapper == null) {
            delegate.error(new IllegalArgumentException(
                    "Agent '" + agentName + "' not found at " + targetPath));
            handoffInProgress.set(false);
            return;
        }

        try {
            // Use the handler's public API: get the handler via the wrapper's
            // accessor and call onStateChange with a crafted event.
            var handler = handlerWrapper.atmosphereHandler();
            var event = new org.atmosphere.cpr.AtmosphereResourceEventImpl(
                    (org.atmosphere.cpr.AtmosphereResourceImpl) resource);
            event.setMessage(message);
            handler.onStateChange(event);
        } catch (Exception e) {
            logger.error("Handoff to '{}' failed", agentName, e);
            delegate.error(e);
        } finally {
            handoffInProgress.set(false);
        }
    }

    /**
     * Check if an incoming message is an approval response and route it
     * to the approval registry. Called by the handler before dispatching to @Prompt.
     *
     * <p>Only returns {@code true} when the registry matched and resolved a
     * pending approval in <em>this</em> session. Stale / unknown IDs and
     * non-approval messages fall through so the handler dispatches them to
     * @Prompt as normal user input.
     *
     * @param message the incoming message
     * @return true if the message resolved a pending approval in this session
     */
    public boolean tryResolveApproval(String message) {
        return approvalRegistry.resolve(message) == ApprovalRegistry.ResolveResult.RESOLVED;
    }

    /** The session-scoped approval registry, exposed for the endpoint handler to route responses. */
    public ApprovalRegistry approvalRegistry() {
        return approvalRegistry;
    }

    // -- Delegate all StreamingSession methods --

    @Override
    public String sessionId() {
        return delegate.sessionId();
    }

    @Override
    public void send(String text) {
        delegate.send(text);
    }

    @Override
    public void sendMetadata(String key, Object value) {
        delegate.sendMetadata(key, value);
    }

    @Override
    public void progress(String message) {
        delegate.progress(message);
    }

    @Override
    public void complete() {
        removeActiveSession(this);
        delegate.complete();
    }

    @Override
    public void complete(String summary) {
        removeActiveSession(this);
        delegate.complete(summary);
    }

    @Override
    public void error(Throwable t) {
        removeActiveSession(this);
        delegate.error(t);
    }

    @Override
    public void emit(AiEvent event) {
        delegate.emit(event);
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public boolean hasErrored() {
        return delegate.hasErrored();
    }

    // visible for testing
    AgentRuntime runtime() {
        return runtime;
    }

    List<AiInterceptor> interceptors() {
        return interceptors;
    }

    String systemPrompt() {
        return systemPrompt;
    }

    private static String extractAttribute(org.atmosphere.cpr.AtmosphereResource resource, String key) {
        var attr = resource.getRequest().getAttribute(key);
        return attr != null ? attr.toString() : null;
    }
}
