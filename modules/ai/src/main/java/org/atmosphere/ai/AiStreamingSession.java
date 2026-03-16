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

import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

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
 *   <li>Delegates to {@link AiSupport#stream(AiRequest, StreamingSession)}</li>
 *   <li>Runs {@link AiInterceptor#postProcess} in LIFO order</li>
 * </ol>
 */
public class AiStreamingSession implements StreamingSession {

    private static final Logger logger = LoggerFactory.getLogger(AiStreamingSession.class);

    /**
     * Request attribute key under which the active {@link StreamingSession} is stored,
     * allowing {@link AiInterceptor#postProcess} to send metadata to the client.
     */
    public static final String STREAMING_SESSION_ATTR = "ai.streaming.session";

    private final StreamingSession delegate;
    private final AiSupport aiSupport;
    private final String systemPrompt;
    private final String model;
    private final List<AiInterceptor> interceptors;
    private final AtmosphereResource resource;
    private final AiConversationMemory memory;
    private final ToolRegistry toolRegistry;
    private final List<AiGuardrail> guardrails;
    private final List<ContextProvider> contextProviders;
    private final AiMetrics metrics;

    /**
     * @param delegate     the underlying streaming session
     * @param aiSupport    the resolved AI support implementation
     * @param systemPrompt the system prompt from {@code @AiEndpoint}
     * @param model        the model name (may be null for provider default)
     * @param interceptors the interceptor chain
     * @param resource     the atmosphere resource for this client
     */
    public AiStreamingSession(StreamingSession delegate, AiSupport aiSupport,
                              String systemPrompt, String model,
                              List<AiInterceptor> interceptors,
                              AtmosphereResource resource) {
        this(delegate, aiSupport, systemPrompt, model, interceptors, resource, null,
                null, List.of(), List.of(), AiMetrics.NOOP);
    }

    /**
     * @param delegate     the underlying streaming session
     * @param aiSupport    the resolved AI support implementation
     * @param systemPrompt the system prompt from {@code @AiEndpoint}
     * @param model        the model name (may be null for provider default)
     * @param interceptors the interceptor chain
     * @param resource     the atmosphere resource for this client
     * @param memory       conversation memory (may be null if disabled)
     */
    public AiStreamingSession(StreamingSession delegate, AiSupport aiSupport,
                              String systemPrompt, String model,
                              List<AiInterceptor> interceptors,
                              AtmosphereResource resource,
                              AiConversationMemory memory) {
        this(delegate, aiSupport, systemPrompt, model, interceptors, resource, memory,
                null, List.of(), List.of(), AiMetrics.NOOP);
    }

    /**
     * Full constructor with tools, guardrails, context providers, and metrics.
     */
    public AiStreamingSession(StreamingSession delegate, AiSupport aiSupport,
                              String systemPrompt, String model,
                              List<AiInterceptor> interceptors,
                              AtmosphereResource resource,
                              AiConversationMemory memory,
                              ToolRegistry toolRegistry,
                              List<AiGuardrail> guardrails,
                              List<ContextProvider> contextProviders) {
        this(delegate, aiSupport, systemPrompt, model, interceptors, resource, memory,
                toolRegistry, guardrails, contextProviders, AiMetrics.NOOP);
    }

    /**
     * Full constructor with metrics.
     */
    public AiStreamingSession(StreamingSession delegate, AiSupport aiSupport,
                              String systemPrompt, String model,
                              List<AiInterceptor> interceptors,
                              AtmosphereResource resource,
                              AiConversationMemory memory,
                              ToolRegistry toolRegistry,
                              List<AiGuardrail> guardrails,
                              List<ContextProvider> contextProviders,
                              AiMetrics metrics) {
        this.delegate = delegate;
        this.aiSupport = aiSupport;
        this.systemPrompt = systemPrompt != null ? systemPrompt : "";
        this.model = model;
        this.interceptors = interceptors != null ? interceptors : List.of();
        this.resource = resource;
        this.memory = memory;
        this.toolRegistry = toolRegistry;
        this.guardrails = guardrails != null ? guardrails : List.of();
        this.contextProviders = contextProviders != null ? contextProviders : List.of();
        this.metrics = metrics != null ? metrics : AiMetrics.NOOP;
    }

    @Override
    public void stream(String message) {
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

        // Expose the session to interceptors via request attribute
        resource.getRequest().setAttribute(STREAMING_SESSION_ATTR, target);

        // Delegate to the AI support
        var finalRequest = request;
        try {
            aiSupport.stream(finalRequest, target);
        } catch (Exception e) {
            metrics.recordError(model != null ? model : "unknown", "stream_error");
            throw e;
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
        delegate.complete();
    }

    @Override
    public void complete(String summary) {
        delegate.complete(summary);
    }

    @Override
    public void error(Throwable t) {
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

    // visible for testing
    AiSupport aiSupport() {
        return aiSupport;
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
