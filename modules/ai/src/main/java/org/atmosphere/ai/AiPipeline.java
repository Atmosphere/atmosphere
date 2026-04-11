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
     * @param message the incoming approval-protocol message
     * @return {@code true} if the message was consumed as an approval response
     */
    public boolean tryResolveApproval(String message) {
        return approvalRegistry.tryResolve(message);
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
        // so that guardrail modifications to systemPrompt/model are honored.
        // Phase 0 HITL gap fix: derive a strategy from the pipeline's registry
        // and pass it into the 15-arg constructor so runtime bridges honor
        // @RequiresApproval on this execution path (Mode Parity Invariant #7).
        var tools = toolRegistry != null ? toolRegistry.allTools() : List.<org.atmosphere.ai.tool.ToolDefinition>of();
        var strategy = ApprovalStrategy.virtualThread(approvalRegistry);
        var context = new AgentExecutionContext(
                request.message(), request.systemPrompt(), request.model(),
                null, clientId, null, clientId,
                List.copyOf(tools), null, memory,
                contextProviders, request.metadata(), history,
                effectiveResponseType, strategy);

        try {
            runtime.execute(context, target);
        } catch (Exception e) {
            metrics.recordError(model != null ? model : "unknown", "stream_error");
            throw e;
        }
    }
}
