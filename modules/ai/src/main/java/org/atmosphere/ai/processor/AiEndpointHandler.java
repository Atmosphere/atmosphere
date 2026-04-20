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
package org.atmosphere.ai.processor;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiConversationMemory;
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiStreamingSession;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.DefaultStreamingSession;
import org.atmosphere.ai.PostPromptHook;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.TracingCapturingSession;
import org.atmosphere.ai.approval.ApprovalRegistry;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceHeartbeatEventListener;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * {@link AtmosphereHandler} for {@link org.atmosphere.ai.annotation.AiEndpoint @AiEndpoint}
 * classes. Handles connect, disconnect, and message events, dispatching prompt handling
 * to the {@code @Prompt} method on a virtual thread. Broadcast responses flow through
 * the standard {@code AsyncIOWriter} interceptor chain.
 */
public class AiEndpointHandler extends AbstractReflectorAtmosphereHandler
        implements AtmosphereResourceHeartbeatEventListener {

    /**
     * Request attribute key for the system prompt configured on the {@code @AiEndpoint}.
     * The {@code @Prompt} method can retrieve this via
     * {@code resource.getRequest().getAttribute(AiEndpointHandler.SYSTEM_PROMPT_ATTRIBUTE)}.
     */
    public static final String SYSTEM_PROMPT_ATTRIBUTE = "org.atmosphere.ai.systemPrompt";

    private static final Logger logger = LoggerFactory.getLogger(AiEndpointHandler.class);

    /**
     * Request attribute prefix for path parameters extracted from the
     * {@code @AiEndpoint} path template. For example, a path template
     * {@code /atmosphere/chat/{room}} sets the attribute
     * {@code org.atmosphere.ai.pathParam.room} on each request.
     */
    public static final String PATH_PARAM_ATTRIBUTE_PREFIX = "org.atmosphere.ai.pathParam.";

    private final Object target;
    private final Method promptMethod;
    private final long suspendTimeout;
    private final String systemPrompt;
    private final String pathTemplate;
    private final AgentRuntime runtime;
    private final List<AiInterceptor> interceptors;
    private final AiConversationMemory memory;
    private final AnnotatedLifecycle lifecycle;
    private final ToolRegistry toolRegistry;
    private final List<AiGuardrail> guardrails;
    private final List<ContextProvider> contextProviders;
    private final AiMetrics metrics;
    private final List<BroadcastFilter> broadcastFilters;
    private final String endpointModel;
    private final Map<Class<?>, Object> injectables;

    /**
     * @param target       the user's @AiEndpoint instance
     * @param promptMethod the @Prompt-annotated method
     * @param timeout      per-resource suspend timeout in milliseconds
     * @param systemPrompt the system prompt from the @AiEndpoint annotation (may be empty)
     * @param runtime    the resolved AI support implementation
     * @param interceptors the interceptor chain
     */
    public AiEndpointHandler(Object target, Method promptMethod, long timeout,
                             String systemPrompt, AgentRuntime runtime,
                             List<AiInterceptor> interceptors) {
        this(target, promptMethod, timeout, systemPrompt, null, runtime, interceptors,
                null, AnnotatedLifecycle.scan(target.getClass()),
                new DefaultToolRegistry(), List.of(), List.of(), AiMetrics.NOOP, List.of(), null);
    }

    /**
     * @param target       the user's @AiEndpoint instance
     * @param promptMethod the @Prompt-annotated method
     * @param timeout      per-resource suspend timeout in milliseconds
     * @param systemPrompt the system prompt from the @AiEndpoint annotation (may be empty)
     * @param pathTemplate the path template from the @AiEndpoint annotation (e.g. "/chat/{room}")
     * @param runtime    the resolved AI support implementation
     * @param interceptors the interceptor chain
     * @param memory       conversation memory (may be null if disabled)
     * @param lifecycle    the shared lifecycle descriptor from {@link AnnotatedLifecycle#scan}
     */
    public AiEndpointHandler(Object target, Method promptMethod, long timeout,
                             String systemPrompt, String pathTemplate,
                             AgentRuntime runtime,
                             List<AiInterceptor> interceptors,
                             AiConversationMemory memory,
                             AnnotatedLifecycle lifecycle) {
        this(target, promptMethod, timeout, systemPrompt, pathTemplate, runtime,
                interceptors, memory, lifecycle,
                new DefaultToolRegistry(), List.of(), List.of(), AiMetrics.NOOP, List.of(), null);
    }

    /**
     * Full constructor with tools, guardrails, context providers, and metrics.
     */
    public AiEndpointHandler(Object target, Method promptMethod, long timeout,
                             String systemPrompt, String pathTemplate,
                             AgentRuntime runtime,
                             List<AiInterceptor> interceptors,
                             AiConversationMemory memory,
                             AnnotatedLifecycle lifecycle,
                             ToolRegistry toolRegistry,
                             List<AiGuardrail> guardrails,
                             List<ContextProvider> contextProviders,
                             AiMetrics metrics) {
        this(target, promptMethod, timeout, systemPrompt, pathTemplate, runtime,
                interceptors, memory, lifecycle, toolRegistry, guardrails,
                contextProviders, metrics, List.of(), null);
    }

    /**
     * Full constructor with broadcast filters.
     */
    public AiEndpointHandler(Object target, Method promptMethod, long timeout,
                             String systemPrompt, String pathTemplate,
                             AgentRuntime runtime,
                             List<AiInterceptor> interceptors,
                             AiConversationMemory memory,
                             AnnotatedLifecycle lifecycle,
                             ToolRegistry toolRegistry,
                             List<AiGuardrail> guardrails,
                             List<ContextProvider> contextProviders,
                             AiMetrics metrics,
                             List<BroadcastFilter> broadcastFilters) {
        this(target, promptMethod, timeout, systemPrompt, pathTemplate, runtime,
                interceptors, memory, lifecycle, toolRegistry, guardrails,
                contextProviders, metrics, broadcastFilters, null);
    }

    /**
     * Full constructor with per-endpoint model override.
     */
    public AiEndpointHandler(Object target, Method promptMethod, long timeout,
                             String systemPrompt, String pathTemplate,
                             AgentRuntime runtime,
                             List<AiInterceptor> interceptors,
                             AiConversationMemory memory,
                             AnnotatedLifecycle lifecycle,
                             ToolRegistry toolRegistry,
                             List<AiGuardrail> guardrails,
                             List<ContextProvider> contextProviders,
                             AiMetrics metrics,
                             List<BroadcastFilter> broadcastFilters,
                             String endpointModel) {
        this(target, promptMethod, timeout, systemPrompt, pathTemplate, runtime,
                interceptors, memory, lifecycle, toolRegistry, guardrails,
                contextProviders, metrics, broadcastFilters, endpointModel, Map.of());
    }

    /**
     * Full constructor with injectable parameters for {@code @Prompt} methods.
     * The {@code injectables} map provides additional type-to-instance mappings
     * that are resolved by parameter type when invoking the {@code @Prompt} method.
     * This enables modules like {@code atmosphere-coordinator} to inject custom
     * parameters (e.g. {@code AgentFleet}) without modifying this class.
     *
     * @param injectables extra parameters to inject into {@code @Prompt} methods,
     *                    keyed by parameter type
     */
    public AiEndpointHandler(Object target, Method promptMethod, long timeout,
                             String systemPrompt, String pathTemplate,
                             AgentRuntime runtime,
                             List<AiInterceptor> interceptors,
                             AiConversationMemory memory,
                             AnnotatedLifecycle lifecycle,
                             ToolRegistry toolRegistry,
                             List<AiGuardrail> guardrails,
                             List<ContextProvider> contextProviders,
                             AiMetrics metrics,
                             List<BroadcastFilter> broadcastFilters,
                             String endpointModel,
                             Map<Class<?>, Object> injectables) {
        this.target = target;
        this.promptMethod = promptMethod;
        this.suspendTimeout = timeout;
        this.systemPrompt = systemPrompt != null ? systemPrompt : "";
        this.pathTemplate = pathTemplate;
        this.runtime = runtime;
        this.interceptors = interceptors != null ? interceptors : List.of();
        this.memory = memory;
        this.lifecycle = lifecycle;
        this.toolRegistry = toolRegistry;
        this.guardrails = guardrails != null ? guardrails : List.of();
        this.contextProviders = contextProviders != null ? contextProviders : List.of();
        this.metrics = metrics != null ? metrics : AiMetrics.NOOP;
        this.broadcastFilters = broadcastFilters != null ? broadcastFilters : List.of();
        this.endpointModel = endpointModel;
        this.injectables = injectables != null ? Map.copyOf(injectables) : Map.of();
        this.promptMethod.setAccessible(true);
    }

    /**
     * Endpoint-scoped prompt caching policy from {@code @AiEndpoint.promptCache()}.
     * Every session created by this handler applies the policy via
     * {@link AiStreamingSession#setCachePolicy}.
     */
    private volatile org.atmosphere.ai.llm.CacheHint.CachePolicy cachePolicy;

    /** Endpoint-scoped retry policy from {@code @AiEndpoint.retry()}. */
    private volatile org.atmosphere.ai.RetryPolicy endpointRetryPolicy;

    public void setCachePolicy(org.atmosphere.ai.llm.CacheHint.CachePolicy policy) {
        this.cachePolicy = policy;
    }

    public void setRetryPolicy(org.atmosphere.ai.RetryPolicy policy) {
        this.endpointRetryPolicy = policy;
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        var method = resource.getRequest().getMethod();

        // WebSocket frames arrive as POST requests via SimpleHttpProtocol.
        // The framework creates a temporary resource for each frame with the handler's
        // default broadcaster — NOT the per-path broadcaster assigned during connection.
        // We must look up the per-path broadcaster by URI so the broadcast reaches
        // the suspended resource(s) in the correct room.
        if ("POST".equalsIgnoreCase(method)) {
            AtmosphereRequestImpl.Body body = resource.getRequest().body();
            if (!body.isEmpty()) {
                var msg = body.hasString() ? body.asString() : new String(body.asBytes());
                resolvePerPathBroadcaster(resource).broadcast(msg);
            }
            return;
        }

        // Initial connection: suspend the resource.
        if (resource.transport() == AtmosphereResource.TRANSPORT.WEBSOCKET
                || resource.transport() == AtmosphereResource.TRANSPORT.SSE
                || resource.transport() == AtmosphereResource.TRANSPORT.LONG_POLLING) {
            assignPerPathBroadcaster(resource);
            registerBroadcastFilters(resource.getBroadcaster());
            registerCacheInspector(resource.getBroadcaster());
            resource.suspend(suspendTimeout);
            if (!systemPrompt.isEmpty()) {
                resource.getRequest().setAttribute(SYSTEM_PROMPT_ATTRIBUTE, systemPrompt);
            }
            extractPathParams(resource);
            lifecycle.injectPathParams(target, resource, pathTemplate,
                    resource.getAtmosphereConfig());
            lifecycle.onReady(target, resource);
            logger.info("Client {} connected to AI endpoint (broadcaster: {})",
                    resource.uuid(), resource.getBroadcaster().getID());
            // Consume the X-Atmosphere-Run-Id header that
            // DurableSessionInterceptor stashes on reconnection: if the run
            // is still live in the RunRegistry, replay the buffered events
            // to THIS resource so the client catches up on what it missed
            // mid-stream. Silent no-op when the attr is absent (fresh
            // connection) or the run is unknown (expired or never existed).
            reattachPendingRun(resource);
        }
    }

    /**
     * Consumer path for the {@code X-Atmosphere-Run-Id} header. Closes the
     * P1 gap where the producer (run registration in {@code invokePrompt})
     * was wired but nothing read the id back on reconnection — so reattach
     * was half-shipped. Looks the run up in {@link RunRegistryHolder} and,
     * when it is still live, drains its replay buffer onto this resource
     * only (not the broadcaster — the other subscribers already saw the
     * events live).
     */
    private void reattachPendingRun(AtmosphereResource resource) {
        if (resource == null || resource.getRequest() == null) {
            return;
        }
        // Attribute key matches DurableSessionInterceptor.RUN_ID_ATTRIBUTE —
        // the producer side. Inlined here so the ai module does not pull a
        // compile-time dependency on the durable-sessions module.
        var attr = resource.getRequest().getAttribute("org.atmosphere.session.runId");
        if (attr == null) {
            // Header fallback for clients that bypass the interceptor
            // (e.g. raw WebSocket clients that don't speak the durable-session
            // cookie contract but still carry the run id).
            attr = resource.getRequest().getHeader("X-Atmosphere-Run-Id");
        }
        if (!(attr instanceof String runId) || runId.isBlank()) {
            return;
        }
        var handleOpt = org.atmosphere.ai.resume.RunRegistryHolder.get().lookup(runId);
        if (handleOpt.isEmpty()) {
            logger.debug("Reconnect with run id {} but no live run in the registry "
                    + "(expired or never existed); treating as fresh session", runId);
            return;
        }
        var handle = handleOpt.get();
        var replayed = handle.replayableEvents();
        if (replayed.isEmpty()) {
            logger.info("Reattach run {} for resource {}: no buffered events to replay",
                    runId, resource.uuid());
            return;
        }
        logger.info("Reattach run {} for resource {}: replaying {} event(s)",
                runId, resource.uuid(), replayed.size());
        for (var ev : replayed) {
            try {
                // Write directly to this resource — other resources already
                // saw the event live on the shared broadcaster. Each event's
                // payload is the wire-format string the live pipeline emits.
                resource.write(ev.payload());
            } catch (RuntimeException e) {
                logger.warn("Replay to resource {} failed at event {}: {}",
                        resource.uuid(), ev.sequence(), e.getMessage());
                break;
            }
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        var resource = event.getResource();

        if (event.isClosedByClient() || event.isClosedByApplication()) {
            handleDisconnect(resource, event, "Client {} disconnected from AI endpoint");
            return;
        }

        if (event.isCancelled()) {
            handleDisconnect(resource, event, "Client {} unexpectedly disconnected from AI endpoint");
            return;
        }

        var message = event.getMessage();
        if (message == null) {
            return;
        }

        // RawMessage = broadcast from StreamingSession (streaming texts, progress, complete, error).
        // Unwrap and delegate to AbstractReflectorAtmosphereHandler which writes through
        // the AsyncIOWriter chain (TrackMessageSizeInterceptor adds length-prefix).
        if (message instanceof RawMessage raw) {
            event.setMessage(raw.message());

            boolean resumeOnBroadcast = resource.resumeOnBroadcast();
            if (resumeOnBroadcast) {
                resource.resumeOnBroadcast(false);
                resource.getRequest().setAttribute(
                        ApplicationConfig.RESUME_ON_BROADCAST, false);
            }

            super.onStateChange(event);

            if (resumeOnBroadcast && resource.isSuspended()) {
                resource.resume();
            }
            return;
        }

        // Plain String = user prompt (broadcast from onRequest POST handler).
        // Dispatch to the @Prompt method on a virtual thread.
        var userMessage = message.toString();

        // Fast-path: route approval responses to the existing session's registry
        // instead of creating a new session and dispatching to @Prompt. Walks
        // every session registered for this resource (concurrent prompts on the
        // same socket) and only short-circuits on RESOLVED; a stale/unknown ID
        // in one overlapping session is not allowed to swallow the message
        // before a sibling session that owns it gets a turn.
        if (ApprovalRegistry.isApprovalMessage(userMessage)) {
            if (AiStreamingSession.tryResolveApprovalForResource(resource.uuid(), userMessage)) {
                logger.debug("Approval response routed for resource {}", resource.uuid());
                return;
            }
            // Cross-session fallback removed: scanning all active sessions
            // weakens approval ownership guarantees. If the resource UUID
            // changed after transport reconnect, the original approval
            // times out and the new session must re-trigger the tool call.
            logger.warn("Approval message with no pending approval for resource {}", resource.uuid());
            return;
        }

        // Snapshot business.* request attributes so the VT dispatch below
        // can re-apply them on the virtual-thread's own MDC. MDC is
        // thread-local — setting it on the servlet thread does nothing for
        // logs produced by the VT. Apply + clear is wrapped around the VT
        // body (see promptThread below).
        var businessMdc = snapshotBusinessMdc(resource);
        logger.info("Received prompt from {}: {}", resource.uuid(), userMessage);

        var delegate = StreamingSessions.start(resource);
        var settings = AiConfig.get();
        var model = endpointModel != null ? endpointModel
                : (settings != null ? settings.model() : null);

        // Wrap with TracingCapturingSession for top-level observability.
        // This captures metrics for ALL session usage paths (stream(), send(), complete()).
        StreamingSession traced = delegate;
        if (metrics != AiMetrics.NOOP) {
            traced = new TracingCapturingSession(delegate, metrics, model);
        }

        var responseType = injectables.get(Class.class) instanceof Class<?> c ? c : null;
        // Prepend grounded facts to the system prompt via FactResolver.
        // DefaultFactResolver supplies time.now + time.timezone; apps can
        // install a richer resolver via FactResolverHolder.install(). Every
        // turn pays one resolver call per endpoint — no ThreadLocal, no
        // per-@AiTool wiring.
        var effectivePrompt = prependResolvedFacts(systemPrompt, resource);
        var session = new AiStreamingSession(traced, runtime,
                effectivePrompt, model, interceptors, resource, memory,
                toolRegistry, guardrails, contextProviders, metrics, responseType);
        AiStreamingSession.registerActive(session);

        // Propagate endpoint-scoped cache / retry policies from @AiEndpoint.
        if (cachePolicy != null) {
            session.setCachePolicy(cachePolicy);
        }
        if (endpointRetryPolicy != null) {
            session.setRetryPolicy(endpointRetryPolicy);
        }

        // Publish the handler's injectables map (AgentFleet, AgentIdentity,
        // AgentState, ...) onto the session so @AiTool methods can declare
        // these types as parameters — no ThreadLocal shim required.
        // Include the live AtmosphereResource so tools can reach request
        // attributes (user id, session id) without a separate accessor.
        if (!injectables.isEmpty() || resource != null) {
            var toolScope = new java.util.LinkedHashMap<Class<?>, Object>(injectables);
            if (resource != null) {
                toolScope.putIfAbsent(AtmosphereResource.class, resource);
            }
            session.setInjectables(toolScope);
        }

        // Set pre-stream hook so journal cards emit before LLM starts
        if (injectables.get(PostPromptHook.class) instanceof PostPromptHook hook) {
            session.setPreStreamHook(hook);
        }

        // Register this run with the process-wide RunRegistry so a
        // reconnecting client can look up the live AgentResumeHandle via the
        // X-Atmosphere-Run-Id header (Primitive #8 — now with a real
        // producer rather than a published-but-dead API).
        // Cancelling the handle via RunRegistry (or admin plane) must
        // actually interrupt the @Prompt virtual thread; we close over
        // promptThreadRef after it's started below.
        var promptThreadRef = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var runExecutionHandle = new org.atmosphere.ai.ExecutionHandle.Settable(() -> {
            var t = promptThreadRef.get();
            if (t != null) {
                t.interrupt();
            }
        });
        // Default producer for the ai.userId request attribute that the
        // rest of the ai module reads (ToolExecutionHelper.resolveMode,
        // AiStreamingSession.stream, this handler's RunRegistry register):
        // if nothing upstream has set it, fall back to the servlet
        // Principal's name. Without this hook, PermissionMode resolution
        // always saw null and fell to DEFAULT regardless of the
        // AgentIdentity wiring. Apps that integrate their own auth stack
        // set ai.userId via an AtmosphereInterceptor and this no-op's.
        if (resource.getRequest() != null
                && resource.getRequest().getAttribute("ai.userId") == null) {
            try {
                var principal = resource.getRequest().getUserPrincipal();
                if (principal != null && principal.getName() != null
                        && !principal.getName().isBlank()) {
                    resource.getRequest().setAttribute("ai.userId", principal.getName());
                }
            } catch (RuntimeException e) {
                logger.trace("unable to resolve userPrincipal for request attr", e);
            }
        }
        var userIdAttr = resource.getRequest() != null
                ? resource.getRequest().getAttribute("ai.userId") : null;
        var runUserId = userIdAttr != null ? userIdAttr.toString() : "anonymous";
        var handle = org.atmosphere.ai.resume.RunRegistryHolder.get().register(
                pathTemplate, runUserId, resource.uuid(), runExecutionHandle);
        session.setRunId(handle.runId());

        var promptThread = Thread.startVirtualThread(() -> {
            // Apply business.* MDC on the VT so every log record emitted
            // during the turn (pipeline / runtime / tool calls) carries the
            // tenant/customer/session tags. Clear in finally — otherwise
            // the VT pool leaks keys across turns.
            businessMdc.forEach(org.slf4j.MDC::put);
            try {
                invokePrompt(userMessage, session, resource);
                runExecutionHandle.complete();
            } catch (Exception e) {
                Throwable cause = e;
                if (e instanceof java.lang.reflect.InvocationTargetException ite
                        && ite.getCause() != null) {
                    cause = ite.getCause();
                }
                if (cause instanceof Error err) {
                    runExecutionHandle.completeExceptionally(err);
                    throw err;
                }
                logger.error("Error invoking @Prompt method", cause);
                session.error(cause);
                runExecutionHandle.completeExceptionally(cause);
            } finally {
                businessMdc.keySet().forEach(org.slf4j.MDC::remove);
            }
        });
        promptThreadRef.set(promptThread);

        if (suspendTimeout > 0) {
            Thread.startVirtualThread(() -> {
                try {
                    if (!promptThread.join(Duration.ofMillis(suspendTimeout))) {
                        logger.warn("@Prompt method timed out after {}ms for client {}",
                                suspendTimeout, resource.uuid());
                        promptThread.interrupt();
                        if (!session.isClosed()) {
                            session.error(new TimeoutException(
                                    "Prompt processing timed out after " + suspendTimeout + "ms"));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    /**
     * Framework-scoped FactResolver resolution. Same template as
     * {@code CoordinatorProcessor.resolveJournal} /
     * {@code BroadcasterFactory.lookup} — the resolver is tied to the
     * {@link org.atmosphere.cpr.AtmosphereFramework} instance through its
     * properties map, not a process-wide static, so multi-framework JVMs
     * and tests stay isolated.
     */
    private org.atmosphere.ai.facts.FactResolver resolveFactResolver(AtmosphereResource resource) {
        // 1. Framework-property bridge (Spring/Quarkus/CDI auto-config writes here).
        if (resource != null && resource.getAtmosphereConfig() != null) {
            var bridged = resource.getAtmosphereConfig().properties()
                    .get(org.atmosphere.ai.facts.FactResolver.FACT_RESOLVER_PROPERTY);
            if (bridged instanceof org.atmosphere.ai.facts.FactResolver fr) {
                return fr;
            }
        }
        // 2. ServiceLoader — plain servlet / embedded / Quarkus native deployments.
        try {
            var loaded = java.util.ServiceLoader.load(org.atmosphere.ai.facts.FactResolver.class)
                    .findFirst();
            if (loaded.isPresent()) {
                return loaded.get();
            }
        } catch (java.util.ServiceConfigurationError | RuntimeException e) {
            logger.debug("FactResolver ServiceLoader lookup failed: {}", e.getMessage());
        }
        // 3. Legacy process-wide holder — kept for tests that install per-test
        //    resolvers and for libraries that set a resolver before any framework
        //    instance exists. New code should use the framework-property bridge.
        var held = org.atmosphere.ai.facts.FactResolverHolder.get();
        if (held != null) {
            return held;
        }
        // 4. Zero-dep default.
        return new org.atmosphere.ai.facts.DefaultFactResolver();
    }

    /**
     * Snapshot {@code business.*} request attributes into a map the VT
     * dispatch below re-applies onto its own SLF4J MDC. Returning a
     * snapshot (instead of mutating MDC directly) avoids the
     * servlet-thread-vs-VT mismatch: MDC is thread-local, so calls made
     * on the servlet thread would never reach the VT's log records.
     *
     * <p>Keys are the {@link org.atmosphere.ai.business.BusinessMetadata}
     * constants; unknown event-kind strings are normalized via
     * {@link org.atmosphere.ai.business.BusinessMetadata.EventKind#fromWire(String)}
     * so downstream consumers see a canonical value.</p>
     */
    private java.util.Map<String, String> snapshotBusinessMdc(AtmosphereResource resource) {
        if (resource == null || resource.getRequest() == null) {
            return java.util.Map.of();
        }
        var req = resource.getRequest();
        var snapshot = new java.util.LinkedHashMap<String, String>();
        for (var key : java.util.List.of(
                org.atmosphere.ai.business.BusinessMetadata.TENANT_ID,
                org.atmosphere.ai.business.BusinessMetadata.CUSTOMER_ID,
                org.atmosphere.ai.business.BusinessMetadata.CUSTOMER_SEGMENT,
                org.atmosphere.ai.business.BusinessMetadata.SESSION_ID,
                org.atmosphere.ai.business.BusinessMetadata.SESSION_CURRENCY,
                org.atmosphere.ai.business.BusinessMetadata.EVENT_SUBJECT)) {
            if (req.getAttribute(key) instanceof String s && !s.isBlank()) {
                snapshot.put(key, s);
            }
        }
        if (req.getAttribute(org.atmosphere.ai.business.BusinessMetadata.EVENT_KIND)
                instanceof String kind && !kind.isBlank()) {
            // Normalize through the enum so OTHER is emitted on unknown wire
            // strings rather than leaking whatever free-form value came in.
            snapshot.put(org.atmosphere.ai.business.BusinessMetadata.EVENT_KIND,
                    org.atmosphere.ai.business.BusinessMetadata.EventKind.fromWire(kind).wireName());
        }
        return snapshot;
    }

    /**
     * Resolve the live {@link org.atmosphere.ai.facts.FactResolver} and prepend
     * its output as a {@code facts} block to the base system prompt.
     * Framework-scoped resolution mirrors {@code CoordinatorProcessor.resolveJournal}
     * — check {@code framework.properties()}, then {@link java.util.ServiceLoader},
     * then {@link org.atmosphere.ai.facts.FactResolverHolder} (legacy), then
     * {@link org.atmosphere.ai.facts.DefaultFactResolver}. Returns the
     * original prompt unchanged when the bundle is empty so this never
     * regresses the text path when the feature is not in use.
     */
    private String prependResolvedFacts(String basePrompt, AtmosphereResource resource) {
        var resolver = resolveFactResolver(resource);
        if (resolver == null) {
            return basePrompt != null ? basePrompt : "";
        }
        String userId = null;
        if (resource != null && resource.getRequest() != null) {
            var att = resource.getRequest().getAttribute("ai.userId");
            if (att instanceof String s && !s.isBlank()) {
                userId = s;
            }
        }
        // Derive agentId from the endpoint path template — "/atmosphere/agent/{name}"
        // for @Agent-registered endpoints. Null when the handler is a plain
        // @AiEndpoint at a custom path, in which case downstream resolvers
        // treat the request as agent-unscoped.
        String agentId = null;
        if (pathTemplate != null && pathTemplate.startsWith("/atmosphere/agent/")) {
            var rest = pathTemplate.substring("/atmosphere/agent/".length());
            var slash = rest.indexOf('/');
            agentId = slash < 0 ? rest : rest.substring(0, slash);
            if (agentId.isBlank()) {
                agentId = null;
            }
        }
        var req = new org.atmosphere.ai.facts.FactResolver.FactRequest(
                userId, resource != null ? resource.uuid() : null,
                agentId,
                java.util.Set.of(
                        org.atmosphere.ai.facts.FactKeys.TIME_NOW,
                        org.atmosphere.ai.facts.FactKeys.TIME_TIMEZONE,
                        org.atmosphere.ai.facts.FactKeys.USER_ID));
        org.atmosphere.ai.facts.FactResolver.FactBundle bundle;
        try {
            bundle = resolver.resolve(req);
        } catch (RuntimeException e) {
            logger.warn("FactResolver {} failed; proceeding without facts: {}",
                    resolver.getClass().getName(), e.getMessage());
            return basePrompt != null ? basePrompt : "";
        }
        if (bundle == null || bundle.facts().isEmpty()) {
            return basePrompt != null ? basePrompt : "";
        }
        var block = bundle.asSystemPromptBlock();
        if (basePrompt == null || basePrompt.isBlank()) {
            return block;
        }
        return block + "\n" + basePrompt;
    }

    private void handleDisconnect(AtmosphereResource resource, AtmosphereResourceEvent event,
                                   String logMessage) {
        notifyInterceptorsOnDisconnect(resource);
        if (memory != null) {
            memory.clear(resource.uuid());
        }
        DefaultStreamingSession.cleanupResource(resource);
        AiStreamingSession.removeAllForResource(resource.uuid());
        lifecycle.onDisconnect(target, event);
        logger.info(logMessage, resource.uuid());
    }

    /**
     * Notify interceptors of disconnect BEFORE memory is cleared so they can
     * extract facts from conversation history.
     */
    private void notifyInterceptorsOnDisconnect(AtmosphereResource resource) {
        if (interceptors.isEmpty()) {
            return;
        }
        var history = memory != null
                ? memory.getHistory(resource.uuid())
                : List.<org.atmosphere.ai.llm.ChatMessage>of();
        var userId = resource.getRequest().getAttribute("ai.userId");
        var userIdStr = userId != null ? userId.toString() : null;
        var conversationId = resource.uuid();
        for (var interceptor : interceptors) {
            try {
                interceptor.onDisconnect(userIdStr, conversationId, history);
            } catch (Exception e) {
                logger.error("AiInterceptor.onDisconnect failed: {}",
                        interceptor.getClass().getName(), e);
            }
        }
    }

    private void invokePrompt(String message, StreamingSession session, AtmosphereResource resource)
            throws InvocationTargetException, IllegalAccessException {
        var paramTypes = promptMethod.getParameterTypes();
        var args = new Object[paramTypes.length];
        for (var i = 0; i < paramTypes.length; i++) {
            if (paramTypes[i] == String.class) {
                args[i] = message;
            } else if (StreamingSession.class.isAssignableFrom(paramTypes[i])) {
                args[i] = session;
            } else if (AtmosphereResource.class.isAssignableFrom(paramTypes[i])) {
                args[i] = resource;
            } else {
                // Exact-key match first (O(1)); assignable-from scan as a
                // fallback so a @Prompt method can declare an SPI interface
                // (AgentState, AgentIdentity, AgentWorkspace) and receive the
                // concrete impl the endpoint processor registered.
                var injectable = injectables.get(paramTypes[i]);
                if (injectable == null) {
                    for (var entry : injectables.entrySet()) {
                        if (paramTypes[i].isAssignableFrom(entry.getKey())) {
                            injectable = entry.getValue();
                            break;
                        }
                    }
                }
                if (injectable != null) {
                    args[i] = injectable;
                } else {
                    throw new IllegalStateException(
                            "Unsupported parameter type in @Prompt method: " + paramTypes[i].getName());
                }
            }
        }
        promptMethod.invoke(target, args);
    }

    // visible for testing
    Object target() {
        return target;
    }

    Method promptMethod() {
        return promptMethod;
    }

    long suspendTimeout() {
        return suspendTimeout;
    }

    String systemPrompt() {
        return systemPrompt;
    }

    AgentRuntime runtime() {
        return runtime;
    }

    List<AiInterceptor> interceptors() {
        return interceptors;
    }

    AiConversationMemory memory() {
        return memory;
    }

    AnnotatedLifecycle lifecycle() {
        return lifecycle;
    }

    ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    List<AiGuardrail> guardrails() {
        return guardrails;
    }

    List<ContextProvider> contextProviders() {
        return contextProviders;
    }

    AiMetrics metrics() {
        return metrics;
    }

    List<BroadcastFilter> broadcastFilters() {
        return broadcastFilters;
    }

    String endpointModel() {
        return endpointModel;
    }

    /**
     * Registers a {@link org.atmosphere.cache.BroadcasterCacheInspector} that
     * only allows {@link RawMessage}-based broadcasts (AI streaming responses)
     * to be cached. Plain String broadcasts (user prompts routed internally
     * from WebSocket frames) are excluded to prevent stale prompt replay on
     * new connections.
     */
    private void registerCacheInspector(Broadcaster broadcaster) {
        var cache = broadcaster.getBroadcasterConfig().getBroadcasterCache();
        if (cache != null) {
            cache.inspector(message -> message.message() instanceof RawMessage);
        }
    }

    /**
     * Registers configured broadcast filters on the broadcaster, guarding
     * against double-registration.
     */
    private void registerBroadcastFilters(Broadcaster broadcaster) {
        if (broadcastFilters.isEmpty()) {
            return;
        }
        var config = broadcaster.getBroadcasterConfig();
        var existing = config.filters();
        for (var filter : broadcastFilters) {
            var alreadyRegistered = false;
            for (var ef : existing) {
                if (ef.getClass().equals(filter.getClass())) {
                    alreadyRegistered = true;
                    break;
                }
            }
            if (!alreadyRegistered) {
                config.addFilter(filter);
            }
        }
    }

    /**
     * Resolves the correct broadcaster for a WebSocket frame dispatch. When the
     * path template contains parameters, the suspended resource's per-path
     * broadcaster is looked up by request URI. Falls back to the resource's
     * current broadcaster when no path template is configured.
     */
    private Broadcaster resolvePerPathBroadcaster(AtmosphereResource resource) {
        if (pathTemplate != null && pathTemplate.contains("{")) {
            var requestUri = resource.getRequest().getRequestURI();
            if (requestUri != null) {
                var factory = resource.getAtmosphereConfig().getBroadcasterFactory();
                var broadcaster = factory.lookup(requestUri, false);
                if (broadcaster != null) {
                    return broadcaster;
                }
            }
        }
        return resource.getBroadcaster();
    }

    /**
     * When the path template contains {@code {param}} placeholders, each unique
     * request path (e.g. {@code /classroom/math} vs {@code /classroom/code})
     * gets its own {@link org.atmosphere.cpr.Broadcaster}. This provides
     * per-path message isolation — messages broadcast in one room stay in that room.
     */
    private void assignPerPathBroadcaster(AtmosphereResource resource) {
        if (pathTemplate == null || !pathTemplate.contains("{")) {
            return;
        }
        var requestUri = resource.getRequest().getRequestURI();
        if (requestUri == null || requestUri.equals(pathTemplate)) {
            return;
        }
        var factory = resource.getAtmosphereConfig().getBroadcasterFactory();
        var broadcaster = factory.lookup(requestUri, true);
        resource.setBroadcaster(broadcaster);
    }

    /**
     * Extracts path parameters from the request URI by matching it against the
     * {@link #pathTemplate}. Each {@code {param}} placeholder is resolved and
     * stored as a request attribute under the key
     * {@code org.atmosphere.ai.pathParam.<name>}.
     */
    private void extractPathParams(AtmosphereResource resource) {
        if (pathTemplate == null || !pathTemplate.contains("{")) {
            return;
        }
        var requestUri = resource.getRequest().getRequestURI();
        if (requestUri == null) {
            return;
        }
        var templateParts = pathTemplate.split("/");
        var uriParts = requestUri.split("/");
        var len = Math.min(templateParts.length, uriParts.length);
        for (var i = 0; i < len; i++) {
            var tpl = templateParts[i];
            if (tpl.startsWith("{") && tpl.endsWith("}")) {
                var name = tpl.substring(1, tpl.length() - 1);
                resource.getRequest().setAttribute(PATH_PARAM_ATTRIBUTE_PREFIX + name, uriParts[i]);
            }
        }
    }

    /**
     * Convenience method for {@link AiInterceptor} implementations to retrieve
     * a path parameter set by this handler.
     *
     * @param resource the current atmosphere resource
     * @param name     the parameter name (e.g. "room")
     * @return the parameter value, or {@code null} if not present
     */
    public static String pathParam(AtmosphereResource resource, String name) {
        return (String) resource.getRequest().getAttribute(PATH_PARAM_ATTRIBUTE_PREFIX + name);
    }

    @Override
    public void onHeartbeat(AtmosphereResourceEvent event) {
        if (lifecycle != null) {
            lifecycle.onHeartbeat(target, event);
        }
    }
}
