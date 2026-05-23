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
package org.atmosphere.ai.spring.alibaba;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.atmosphere.ai.AbstractAgentRuntime;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.Set;

/**
 * {@link org.atmosphere.ai.AgentRuntime} adapter for Spring AI Alibaba's
 * {@link ReactAgent} (Alibaba Cloud AI team —
 * {@code com.alibaba.cloud.ai:spring-ai-alibaba-agent-framework}).
 *
 * <p><b>Buffered streaming.</b> Spring AI Alibaba's agent surface is
 * synchronous — {@code ReactAgent.call(List&lt;Message&gt;)} returns a single
 * {@link AssistantMessage} after the entire ReAct loop completes. There is
 * no native {@code Flux}/streaming agent method as of v1.1.2.0. This
 * adapter therefore delivers the full reply as one
 * {@link StreamingSession#send(String)} chunk followed by
 * {@link StreamingSession#complete()} — the Atmosphere transport still
 * streams that chunk to the client over WebSocket / SSE / long-poll, but
 * there is no incremental token surface from the LLM. Callers that want
 * token-by-token streaming should drive Spring AI's
 * {@code StreamingChatModel} directly via
 * {@code atmosphere-spring-ai} instead, losing Spring AI Alibaba's
 * multi-agent / graph orchestration value.</p>
 *
 * <p>Auto-detected when {@code com.alibaba.cloud.ai.graph.agent.ReactAgent}
 * is on the classpath. The agent itself is wired via Spring (
 * {@link AtmosphereSpringAiAlibabaAutoConfiguration}).</p>
 *
 * <p><b>Capabilities (all unconditional):</b>
 * {@link AiCapability#TEXT_STREAMING} (buffered — see above),
 * {@link AiCapability#SYSTEM_PROMPT}
 * ({@code ReactAgent.setSystemPrompt} threaded per-request, serialized on
 * the agent monitor to avoid singleton-mutation races),
 * {@link AiCapability#STRUCTURED_OUTPUT} (via the pipeline),
 * {@link AiCapability#CONVERSATION_MEMORY},
 * {@link AiCapability#PER_REQUEST_RETRY},
 * {@link AiCapability#TOOL_CALLING} / {@link AiCapability#TOOL_APPROVAL}
 * (via {@link SpringAiAlibabaToolBridge} attached to a per-request
 * {@code ReactAgent} built around the configured Spring AI
 * {@code ChatModel}), and {@link AiCapability#TOKEN_USAGE}.</p>
 *
 * <p><b>How TOKEN_USAGE works.</b> {@code ReactAgent.call} returns an
 * {@link AssistantMessage} that does not surface the underlying
 * {@code ChatResponse} usage metadata. Atmosphere therefore wraps the
 * Spring AI {@code ChatModel} bean once at auto-configuration time in a
 * {@link UsageCapturingChatModel} decorator. Every underlying
 * {@code ChatModel.call(Prompt)} performed by the ReAct graph during a
 * single {@code agent.call(messages)} run pushes its
 * {@code ChatResponseMetadata.getUsage()} into a per-thread accumulator;
 * the runtime resets the accumulator on entry to {@link #doExecute} and
 * reads it on exit, emitting a single {@link org.atmosphere.ai.TokenUsage}
 * record via {@link StreamingSession#usage(org.atmosphere.ai.TokenUsage)}.
 * Custom {@code ReactAgent} beans that bypass auto-configuration may also
 * bypass the wrapper — see
 * {@link AtmosphereSpringAiAlibabaAutoConfiguration} for the wrapping
 * point.</p>
 */
