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

    private final AiSupport aiSupport;
    private final String systemPrompt;
    private final String model;
    private final AiConversationMemory memory;
    private final ToolRegistry toolRegistry;
    private final List<AiGuardrail> guardrails;
    private final List<ContextProvider> contextProviders;
    private final AiMetrics metrics;

    public AiPipeline(AiSupport aiSupport, String systemPrompt, String model,
                      AiConversationMemory memory, ToolRegistry toolRegistry,
                      List<AiGuardrail> guardrails, List<ContextProvider> contextProviders,
                      AiMetrics metrics) {
        this.aiSupport = aiSupport;
        this.systemPrompt = systemPrompt != null ? systemPrompt : "";
        this.model = model;
        this.memory = memory;
        this.toolRegistry = toolRegistry;
        this.guardrails = guardrails != null ? guardrails : List.of();
        this.contextProviders = contextProviders != null ? contextProviders : List.of();
        this.metrics = metrics != null ? metrics : AiMetrics.NOOP;
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
        // Load conversation history
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
                    session.sendMetadata("rag.error",
                            provider.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            if (!contextBuilder.isEmpty()) {
                var augmented = request.message()
                        + "\n\nRelevant context:" + contextBuilder;
                request = request.withMessage(augmented);
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

        // Delegate to AI backend
        try {
            aiSupport.stream(request, target);
        } catch (Exception e) {
            metrics.recordError(model != null ? model : "unknown", "stream_error");
            throw e;
        }
    }
}
