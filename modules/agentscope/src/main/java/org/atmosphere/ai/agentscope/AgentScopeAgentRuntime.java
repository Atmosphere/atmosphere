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
package org.atmosphere.ai.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.plan.PlanNotebook;
import org.atmosphere.ai.AbstractAgentRuntime;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.plan.AgentPlanStore;
import org.atmosphere.ai.plan.PlanningMode;
import org.atmosphere.ai.tool.ToolScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * {@link org.atmosphere.ai.AgentRuntime} adapter for the AgentScope Java
 * framework (Tongyi Lab — {@code io.agentscope:agentscope-core}). Wraps a
 * {@link ReActAgent} so its native {@code Flux<Event>} streaming surface
 * forwards token deltas through Atmosphere's {@link StreamingSession}.
 *
 * <p>Auto-detected when {@code io.agentscope.core.ReActAgent} is on the
 * classpath. The agent itself is wired via Spring (
 * {@link AtmosphereAgentScopeAutoConfiguration}) — AgentScope models
 * (DashScope, OpenAI-compatible) need provider-specific construction that
 * can't be done from {@code llm.*} properties alone, so the runtime accepts
 * a user-built {@link ReActAgent} bean rather than building one itself.</p>
 *
 * <p><b>Capabilities:</b> {@link AiCapability#TEXT_STREAMING},
 * {@link AiCapability#SYSTEM_PROMPT}, {@link AiCapability#STRUCTURED_OUTPUT}
 * (via the pipeline), {@link AiCapability#CONVERSATION_MEMORY},
 * {@link AiCapability#TOKEN_USAGE}, {@link AiCapability#TOOL_CALLING}, and
 * {@link AiCapability#TOOL_APPROVAL}. Tools are bridged per request into an
 * AgentScope {@code Toolkit} through {@link AgentScopeToolBridge}, so
 * Atmosphere's validation and HITL gate remain authoritative.
 * {@link AiCapability#PLANNING} delegates the harness planning primitive to
 * AgentScope's native {@code PlanNotebook} through
 * {@link AgentScopePlanBridge} — persisted in Atmosphere's
 * {@link AgentPlanStore}, surfaced as {@code AiEvent.PlanUpdate}.</p>
 */
public class AgentScopeAgentRuntime extends AbstractAgentRuntime<ReActAgent> {

    private static final Logger logger = LoggerFactory.getLogger(AgentScopeAgentRuntime.class);

    private static volatile ReActAgent staticAgent;

    /**
     * Inject a pre-built {@link ReActAgent} from Spring auto-configuration.
     * Mirrors the {@code KoogAgentRuntime.setPromptExecutor} /
     * {@code LangChain4jAgentRuntime.setModel} pattern — the autoconfig
     * holds the Spring-managed agent bean and seeds the runtime statically
     * so the {@link java.util.ServiceLoader}-instantiated runtime picks it
     * up without needing constructor injection.
     */
    public static void setAgent(ReActAgent agent) {
        staticAgent = agent;
    }

    @Override
    public String name() {
        return "agentscope";
    }

    @Override
    protected String nativeClientClassName() {
        return "io.agentscope.core.ReActAgent";
    }

    @Override
    protected String clientDescription() {
        return "ReActAgent";
    }

    @Override
    protected String configurationHint() {
        return "Wire a ReActAgent @Bean (e.g. ReActAgent.builder().model(...).build()) "
                + "or call AgentScopeAgentRuntime.setAgent() before the first request.";
    }

    @Override
    protected ReActAgent createNativeClient(AiConfig.LlmSettings settings) {
        // AgentScope's Model surface (DashScope, OpenAI-compatible) needs
        // provider-specific construction the AiConfig.LlmSettings DTO can't
        // express. Defer to the user's @Bean wired via autoconfig.
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
    protected void doExecute(ReActAgent agent,
                             AgentExecutionContext context,
                             StreamingSession session) {
        var handle = doExecuteWithHandle(agent, context, session);
        try {
            handle.whenDone().get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException ex) {
            // Already surfaced via session.error() in the subscribe handler.
        }
    }

    @Override
    protected ExecutionHandle doExecuteWithHandle(ReActAgent agent,
                                                  AgentExecutionContext context,
                                                  StreamingSession session) {
        admitThroughGateway(context);

        // Per-request agent override: when the caller attached a different
        // ReActAgent via AgentScopeAgent.attach(...), dispatch against it
        // instead of the runtime's installed default. Useful when different
        // prompts need different agent topologies (e.g. planner vs. quick
        // lookup) without re-installing the runtime client globally. Bind to
        // a fresh effectively-final local so the cancel handler's anonymous
        // ExecutionHandle inner class can capture the resolved agent.
        var perRequestAgent = AgentScopeAgent.from(context);
        ReActAgent resolvedAgent;
        if (perRequestAgent != null) {
            resolvedAgent = perRequestAgent;
            logger.debug("Dispatching against per-request AgentScope ReActAgent override");
        } else {
            resolvedAgent = agent;
        }
        // Harness PLANNING delegation: when the endpoint attached an
        // AgentPlanStore (Harness.PLANNING resolved) and the mode allows a
        // native surface, provision a per-request PlanNotebook bridged to
        // Atmosphere's store + PlanUpdate events. The rebuild below merges it
        // with the bridged toolkit — ReActAgent.Builder registers the plan
        // tools into the same toolkit copy, so per-request tool rebuilds
        // never clobber the plan surface.
        var planNotebook = provisionPlanNotebook(context, session);
        if (!context.tools().isEmpty() || planNotebook != null) {
            resolvedAgent = rebuildAgent(resolvedAgent, context, session, planNotebook);
        }
        final ReActAgent activeAgent = resolvedAgent;

        var msgs = new ArrayList<Msg>();
        var assembled = assembleMessages(context);
        for (var chat : assembled) {
            msgs.add(Msg.builder()
                    .role(toRole(chat.role()))
                    .textContent(chat.content())
                    .build());
        }
        // Replace the trailing user message with a content-block-bearing copy
        // when multi-modal parts are present. AgentScope's native ImageBlock
        // / AudioBlock + Base64Source wire to the underlying ChatModel
        // formatter (DashScope / OpenAI / Ollama), so vision-capable models
        // see the image bytes through the same flow non-multimodal text takes.
        attachPartsToTrailingUserMessage(msgs, context.message(), context.parts());

        var done = new CompletableFuture<Void>();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();

        // Model-lifecycle hooks: same posture as Spring AI / LC4j / ADK / Koog
        // / Embabel / SK. fireModelStart synchronously before subscribe;
        // fireModelEnd on completion (with the last captured TokenUsage from
        // handleEvent); fireModelError on the error callback.
        var messageCount = msgs.size();
        var toolCount = context.tools().size();
        var lastUsage = new java.util.concurrent.atomic.AtomicReference<TokenUsage>();
        var modelScope = org.atmosphere.ai.ModelCallScope.open(
                context.listeners(),
                context.model() != null ? context.model() : name(),
                messageCount, toolCount);

        // Provider-native structured output: when the pipeline opts in (response
        // type declared and NativeStructuredOutputMode != DISABLED), use the
        // schema-aware 3-arg stream overload so AgentScope enforces the schema at
        // the provider level (ReActAgent's structured-output tool / formatter
        // ResponseFormat.jsonSchema). The Class overload lets AgentScope derive
        // its own provider-appropriate schema. AUTO mode falls back to prompt
        // injection on a provider rejection (the pipeline re-dispatches with the
        // flag cleared, so this drops back to the 2-arg overload).
        var nativeApply = context.responseType() != null
                && org.atmosphere.ai.NativeStructuredOutput.shouldApply(context);
        var eventStream = nativeApply
                ? activeAgent.stream(msgs, StreamOptions.defaults(), context.responseType())
                : activeAgent.stream(msgs, StreamOptions.defaults());
        Disposable subscription = eventStream
                .subscribe(
                        event -> handleEvent(event, session, lastUsage),
                        error -> {
                            // Boundary safety (Invariant #4): every terminal
                            // path must close the session. error() is
                            // idempotent on Atmosphere's StreamingSession.
                            modelScope.fail(error);
                            session.error(error);
                            done.completeExceptionally(error);
                        },
                        () -> {
                            modelScope.complete(lastUsage.get());
                            if (!session.isClosed()) {
                                session.complete();
                            }
                            done.complete(null);
                        });

        return new ExecutionHandle() {
            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    // AgentScope exposes interrupt() for cooperative cancel
                    // and the Reactor Disposable for unsubscription. Fire
                    // both: interrupt() unwinds the agent's internal loop;
                    // dispose() drops any in-flight emission to the session.
                    try {
                        activeAgent.interrupt();
                    } catch (RuntimeException re) {
                        logger.trace("ReActAgent.interrupt() threw on cancel", re);
                    }
                    subscription.dispose();
                    if (!done.isDone()) {
                        done.completeExceptionally(new CancellationException("cancelled"));
                    }
                }
            }

            @Override
            public boolean isDone() {
                return done.isDone();
            }

            @Override
            public CompletableFuture<Void> whenDone() {
                return done;
            }
        };
    }

    private static void handleEvent(
            Event event,
            StreamingSession session,
            java.util.concurrent.atomic.AtomicReference<TokenUsage> lastUsage) {
        if (event == null || event.getMessage() == null) {
            return;
        }
        var msg = event.getMessage();
        var text = msg.getTextContent();
        if (text != null && !text.isEmpty()) {
            session.send(text);
        }
        // Token usage rides on the terminal Msg. Forwarding TOKEN_USAGE on
        // every chunk would double-count; AgentScope sets ChatUsage only on
        // the message that completes the round. Stash to lastUsage so the
        // caller's onModelEnd lifecycle hook reports it.
        var usage = msg.getChatUsage();
        if (usage != null && event.isLast()) {
            var tokenUsage = TokenUsage.fromCounts(
                    (long) usage.getInputTokens(),
                    (long) usage.getOutputTokens(),
                    null,
                    (long) usage.getTotalTokens());
            session.usage(tokenUsage);
            lastUsage.set(tokenUsage);
        }
    }

    /**
     * Resolve the native plan surface for this request, or {@code null} when
     * the built-in floor (or no plan surface at all) governs. Native applies
     * only when the endpoint's injectables carry the {@link AgentPlanStore}
     * ({@code Harness.PLANNING} resolved for the path — plain endpoints never
     * see plan machinery) and {@code atmosphere.ai.planning} is not
     * {@code BUILTIN} (in BUILTIN the {@code write_todos} floor is already
     * registered; provisioning the notebook too would duplicate plan tools).
     * Keys mirror {@link ToolScopes} so the native surface reads and writes
     * the exact plan slot the floor uses. Package-private so
     * {@code AgentScopePlanBridgeTest} pins the gating.
     */
    static PlanNotebook provisionPlanNotebook(
            AgentExecutionContext context, StreamingSession session) {
        var injectables = session.injectables();
        if (injectables == null
                || !(injectables.get(AgentPlanStore.class) instanceof AgentPlanStore store)) {
            return null;
        }
        if (AiConfig.resolvePlanningMode() == PlanningMode.BUILTIN) {
            return null;
        }
        var agentId = ToolScopes.agentId(injectables, context.agentId());
        var conversationId = ToolScopes.conversationId(injectables);
        return AgentScopePlanBridge.provision(store, agentId, conversationId, session);
    }

    /**
     * Rebuild the dispatch agent for this request, merging the per-request
     * surfaces: the bridged toolkit (when the context carries tools) and the
     * plan notebook. {@code ReActAgent.Builder.build()} registers the plan
     * tools into its copy of the provided toolkit, so both surfaces land on
     * the same agent — never one clobbering the other. When the harness did
     * not provision a notebook, the base agent's own {@code planNotebook}
     * (e.g. a user bean built with {@code enablePlan()}) is carried over so
     * a tools-only rebuild does not silently drop the user's plan surface.
     */
    private static ReActAgent rebuildAgent(
            ReActAgent baseAgent, AgentExecutionContext context, StreamingSession session,
            PlanNotebook planNotebook) {
        var builder = ReActAgent.builder()
                .name(baseAgent.getClass().getSimpleName())
                .sysPrompt(baseAgent.getSysPrompt())
                .model(baseAgent.getModel())
                .memory(baseAgent.getMemory())
                .maxIters(baseAgent.getMaxIters())
                .generateOptions(baseAgent.getGenerateOptions());
        if (!context.tools().isEmpty()) {
            builder.toolkit(AgentScopeToolBridge.toToolkit(
                    context.tools(), session, context.approvalStrategy(),
                    context.approvalPolicy()));
        }
        var notebook = planNotebook != null ? planNotebook : baseAgent.getPlanNotebook();
        if (notebook != null) {
            builder.planNotebook(notebook);
        }
        return builder.build();
    }

    private static MsgRole toRole(String role) {
        if (role == null) {
            return MsgRole.USER;
        }
        return switch (role) {
            case "system" -> MsgRole.SYSTEM;
            case "assistant" -> MsgRole.ASSISTANT;
            case "tool" -> MsgRole.TOOL;
            default -> MsgRole.USER;
        };
    }

    /**
     * Rebuild the trailing {@link Msg} as a content-block list when
     * multi-modal parts are present. The new message preserves the user
     * text alongside native {@link io.agentscope.core.message.ImageBlock}
     * / {@link io.agentscope.core.message.AudioBlock} instances backed by
     * {@link io.agentscope.core.message.Base64Source} so AgentScope's
     * formatter layer can route the bytes to the model.
     *
     * <p>{@link org.atmosphere.ai.Content.File} is dropped with a debug
     * log: AgentScope has no FileBlock in 1.0.12 (only Image / Audio /
     * Video / Thinking / Tool blocks). Declaring file support without a
     * wire path would lie about runtime truth.</p>
     */
    private static void attachPartsToTrailingUserMessage(
            java.util.List<Msg> msgs, String userMessage,
            java.util.List<org.atmosphere.ai.Content> parts) {
        if (parts == null || parts.isEmpty() || msgs.isEmpty()) {
            return;
        }
        var lastIndex = msgs.size() - 1;
        var trailing = msgs.get(lastIndex);
        if (trailing.getRole() != io.agentscope.core.message.MsgRole.USER) {
            return;
        }
        var blocks = new ArrayList<io.agentscope.core.message.ContentBlock>();
        if (userMessage != null && !userMessage.isEmpty()) {
            blocks.add(io.agentscope.core.message.TextBlock.builder().text(userMessage).build());
        }
        for (var part : parts) {
            if (part instanceof org.atmosphere.ai.Content.Image img) {
                var source = io.agentscope.core.message.Base64Source.builder()
                        .mediaType(img.mimeType())
                        .data(java.util.Base64.getEncoder().encodeToString(img.data()))
                        .build();
                blocks.add(io.agentscope.core.message.ImageBlock.builder().source(source).build());
            } else if (part instanceof org.atmosphere.ai.Content.Audio audio) {
                var source = io.agentscope.core.message.Base64Source.builder()
                        .mediaType(audio.mimeType())
                        .data(java.util.Base64.getEncoder().encodeToString(audio.data()))
                        .build();
                blocks.add(io.agentscope.core.message.AudioBlock.builder().source(source).build());
            } else if (part instanceof org.atmosphere.ai.Content.Text t) {
                blocks.add(io.agentscope.core.message.TextBlock.builder().text(t.text()).build());
            } else {
                logger.debug("Dropping unsupported multi-modal part {} — "
                        + "AgentScope 1.0.12 has no matching ContentBlock type",
                        part.getClass().getSimpleName());
            }
        }
        if (blocks.isEmpty()) {
            return;
        }
        msgs.set(lastIndex, Msg.builder()
                .role(io.agentscope.core.message.MsgRole.USER)
                .content(blocks)
                .build());
    }

    @Override
    public Set<AiCapability> capabilities() {
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.SYSTEM_PROMPT,
                // AiPipeline wraps the session in StructuredOutputCapturingSession
                // and augments the system prompt with schema instructions for
                // every SYSTEM_PROMPT-capable runtime — the contract test
                // pins this pairing.
                AiCapability.STRUCTURED_OUTPUT,
                // NATIVE_STRUCTURED_OUTPUT: doExecuteWithHandle uses AgentScope's
                // schema-aware 3-arg stream(msgs, opts, responseType) overload so
                // the agent enforces the schema at the provider level (formatter
                // ResponseFormat.jsonSchema / structured-output tool); AUTO mode
                // falls back to prompt injection on a provider rejection.
                AiCapability.NATIVE_STRUCTURED_OUTPUT,
                // assembleMessages threads context.history() into the Msg
                // list the agent receives, so prior turns are honored.
                AiCapability.CONVERSATION_MEMORY,
                // handleEvent forwards Msg.getChatUsage() on isLast().
                AiCapability.TOKEN_USAGE,
                // AgentScopeToolBridge registers Atmosphere ToolDefinition
                // instances as AgentScope AgentTool objects on a per-request
                // Toolkit, then routes each invocation through the shared
                // ToolExecutionHelper approval/validation seam.
                AiCapability.TOOL_CALLING,
                AiCapability.TOOL_APPROVAL,
                // executeWithHandle returns an ExecutionHandle whose cancel()
                // calls activeAgent.interrupt() (cooperative unwind of the
                // ReAct loop), disposes the Reactor subscription (drops
                // in-flight emission), and resolves the done-future
                // exceptionally with CancellationException — terminal-path
                // closure per Correctness Invariant #2.
                AiCapability.CANCELLATION,
                // Inherits AbstractAgentRuntime.executeWithOuterRetry — does
                // not override ownsPerRequestRetry(), so context.retryPolicy()
                // with maxRetries > 0 retries doExecute on pre-stream
                // RuntimeException. See modules/ai/README.md
                // "Per-Request Retry Architecture".
                AiCapability.PER_REQUEST_RETRY,
                // BUDGET_ENFORCEMENT: framework-level circuit breaker via the
                // AiPipeline BudgetCapturingSession decorator — honest because
                // handleEvent forwards Msg.getChatUsage() through session.usage()
                // which is the signal the decorator taps for token / step abort.
                AiCapability.BUDGET_ENFORCEMENT,
                // CONFIDENCE_SCORES: framework-level — AiPipeline's
                // ConfidenceCapturingSession parses the model-reported
                // confidence field on stream completion. Honest because
                // AgentScope honors SYSTEM_PROMPT and streams response text.
                AiCapability.CONFIDENCE_SCORES,
                // PASSIVATION: AgentPassivation snapshots context.history()
                // into a CheckpointStore. Honest because assembleMessages
                // threads history into the Msg list AgentScope receives.
                AiCapability.PASSIVATION,
                // VISION / AUDIO / MULTI_MODAL: attachPartsToTrailingUserMessage
                // rebuilds the active user Msg with native ImageBlock /
                // AudioBlock content blocks (Base64Source). AgentScope's
                // formatter layer routes these blocks through to the
                // underlying ChatModel for vision-capable providers.
                AiCapability.VISION,
                AiCapability.AUDIO,
                AiCapability.MULTI_MODAL,
                // PLANNING: provisionPlanNotebook attaches AgentScope's native
                // PlanNotebook per request when Harness.PLANNING resolved —
                // model-facing create_plan / update_subtask_state /
                // finish_subtask / finish_plan tools plus per-step hint
                // injection — with AtmospherePlanStorage persisting through
                // Atmosphere's AgentPlanStore and a change hook emitting
                // AiEvent.PlanUpdate on every mutation (AgentScopePlanBridge).
                AiCapability.PLANNING);
    }
}