public class SpringAiAlibabaAgentRuntime extends AbstractAgentRuntime<ReactAgent> {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiAlibabaAgentRuntime.class);

    private static volatile ReactAgent staticAgent;
    private static volatile ChatModel staticChatModel;

    /** Inject a pre-built {@link ReactAgent} from Spring auto-configuration. */
    public static void setAgent(ReactAgent agent) {
        staticAgent = agent;
    }

    /** Inject the {@link ChatModel} used to build per-request tool-enabled agents. */
    public static void setChatModel(ChatModel chatModel) {
        staticChatModel = chatModel;
    }

    @Override
    public String name() {
        return "spring-ai-alibaba";
    }

    @Override
    protected String nativeClientClassName() {
        return "com.alibaba.cloud.ai.graph.agent.ReactAgent";
    }

    @Override
    protected String clientDescription() {
        return "ReactAgent";
    }

    @Override
    protected String configurationHint() {
        return "Wire a ReactAgent @Bean (e.g. ReactAgent.builder().model(chatModel)...build()) "
                + "or call SpringAiAlibabaAgentRuntime.setAgent() before the first request.";
    }

    @Override
    protected ReactAgent createNativeClient(AiConfig.LlmSettings settings) {
        return staticAgent;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        if (getNativeClient() == null && staticAgent != null) {
            setNativeClient(staticAgent);
        }
        super.configure(settings);
    }

    @Override
    protected void doExecute(ReactAgent agent,
                             AgentExecutionContext context,
                             StreamingSession session) {
        admitThroughGateway(context);

        var messages = new ArrayList<Message>();
        for (var chat : assembleMessages(context)) {
            messages.add(toSpringMessage(chat));
        }
        // Attach multi-modal parts to the trailing user message. Spring AI
        // Alibaba's ReactAgent.call(List<Message>) forwards Messages straight
        // to the underlying ChatModel, so vision-capable models (Qwen-VL,
        // DashScope vision models) see the Media attachments natively.
        attachMediaToTrailingUserMessage(messages, context.parts());

        // Model-lifecycle hooks: same posture as Spring AI / LC4j / ADK / Koog
        // / Embabel / SK / AgentScope. Spring AI Alibaba is buffered (no
        // incremental token deltas), so onModelEnd fires once at completion
        // with the full duration but no TokenUsage (Alibaba's ReactAgent.call
        // returns AssistantMessage which has no usage surface in v1.1.2.0).
        var listeners = context.listeners();
        var modelName = context.model() != null ? context.model() : name();
        var startNanos = System.nanoTime();
        org.atmosphere.ai.AgentLifecycleListener.fireModelStart(
                listeners, modelName, messages.size(), context.tools().size());

        var activeAgent = agent;
        if (!context.tools().isEmpty()) {
            if (staticChatModel == null) {
                throw new IllegalStateException(
                        "Spring AI Alibaba runtime received an @AiTool-bearing request but no "
                                + "Spring AI ChatModel bean is configured. "
                                + configurationHint());
            }
            activeAgent = ReactAgent.builder()
                    .name("atmosphere-spring-ai-alibaba-tools")
                    .model(staticChatModel)
                    .systemPrompt(context.systemPrompt())
                    .tools(SpringAiAlibabaToolBridge.toToolCallbacks(
                            context.tools(), session, context.approvalStrategy(),
                            context.listeners(), context.approvalPolicy()))
                    .build();
        }

        // TOKEN_USAGE capture: scope a UsageCollector to this dispatch.
        // UsageCapturingChatModel.call(Prompt) accumulates into this collector
        // on every underlying ChatModel call the ReAct graph performs.
        // Skip when the wired ChatModel is not our wrapper (e.g. a user-
        // supplied ReactAgent bypassed auto-config) — TOKEN_USAGE will then
        // be a no-op on this path, consistent with the singleton ReactAgent
        // never seeing the wrapper.
        var captureUsage = staticChatModel instanceof UsageCapturingChatModel;
        UsageCapturingChatModel.UsageCollector collector =
                captureUsage ? UsageCapturingChatModel.beginCapture() : null;

        // Per-request RunnableConfig override: when the caller attached one
        // via SpringAiAlibabaRunnableConfig.attach(...), use the
        // call(messages, config) overload — that's the natural per-invocation
        // handle for thread continuation, checkpoint resume, stream mode,
        // metadata, and store. Without an override we fall back to the no-arg
        // agent.call(messages) — preserves prior behavior.
        var runnableConfig = SpringAiAlibabaRunnableConfig.from(context);
        AssistantMessage response;
        try {
            // Spring-managed ReactAgent is a singleton; ReactAgent.setSystemPrompt
            // mutates state on it. Serializing setSystemPrompt + call on the agent
            // monitor prevents two concurrent requests from clobbering each other's
            // system prompt. assembleMessages already places the system prompt as
            // the first SystemMessage in the messages list (defense in depth);
            // setSystemPrompt is kept because Alibaba's ReactAgent reads its own
            // field in addition to the input messages.
            synchronized (activeAgent) {
                if (context.systemPrompt() != null && !context.systemPrompt().isBlank()) {
                    try {
                        activeAgent.setSystemPrompt(context.systemPrompt());
                    } catch (RuntimeException re) {
                        logger.trace(
                                "ReactAgent.setSystemPrompt threw — falling back to message-level system role",
                                re);
                    }
                }
                if (runnableConfig != null) {
                    logger.debug("Dispatching with per-request Alibaba RunnableConfig override");
                    response = activeAgent.call(messages, runnableConfig);
                } else {
                    response = activeAgent.call(messages);
                }
            }
        } catch (com.alibaba.cloud.ai.graph.exception.GraphRunnerException gre) {
            // Boundary safety (Invariant #4): a checked framework failure
            // must surface through session.error and propagate so the
            // outer pipeline records the right lifecycle event.
            if (captureUsage) {
                UsageCapturingChatModel.endCapture();
            }
            org.atmosphere.ai.AgentLifecycleListener.fireModelError(
                    listeners, modelName, gre);
            session.error(gre);
            throw new IllegalStateException("Spring AI Alibaba ReactAgent failed", gre);
        } catch (RuntimeException re) {
            // Mirror the checked-exception path for any unchecked failure
            // escaping ReactAgent.call so observers see the dispatch error
            // before propagation.
            if (captureUsage) {
                UsageCapturingChatModel.endCapture();
            }
            org.atmosphere.ai.AgentLifecycleListener.fireModelError(
                    listeners, modelName, re);
            throw re;
        }

        // Buffered delivery: one chunk + complete. The Atmosphere transport
        // still streams this chunk to the client; the limitation is that
        // there is no incremental token surface from the LLM.
        if (response != null) {
            var text = response.getText();
            if (text != null && !text.isEmpty()) {
                session.send(text);
            }
        }

        // TOKEN_USAGE: read the accumulator now that the ReAct graph has
        // finished its underlying ChatModel calls. Emit a single typed
        // TokenUsage record via session.usage(...) and ride it on the
        // onModelEnd lifecycle event so listeners observe consistent counts.
        org.atmosphere.ai.TokenUsage tokenUsage = null;
        if (collector != null) {
            try {
                if (collector.hasCounts()) {
                    tokenUsage = new org.atmosphere.ai.TokenUsage(
                            collector.promptTokens(),
                            collector.completionTokens(),
                            0L,
                            collector.totalTokens(),
                            collector.model() != null ? collector.model() : modelName);
                    session.usage(tokenUsage);
                }
            } finally {
                UsageCapturingChatModel.endCapture();
            }
        }

        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        org.atmosphere.ai.AgentLifecycleListener.fireModelEnd(
                listeners, modelName, tokenUsage, durationMs);
        if (!session.isClosed()) {
            session.complete();
        }
    }

    private static Message toSpringMessage(org.atmosphere.ai.llm.ChatMessage msg) {
        return switch (msg.role()) {
            case "system" -> new SystemMessage(msg.content());
            case "assistant" -> new AssistantMessage(msg.content());
            default -> new UserMessage(msg.content());
        };
    }

    /**
     * Replace the trailing {@link UserMessage} with a rebuilt copy that
     * carries any image / audio {@link org.atmosphere.ai.Content} parts as
     * Spring AI {@link org.springframework.ai.content.Media} attachments.
     * No-op when {@code parts} is empty or the trailing message is not a
     * user message (defensive — assembleMessages always places the active
     * user turn last today, but a future shim could change that).
     *
     * <p>{@link org.atmosphere.ai.Content.File} is dropped with a debug
     * log: Spring AI's {@link org.springframework.ai.content.Media} carries
     * a mime type but no file name, and the user-message path is not the
     * conventional surface for arbitrary file uploads (those typically
     * ride tool calls). Same posture as {@code SpringAiAgentRuntime}.</p>
     */
    private static void attachMediaToTrailingUserMessage(
            java.util.List<Message> messages, java.util.List<org.atmosphere.ai.Content> parts) {
        if (parts == null || parts.isEmpty() || messages.isEmpty()) {
            return;
        }
        var lastIndex = messages.size() - 1;
        if (!(messages.get(lastIndex) instanceof UserMessage existing)) {
            return;
        }
        var media = new ArrayList<org.springframework.ai.content.Media>();
        for (var part : parts) {
            if (part instanceof org.atmosphere.ai.Content.Image img) {
                media.add(new org.springframework.ai.content.Media(
                        org.springframework.util.MimeType.valueOf(img.mimeType()),
                        new org.springframework.core.io.ByteArrayResource(img.data())));
            } else if (part instanceof org.atmosphere.ai.Content.Audio audio) {
                media.add(new org.springframework.ai.content.Media(
                        org.springframework.util.MimeType.valueOf(audio.mimeType()),
                        new org.springframework.core.io.ByteArrayResource(audio.data())));
            } else if (!(part instanceof org.atmosphere.ai.Content.Text)) {
                logger.debug("Dropping unsupported multi-modal part {} — "
                        + "Spring AI Media has no matching surface on UserMessage",
                        part.getClass().getSimpleName());
            }
        }
        if (media.isEmpty()) {
            return;
        }
        var rebuilt = UserMessage.builder()
                .text(existing.getText() != null ? existing.getText() : "")
                .media(media.toArray(new org.springframework.ai.content.Media[0]))
                .build();
        messages.set(lastIndex, rebuilt);
    }

    @Override
    public Set<AiCapability> capabilities() {
        return Set.of(
                // Buffered streaming — full reply delivered as one
                // session.send() chunk. Honest because the Atmosphere
                // transport still frames the response as a streamed
                // message; the limitation (no incremental token deltas
                // from the LLM) is documented in the class Javadoc.
                AiCapability.TEXT_STREAMING,
                // setSystemPrompt(...) is threaded per request in doExecute,
                // and assembleMessages also includes a SystemMessage in
                // the List<Message> dispatched to call(...) as a defense
                // in depth.
                AiCapability.SYSTEM_PROMPT,
                // AiPipeline wraps the session in StructuredOutputCapturingSession
                // for every SYSTEM_PROMPT-capable runtime — a single buffered
                // chunk is still a complete final frame the wrapper can parse.
                AiCapability.STRUCTURED_OUTPUT,
                // assembleMessages threads context.history() into the Message
                // list ReactAgent receives, so prior turns are honored.
                AiCapability.CONVERSATION_MEMORY,
                // TOOL_CALLING: doExecute builds a per-request ReactAgent
                // with SpringAiAlibabaToolBridge attached when context.tools()
                // is non-empty. The bridge routes every tool invocation
                // through ToolExecutionHelper.executeWithApproval so
                // @RequiresApproval gates fire uniformly. AtmosphereSpring-
                // AiAlibabaAutoConfiguration guarantees staticChatModel is
                // set whenever a Spring AI ChatModel bean is available; the
                // runtime fails fast with configurationHint() at first tool-
                // bearing dispatch otherwise.
                AiCapability.TOOL_CALLING,
                AiCapability.TOOL_APPROVAL,
                // TOKEN_USAGE: AtmosphereSpringAiAlibabaAutoConfiguration
                // wraps the Spring AI ChatModel bean in a
                // UsageCapturingChatModel decorator at startup. doExecute
                // scopes a per-thread UsageCollector before agent.call(...),
                // every ChatModel.call inside the ReAct graph accumulates
                // ChatResponseMetadata.getUsage() into it, and the runtime
                // emits a single typed TokenUsage record via
                // session.usage(...) after the agent returns.
                AiCapability.TOKEN_USAGE,
                // Inherits AbstractAgentRuntime.executeWithOuterRetry — does
                // not override ownsPerRequestRetry(), so context.retryPolicy()
                // with maxRetries > 0 retries doExecute on pre-stream
                // RuntimeException. See modules/ai/README.md
                // "Per-Request Retry Architecture".
                AiCapability.PER_REQUEST_RETRY,
                // BUDGET_ENFORCEMENT: framework-level circuit breaker via the
                // AiPipeline BudgetCapturingSession decorator. Both wall-
                // clock and token / step budgets now trip because TOKEN_USAGE
                // is declared and populated through the wrapper above.
                AiCapability.BUDGET_ENFORCEMENT,
                // CONFIDENCE_SCORES: framework-level — AiPipeline's
                // ConfidenceCapturingSession parses the model-reported
                // confidence field on stream completion. Honest because
                // Alibaba honors SYSTEM_PROMPT and the buffered AssistantMessage
                // text reaches session.send before complete().
                AiCapability.CONFIDENCE_SCORES,
                // PASSIVATION: AgentPassivation snapshots context.history()
                // into a CheckpointStore. Honest because Alibaba threads
                // history into the Message list ReactAgent receives.
                AiCapability.PASSIVATION,
                // VISION / AUDIO / MULTI_MODAL: attachMediaToTrailingUserMessage
                // rebuilds the active user-turn UserMessage with Spring AI Media
                // attachments. ReactAgent.call forwards Messages straight to the
                // underlying ChatModel, so vision-capable Qwen / DashScope models
                // see image bytes natively. Same posture as
                // SpringAiAgentRuntime — declaring on the Atmosphere SPI side;
                // whether the configured ChatModel honors Media is upstream.
                AiCapability.VISION,
                AiCapability.AUDIO,
                AiCapability.MULTI_MODAL);
    }

    @Override
    public java.util.List<String> models() {
        var settings = AiConfig.get();
        if (settings == null || settings.model() == null || settings.model().isBlank()) {
            return java.util.List.of();
        }
        return java.util.List.of(settings.model());
    }
}
