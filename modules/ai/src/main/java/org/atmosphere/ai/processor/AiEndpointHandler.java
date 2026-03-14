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
import org.atmosphere.ai.AiSupport;
import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.TracingCapturingSession;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
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
import java.util.List;

/**
 * {@link AtmosphereHandler} that bridges an {@link org.atmosphere.ai.annotation.AiEndpoint}
 * annotated class to Atmosphere's lifecycle. Handles connect, disconnect, and message
 * events — delegating prompt handling to the user's {@code @Prompt} method on a virtual thread.
 *
 * <p>Extends {@link AbstractReflectorAtmosphereHandler} so that broadcast responses
 * (streaming texts, progress, completion) are written through the standard output path, which
 * honours the {@code AsyncIOWriter} interceptor chain (including
 * {@code TrackMessageSizeInterceptor}).</p>
 *
 * <h3>Shared injection framework</h3>
 * <p>{@code @AiEndpoint} reuses the same injection infrastructure as
 * {@link org.atmosphere.config.service.ManagedService} via the shared
 * {@link AnnotatedLifecycle} class — no scanning or injection logic is
 * duplicated:</p>
 * <ul>
 *   <li>{@link org.atmosphere.config.service.PathParam @PathParam} fields are
 *       injected per-request via {@link InjectableObjectFactory#requestScoped}</li>
 *   <li>{@link jakarta.inject.Inject @Inject} fields (e.g. {@code Broadcaster},
 *       {@code AtmosphereConfig}) are injected once at registration time</li>
 *   <li>{@link org.atmosphere.config.service.Ready @Ready} and
 *       {@link org.atmosphere.config.service.Disconnect @Disconnect} lifecycle
 *       methods are discovered and invoked on connect/disconnect</li>
 * </ul>
 */
public class AiEndpointHandler extends AbstractReflectorAtmosphereHandler {

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
    private final int paramCount;
    private final long suspendTimeout;
    private final String systemPrompt;
    private final String pathTemplate;
    private final AiSupport aiSupport;
    private final List<AiInterceptor> interceptors;
    private final AiConversationMemory memory;
    private final AnnotatedLifecycle lifecycle;
    private final ToolRegistry toolRegistry;
    private final List<AiGuardrail> guardrails;
    private final List<ContextProvider> contextProviders;
    private final AiMetrics metrics;
    private final List<BroadcastFilter> broadcastFilters;
    private final String endpointModel;

    /**
     * @param target       the user's @AiEndpoint instance
     * @param promptMethod the @Prompt-annotated method
     * @param timeout      per-resource suspend timeout in milliseconds
     * @param systemPrompt the system prompt from the @AiEndpoint annotation (may be empty)
     * @param aiSupport    the resolved AI support implementation
     * @param interceptors the interceptor chain
     */
    public AiEndpointHandler(Object target, Method promptMethod, long timeout,
                             String systemPrompt, AiSupport aiSupport,
                             List<AiInterceptor> interceptors) {
        this(target, promptMethod, timeout, systemPrompt, null, aiSupport, interceptors,
                null, AnnotatedLifecycle.scan(target.getClass()),
                new DefaultToolRegistry(), List.of(), List.of(), AiMetrics.NOOP, List.of(), null);
    }

    /**
     * @param target       the user's @AiEndpoint instance
     * @param promptMethod the @Prompt-annotated method
     * @param timeout      per-resource suspend timeout in milliseconds
     * @param systemPrompt the system prompt from the @AiEndpoint annotation (may be empty)
     * @param pathTemplate the path template from the @AiEndpoint annotation (e.g. "/chat/{room}")
     * @param aiSupport    the resolved AI support implementation
     * @param interceptors the interceptor chain
     * @param memory       conversation memory (may be null if disabled)
     * @param lifecycle    the shared lifecycle descriptor from {@link AnnotatedLifecycle#scan}
     */
    public AiEndpointHandler(Object target, Method promptMethod, long timeout,
                             String systemPrompt, String pathTemplate,
                             AiSupport aiSupport,
                             List<AiInterceptor> interceptors,
                             AiConversationMemory memory,
                             AnnotatedLifecycle lifecycle) {
        this(target, promptMethod, timeout, systemPrompt, pathTemplate, aiSupport,
                interceptors, memory, lifecycle,
                new DefaultToolRegistry(), List.of(), List.of(), AiMetrics.NOOP, List.of(), null);
    }

