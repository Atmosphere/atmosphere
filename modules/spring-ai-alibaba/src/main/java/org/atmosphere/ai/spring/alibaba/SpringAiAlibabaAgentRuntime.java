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
import org.atmosphere.ai.ExecutionHandle;
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
 * {@code ChatModel}), {@link AiCapability#PLANNING} (harness planning
 * delegated to Alibaba's native {@code TodoListInterceptor} via
 * {@link SpringAiAlibabaPlanBridge} — persisted in Atmosphere's
 * {@link org.atmosphere.ai.plan.AgentPlanStore}, surfaced as
 * {@code AiEvent.PlanUpdate}), and {@link AiCapability#TOKEN_USAGE}.</p>
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

        // Native planning surface (AiCapability.PLANNING): when the endpoint's
        // injectables carry the AgentPlanStore (Harness.PLANNING resolved) and
        // the mode allows a native surface, provision a per-request
        // TodoListInterceptor bridged to Atmosphere's store + PlanUpdate
        // events. Alibaba's todos live in per-invocation graph state and this
        // adapter rebuilds the agent per request, so the previous turn's live
        // plan re-hydrates through the system prompt (see
        // SpringAiAlibabaPlanBridge).
        var planning = provisionPlanSurface(context, session);
        if (planning != null && staticChatModel == null && context.tools().isEmpty()) {
            // Graceful degradation: a user-supplied ReactAgent without a
            // ChatModel bean is a documented setup that dispatched fine
            // before planning existed — a default-on harness primitive must
            // not turn it into a hard failure. The dispatch proceeds without
            // a plan surface (the tools-bearing branch below still fails
            // loudly, as it always has).
            logger.warn("Native Alibaba plan surface skipped: the planning agent rebuild "
                    + "needs a Spring AI ChatModel bean and none is configured. Set "
                    + "atmosphere.ai.planning=builtin to use the portable write_todos "
                    + "floor instead. {}", configurationHint());
            planning = null;
        }
        var effectiveSystemPrompt = context.systemPrompt();
        if (planning != null && planning.rehydrationBlock() != null) {
            var block = planning.rehydrationBlock();
            effectiveSystemPrompt = effectiveSystemPrompt == null || effectiveSystemPrompt.isBlank()
                    ? block : effectiveSystemPrompt + "\n\n" + block;
            // assembleMessages placed the system prompt as the first
            // SystemMessage — keep the message list and the agent-level
            // prompt telling the same story.
            if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage sys) {
                messages.set(0, new SystemMessage(sys.getText() + "\n\n" + block));
            } else {
                messages.add(0, new SystemMessage(block));
            }
        }

        // Model-lifecycle hooks: same posture as Spring AI / LC4j / ADK / Koog
        // / Embabel / SK / AgentScope. Spring AI Alibaba is buffered (no
        // incremental token deltas), so onModelEnd fires once at completion
        // with the full duration but no TokenUsage (Alibaba's ReactAgent.call
        // returns AssistantMessage which has no usage surface in v1.1.2.0).
        var modelName = context.model() != null ? context.model() : name();
        var modelScope = org.atmosphere.ai.ModelCallScope.open(
                context.listeners(), modelName,
                messages.size(), context.tools().size());

        var activeAgent = agent;
        if (!context.tools().isEmpty() || planning != null) {
            if (staticChatModel == null) {
                throw new IllegalStateException(
                        "Spring AI Alibaba runtime received an @AiTool-bearing or "
                                + "planning-enabled request but no Spring AI ChatModel bean "
                                + "is configured. " + configurationHint());
            }
            var agentBuilder = ReactAgent.builder()
                    .name("atmosphere-spring-ai-alibaba-tools")
                    .model(staticChatModel)
                    .systemPrompt(effectiveSystemPrompt);
            if (!context.tools().isEmpty()) {
                agentBuilder.tools(SpringAiAlibabaToolBridge.toToolCallbacks(
                        context.tools(), session, context.approvalStrategy(),
                        context.listeners(), context.approvalPolicy()));
            }
            if (planning != null) {
                // ReactAgent.Builder.build() registers the interceptor's
                // write_todos ToolCallback alongside the bridged user tools —
                // both surfaces land on the same agent, neither clobbering
                // the other.
                agentBuilder.interceptors(planning.interceptor());
            }
            activeAgent = agentBuilder.build();
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
                if (effectiveSystemPrompt != null && !effectiveSystemPrompt.isBlank()) {
                    try {
                        activeAgent.setSystemPrompt(effectiveSystemPrompt);
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
            modelScope.fail(gre);
            session.error(gre);
            throw new IllegalStateException("Spring AI Alibaba ReactAgent failed", gre);
        } catch (RuntimeException re) {
            // Mirror the checked-exception path for any unchecked failure
            // escaping ReactAgent.call so observers see the dispatch error
            // before propagation.
            if (captureUsage) {
                UsageCapturingChatModel.endCapture();
            }
            modelScope.fail(re);
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

        modelScope.complete(tokenUsage);
        if (!session.isClosed()) {
            session.complete();
        }
    }

    /**
     * Cooperative cancellation entry point. Spring AI Alibaba's
     * {@link ReactAgent#call} runs the entire ReAct graph as a single blocking,
     * buffered call with no native cancel primitive and no incremental stream,
     * so this override cannot truly abort the upstream run the way the Reactor-
     * based runtimes can. Instead it dispatches {@link #doExecute} on a
     * dedicated virtual thread so the method returns promptly with a live
     * handle, and {@link ExecutionHandle#cancel()}:
     *
     * <ul>
     *   <li>frees the client immediately ({@code session.complete}) and settles
     *       {@link ExecutionHandle#whenDone()} <em>without</em> waiting for the
     *       upstream call to return; and</li>
     *   <li>interrupts the worker as a best-effort signal — {@code ReactAgent.call}
     *       does not observe interruption, so the graph may run to completion in
     *       the background, where its {@code session.send}/{@code complete} land
     *       on the already-closed session as no-ops.</li>
     * </ul>
     *
     * <p>This is the "cooperative" guarantee {@link AiCapability#CANCELLATION}
     * documents — the client disconnect is honoured — not hard preemption of the
     * upstream completion (Correctness Invariant #2 — Terminal Path Completeness,
     * applied within the runtime's native limits). Pinned by
     * {@code SpringAiAlibabaAgentRuntimeCancelTest}.</p>
     */
    @Override
    protected ExecutionHandle doExecuteWithHandle(ReactAgent agent,
                                                  AgentExecutionContext context,
                                                  StreamingSession session) {
        var done = new java.util.concurrent.CompletableFuture<Void>();
        var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
        Thread.startVirtualThread(() -> {
            worker.set(Thread.currentThread());
            try {
                doExecute(agent, context, session);
                done.complete(null);
            } catch (RuntimeException e) {
                // doExecute fires fireModelError + session.error on its failure
                // paths before propagating; settle whenDone with the cause.
                done.completeExceptionally(e);
            } finally {
                worker.set(null);
            }
        });
        return new ExecutionHandle() {
            private final java.util.concurrent.atomic.AtomicBoolean fired =
                    new java.util.concurrent.atomic.AtomicBoolean();

            @Override
            public void cancel() {
                if (!fired.compareAndSet(false, true)) {
                    return;
                }
                var t = worker.get();
                if (t != null) {
                    // Best-effort: ReactAgent.call is uninterruptible, but the
                    // interrupt flips the worker's status so any interruptible
                    // wait it later enters unwinds promptly.
                    t.interrupt();
                }
                if (!session.isClosed()) {
                    session.complete();
                }
                done.complete(null);
            }

            @Override
            public boolean isDone() {
                return done.isDone();
            }

            @Override
            public java.util.concurrent.CompletableFuture<Void> whenDone() {
                return done;
            }
        };
    }

    /**
     * Resolve the native plan surface for this request, or {@code null} when
     * the built-in floor (or no plan surface at all) governs. Native applies
     * only when the endpoint's injectables carry the
     * {@link org.atmosphere.ai.plan.AgentPlanStore} ({@code Harness.PLANNING}
     * resolved for the path — plain endpoints never see plan machinery),
     * {@code atmosphere.ai.planning} is not {@code BUILTIN} (in BUILTIN the
     * {@code write_todos} floor is already registered; provisioning the
     * interceptor too would duplicate plan tools), and no user tool already
     * claims the {@code write_todos} name (user tool wins, same posture as
     * the floor). Keys mirror {@link org.atmosphere.ai.tool.ToolScopes} so
     * the native surface reads and writes the exact plan slot the floor
     * uses. Package-private so {@code SpringAiAlibabaPlanBridgeTest} pins
     * the gating.
     */
    static SpringAiAlibabaPlanBridge.Provision provisionPlanSurface(
            AgentExecutionContext context, StreamingSession session) {
        var injectables = session.injectables();
        if (injectables == null
                || !(injectables.get(org.atmosphere.ai.plan.AgentPlanStore.class)
                        instanceof org.atmosphere.ai.plan.AgentPlanStore store)) {
            return null;
        }
        if (AiConfig.resolvePlanningMode() == org.atmosphere.ai.plan.PlanningMode.BUILTIN) {
            return null;
        }
        for (var tool : context.tools()) {
            if (org.atmosphere.ai.plan.PlanningTools.WRITE_TODOS.equals(tool.name())) {
                logger.warn("Native Alibaba plan surface skipped: a user tool already "
                        + "claims the '{}' name", tool.name());
                return null;
            }
        }
        var agentId = org.atmosphere.ai.tool.ToolScopes.agentId(injectables, context.agentId());
        var conversationId = org.atmosphere.ai.tool.ToolScopes.conversationId(injectables);
        return SpringAiAlibabaPlanBridge.provision(store, agentId, conversationId, session);
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
                AiCapability.MULTI_MODAL,
                // CANCELLATION: doExecuteWithHandle returns a live handle that
                // honours a client disconnect — cancel() frees the client and
                // settles whenDone() immediately. This is cooperative, not
                // preemptive: ReactAgent.call is a blocking, uninterruptible
                // graph run, so the upstream may finish in the background (its
                // output lands on the closed session as a no-op). See the
                // doExecuteWithHandle Javadoc; pinned by
                // SpringAiAlibabaAgentRuntimeCancelTest.
                AiCapability.CANCELLATION,
                // PLANNING: provisionPlanSurface attaches Alibaba's native
                // TodoListInterceptor per request when Harness.PLANNING
                // resolved — the framework's model-facing write_todos tool
                // plus its todo-usage prompt guidance — with the interceptor's
                // todoEventHandler persisting every write through Atmosphere's
                // AgentPlanStore and emitting AiEvent.PlanUpdate, and the
                // previous turn's live plan re-hydrated through the system
                // prompt (todos live in per-invocation graph state; the
                // adapter rebuilds the agent per request). See
                // SpringAiAlibabaPlanBridge; pinned by
                // SpringAiAlibabaPlanBridgeTest.
                // Deliberately NOT declared: VIRTUAL_FILESYSTEM. Alibaba's
                // FilesystemBackend SPI has no model-facing consumer in
                // agent-framework 1.1.2.3 (FilesystemInterceptor's Builder
                // carries a backend field with no setter, and its file tools
                // hit the raw host disk via java.nio directly), so the store
                // cannot be bridged behind the framework's tool surface —
                // the portable built-in file-tool floor governs instead
                // (Correctness Invariant #5, Runtime Truth).
                AiCapability.PLANNING);
    }
}