    /**
     * Full constructor with tools, guardrails, context providers, and metrics.
     */
    public AiEndpointHandler(Object target, Method promptMethod, long timeout,
                             String systemPrompt, String pathTemplate,
                             AiSupport aiSupport,
                             List<AiInterceptor> interceptors,
                             AiConversationMemory memory,
                             AnnotatedLifecycle lifecycle,
                             ToolRegistry toolRegistry,
                             List<AiGuardrail> guardrails,
                             List<ContextProvider> contextProviders,
                             AiMetrics metrics) {
        this(target, promptMethod, timeout, systemPrompt, pathTemplate, aiSupport,
                interceptors, memory, lifecycle, toolRegistry, guardrails,
                contextProviders, metrics, List.of(), null);
    }

    /**
     * Full constructor with broadcast filters.
     */
    public AiEndpointHandler(Object target, Method promptMethod, long timeout,
                             String systemPrompt, String pathTemplate,
                             AiSupport aiSupport,
                             List<AiInterceptor> interceptors,
                             AiConversationMemory memory,
                             AnnotatedLifecycle lifecycle,
                             ToolRegistry toolRegistry,
                             List<AiGuardrail> guardrails,
                             List<ContextProvider> contextProviders,
                             AiMetrics metrics,
                             List<BroadcastFilter> broadcastFilters) {
        this(target, promptMethod, timeout, systemPrompt, pathTemplate, aiSupport,
                interceptors, memory, lifecycle, toolRegistry, guardrails,
                contextProviders, metrics, broadcastFilters, null);
    }

    /**
     * Full constructor with per-endpoint model override.
     */
    public AiEndpointHandler(Object target, Method promptMethod, long timeout,
                             String systemPrompt, String pathTemplate,
                             AiSupport aiSupport,
                             List<AiInterceptor> interceptors,
                             AiConversationMemory memory,
                             AnnotatedLifecycle lifecycle,
                             ToolRegistry toolRegistry,
                             List<AiGuardrail> guardrails,
                             List<ContextProvider> contextProviders,
                             AiMetrics metrics,
                             List<BroadcastFilter> broadcastFilters,
                             String endpointModel) {
        this.target = target;
        this.promptMethod = promptMethod;
        this.paramCount = promptMethod.getParameterCount();
        this.suspendTimeout = timeout;
        this.systemPrompt = systemPrompt != null ? systemPrompt : "";
        this.pathTemplate = pathTemplate;
        this.aiSupport = aiSupport;
        this.interceptors = interceptors != null ? interceptors : List.of();
        this.memory = memory;
        this.lifecycle = lifecycle;
        this.toolRegistry = toolRegistry;
        this.guardrails = guardrails != null ? guardrails : List.of();
        this.contextProviders = contextProviders != null ? contextProviders : List.of();
        this.metrics = metrics != null ? metrics : AiMetrics.NOOP;
        this.broadcastFilters = broadcastFilters != null ? broadcastFilters : List.of();
        this.endpointModel = endpointModel;
        this.promptMethod.setAccessible(true);
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
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        var resource = event.getResource();

        if (event.isClosedByClient() || event.isClosedByApplication()) {
            if (memory != null) {
                memory.clear(resource.uuid());
            }
            lifecycle.onDisconnect(target, event);
            logger.info("Client {} disconnected from AI endpoint", resource.uuid());
            return;
        }

        if (event.isCancelled()) {
            if (memory != null) {
                memory.clear(resource.uuid());
            }
            lifecycle.onDisconnect(target, event);
            logger.info("Client {} unexpectedly disconnected from AI endpoint", resource.uuid());
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

        var session = new AiStreamingSession(traced, aiSupport,
                systemPrompt, model, interceptors, resource, memory,
                toolRegistry, guardrails, contextProviders, metrics);

        Thread.startVirtualThread(() -> {
            try {
                invokePrompt(userMessage, session, resource);
            } catch (Exception e) {
                logger.error("Error invoking @Prompt method", e);
                session.error(e);
            }
        });
    }

    private void invokePrompt(String message, StreamingSession session, AtmosphereResource resource)
            throws InvocationTargetException, IllegalAccessException {
        if (paramCount == 3) {
            promptMethod.invoke(target, message, session, resource);
        } else {
            promptMethod.invoke(target, message, session);
        }
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

    AiSupport aiSupport() {
        return aiSupport;
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
}
