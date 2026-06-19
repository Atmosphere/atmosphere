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
package org.atmosphere.ai.sk;

import com.microsoft.semantickernel.Kernel;
import com.microsoft.semantickernel.orchestration.InvocationContext;
import com.microsoft.semantickernel.orchestration.ToolCallBehavior;
import com.microsoft.semantickernel.services.chatcompletion.ChatCompletionService;
import com.microsoft.semantickernel.services.chatcompletion.ChatHistory;
import org.atmosphere.ai.AbstractAgentRuntime;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * {@link org.atmosphere.ai.AgentRuntime} implementation backed by Microsoft
 * Semantic Kernel for Java. Supports text streaming, system prompts,
 * conversation memory via {@link ChatHistory}, and — via
 * {@link SemanticKernelToolBridge} — full tool calling with
 * {@code @RequiresApproval} gating routed through the shared
 * {@link org.atmosphere.ai.tool.ToolExecutionHelper} helper.
 *
 * <p>Tool calling is implemented by subclassing
 * {@link com.microsoft.semantickernel.semanticfunctions.KernelFunction}
 * directly (one function per Atmosphere {@code ToolDefinition}). SK's
 * {@code KernelFunction} base class exposes a protected constructor and an
 * abstract {@code invokeAsync} method specifically for this use case, so the
 * bridge runs without reflection, annotation processing, or bytecode
 * synthesis — earlier Javadoc on this class claimed such a bridge was
 * impossible; that claim was wrong.</p>
 *
 * <p>The adapter uses the upstream 1.4.0 API surface (latest stable on Maven
 * Central as of April 2026). When Microsoft ships the "Microsoft Agent
 * Framework" Java SDK as the successor, this runtime can coexist with a new
 * {@code AgentFrameworkRuntime} or be updated in-place depending on wire
 * compatibility.</p>
 */
public class SemanticKernelAgentRuntime extends AbstractAgentRuntime<ChatCompletionService> {

    private static final Logger logger = LoggerFactory.getLogger(SemanticKernelAgentRuntime.class);

    private static volatile ChatCompletionService staticService;

    /**
     * Set the {@link ChatCompletionService} to use for streaming. Typically
     * called by {@link AtmosphereSemanticKernelAutoConfiguration} or an
     * application's own Spring wiring.
     */
    public static void setChatCompletionService(ChatCompletionService service) {
        staticService = service;
    }

    @Override
    public String name() {
        return "semantic-kernel";
    }

    @Override
    protected String nativeClientClassName() {
        return "com.microsoft.semantickernel.services.chatcompletion.ChatCompletionService";
    }

    @Override
    protected String clientDescription() {
        return "Semantic Kernel ChatCompletionService";
    }

    @Override
    protected String configurationHint() {
        return "Call SemanticKernelAgentRuntime.setChatCompletionService() or use "
                + "AtmosphereSemanticKernelAutoConfiguration.";
    }

    @Override
    protected ChatCompletionService createNativeClient(AiConfig.LlmSettings settings) {
        // SK-Java requires an OpenAIAsyncClient (Azure SDK) to instantiate its
        // OpenAIChatCompletion. That dependency is scoped 'provided' in this
        // module; auto-creation from AiConfig alone is not supported — rely on
        // Spring auto-configuration or an explicit setChatCompletionService call.
        return staticService;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        if (getNativeClient() == null && staticService != null) {
            setNativeClient(staticService);
            logger.info("Semantic Kernel auto-configured: model={}, endpoint={}",
                    settings != null ? settings.model() : "default",
                    settings != null ? settings.baseUrl() : "default");
        }
    }

    @Override
    protected void doExecute(ChatCompletionService service,
                             AgentExecutionContext context, StreamingSession session) {
        // Single execution path: doExecuteWithHandle owns the dispatch and the
        // cancellable subscription. The blocking entry point just awaits the
        // handle's terminal signal so the sync and cancel-aware callers share
        // identical setup, lifecycle, and streaming semantics (Invariant #7 —
        // Mode Parity). Same posture as the Spring AI runtime.
        var handle = doExecuteWithHandle(service, context, session);
        try {
            handle.whenDone().join();
        } catch (RuntimeException e) {
            // whenDone() resolves normally even on stream error (drainCancellable
            // fires session.error before completing it), so an exception here is
            // unexpected — ensure the session still closes.
            if (!session.isClosed()) {
                session.error(e);
            }
        }
    }

    @Override
    protected ExecutionHandle doExecuteWithHandle(ChatCompletionService service,
                                                  AgentExecutionContext context,
                                                  StreamingSession session) {
        // Admit through the process-wide AiGateway before issuing the native
        // Semantic Kernel dispatch — uniform per-user rate limiting and
        // credential resolution across all contract-tested runtimes
        // (Correctness Invariant #3).
        admitThroughGateway(context);
        var chatHistory = buildChatHistory(context);

        // Tool-calling path: when the context carries any @AiTool-derived
        // ToolDefinitions, build a KernelPlugin of one AtmosphereSkFunction per
        // tool and attach it to a fresh Kernel. ToolCallBehavior.allowAllKernelFunctions(true)
        // enables SK's auto-invoke loop, which will dispatch to our
        // KernelFunction.invokeAsync override and route through
        // ToolExecutionHelper.executeWithApproval for @RequiresApproval gating.
        var toolPlugin = SemanticKernelToolBridge.buildPlugin(context, session);
        var kernelBuilder = Kernel.builder();
        if (toolPlugin != null) {
            kernelBuilder = kernelBuilder.withPlugin(toolPlugin);
        }
        var kernel = kernelBuilder.build();

        // Per-request InvocationContext override: when the caller attached
        // one via SemanticKernelInvocation.attach(...), use it verbatim.
        // Otherwise build the runtime's default (allowAllKernelFunctions
        // gated on tool presence, no other overrides).
        var perRequestInvocation = SemanticKernelInvocation.from(context);
        var invocationContext = perRequestInvocation != null
                ? perRequestInvocation
                : buildInvocationContext(toolPlugin != null);
        if (perRequestInvocation != null) {
            logger.debug("Applied per-request SK InvocationContext override");
        }

        logger.debug("SK streaming: model={}, history messages={}, tools={}",
                context.model(), chatHistory.getMessages().size(),
                toolPlugin != null ? context.tools().size() : 0);

        var flux = service.getStreamingChatMessageContentsAsync(
                chatHistory, kernel, invocationContext);

        // Model-lifecycle hooks: fire onModelStart synchronously before subscribe;
        // onModelEnd / onModelError fire from drainCancellable on the matching
        // terminal signal. Same posture as Spring AI / LC4j / ADK / Koog / Embabel.
        var listeners = context.listeners();
        var modelName = context.model() != null ? context.model() : name();
        var messageCount = chatHistory.getMessages().size();
        var toolCount = toolPlugin != null ? context.tools().size() : 0;
        org.atmosphere.ai.AgentLifecycleListener.fireModelStart(
                listeners, modelName, messageCount, toolCount);

        // Wrap the Reactor Disposable in an ExecutionHandle so a client
        // disconnect can dispose the in-flight SK subscription — disposing
        // propagates an upstream cancel to the Azure / OpenAI streaming call
        // (Correctness Invariant #2 — Terminal Path Completeness). The handle
        // settles whenDone() on any terminal signal; cancel() is CAS-guarded
        // so dispose + session.complete fire at most once.
        var completion = new java.util.concurrent.CompletableFuture<Void>();
        var disposable = SemanticKernelStreamingAdapter.drainCancellable(
                flux, session, listeners, modelName, completion);
        return new ExecutionHandle() {
            private final java.util.concurrent.atomic.AtomicBoolean cancelled =
                    new java.util.concurrent.atomic.AtomicBoolean();

            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    disposable.dispose();
                    if (!session.isClosed()) {
                        session.complete();
                    }
                    completion.complete(null);
                }
            }

            @Override
            public boolean isDone() {
                return completion.isDone();
            }

            @Override
            public java.util.concurrent.CompletableFuture<Void> whenDone() {
                return completion;
            }
        };
    }

    /**
     * Build an {@link InvocationContext} that always carries a
     * {@link ToolCallBehavior}. SK 1.4.0's {@code OpenAIChatCompletion}
     * dereferences {@code invocationContext.getToolCallBehavior()} without a
     * null-check (see {@code OpenAIChatCompletion.java:200}), so a builder that
     * omits tool-call behavior NPEs the moment streaming starts. Passing
     * {@code allowAllKernelFunctions(false)} in the no-tool case keeps the
     * behavior honest — no auto-invoke, no tools — while satisfying SK's
     * non-null expectation.
     */
    static InvocationContext buildInvocationContext(boolean hasTools) {
        return InvocationContext.builder()
                .withToolCallBehavior(ToolCallBehavior.allowAllKernelFunctions(hasTools))
                .build();
    }

    /**
     * Assemble a {@link ChatHistory} from the execution context:
     * system prompt (if present) + conversation history + current user message.
     */
    private static ChatHistory buildChatHistory(AgentExecutionContext context) {
        var history = new ChatHistory();

        if (context.systemPrompt() != null && !context.systemPrompt().isEmpty()) {
            history.addSystemMessage(context.systemPrompt());
        }

        for (var msg : context.history()) {
            switch (msg.role()) {
                case "system" -> history.addSystemMessage(msg.content());
                case "assistant" -> history.addAssistantMessage(msg.content());
                default -> history.addUserMessage(msg.content());
            }
        }

        history.addUserMessage(context.message());
        // Multi-modal parts: SK Java's ChatMessageImageContent is its own
        // ChatMessageContent (no in-message text/image multipart). Append
        // each image as a follow-up user-role message — the underlying
        // OpenAI / Azure OpenAI chat completion sees text first, then image,
        // both on the user side, which vision-capable deployments accept.
        // Content.File has no SK image-equivalent and is dropped with a
        // debug log; Content.Audio similarly (SK 1.5.0 has no audio block).
        for (var part : (context.parts() != null ? context.parts() : java.util.List.<org.atmosphere.ai.Content>of())) {
            if (part instanceof org.atmosphere.ai.Content.Image img) {
                try {
                    // SK's withImage(subtype, bytes) formats `data:image/%s;base64,%s`
                    // — the first argument is the IMAGE SUBTYPE, not the full mime
                    // type. Passing "image/png" verbatim produces the malformed URI
                    // `data:image/image/png;base64,...`. Strip the `image/` prefix
                    // so the wire payload is the canonical `data:image/png;base64,...`.
                    var subtype = imageSubtype(img.mimeType());
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    com.microsoft.semantickernel.services.chatcompletion.ChatMessageContent<?> imageContent =
                            (com.microsoft.semantickernel.services.chatcompletion.ChatMessageContent<?>) (com.microsoft.semantickernel.services.chatcompletion.ChatMessageContent)
                                    com.microsoft.semantickernel.services.chatcompletion.message.ChatMessageImageContent
                                            .<byte[]>builder()
                                            .withImage(subtype, img.data())
                                            .build();
                    history.addMessage(imageContent);
                } catch (RuntimeException re) {
                    logger.warn("Failed to attach Content.Image part to SK ChatHistory "
                            + "(mime={}, bytes={}): {}", img.mimeType(), img.data().length, re.toString());
                }
            } else if (part instanceof org.atmosphere.ai.Content.Text t) {
                history.addUserMessage(t.text());
            } else {
                logger.debug("Dropping unsupported multi-modal part {} — "
                        + "Semantic Kernel 1.5.0 has no matching ChatMessageContent type",
                        part.getClass().getSimpleName());
            }
        }
        return history;
    }

    /**
     * Extract the subtype from a full image mime type so it can be passed
     * to SK's {@code withImage(subtype, bytes)} which prepends {@code image/}
     * internally. {@code image/png} → {@code png}; an unprefixed string is
     * returned unchanged so callers that already pass a subtype keep working.
     */
    private static String imageSubtype(String mimeType) {
        if (mimeType == null) {
            return "png";
        }
        var lower = mimeType.toLowerCase(java.util.Locale.ROOT);
        var prefix = "image/";
        if (lower.startsWith(prefix)) {
            return mimeType.substring(prefix.length());
        }
        return mimeType;
    }

    @Override
    public Set<AiCapability> capabilities() {
        // STRUCTURED_OUTPUT is declared because any runtime that honors
        // SYSTEM_PROMPT gets pipeline-level structured output for free via
        // {@code AiPipeline.StructuredOutputCapturingSession} + system-prompt
        // schema injection. This is the same reasoning the Built-in runtime
        // uses (Invariant #5 — Runtime Truth), enforced by the contract test
        // {@code runtimeWithSystemPromptAlsoDeclaresStructuredOutput}.
        //
        // TOOL_CALLING is honest: SemanticKernelToolBridge builds one
        // AtmosphereSkFunction per Atmosphere ToolDefinition on the context
        // and attaches them to a fresh Kernel's KernelPlugin. The SK auto-
        // invoke loop dispatches to AtmosphereSkFunction.invokeAsync, which
        // translates KernelFunctionArguments into a Map<String,Object> and
        // routes every call through ToolExecutionHelper.executeWithApproval.
        //
        // TOOL_APPROVAL is honest because the same routing path runs through
        // the shared approval helper — @RequiresApproval gates fire
        // uniformly with the other runtime bridges.
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.SYSTEM_PROMPT,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.CONVERSATION_MEMORY,
                AiCapability.TOKEN_USAGE,
                AiCapability.TOOL_CALLING,
                AiCapability.TOOL_APPROVAL,
                // PER_REQUEST_RETRY: honored via AbstractAgentRuntime's
                // outer retry wrapper (executeWithOuterRetry). Retries
                // pre-stream transient failures on top of SK's
                // OpenAIAsyncClient retry layer.
                AiCapability.PER_REQUEST_RETRY,
                // BUDGET_ENFORCEMENT: framework-level circuit breaker via
                // the AiPipeline BudgetCapturingSession decorator — honest
                // because the chat-completion handler emits typed TokenUsage
                // through session.usage(), the signal BudgetCapturingSession
                // taps for token / step abort. Wall-clock limits trip on
                // every runtime universally.
                AiCapability.BUDGET_ENFORCEMENT,
                // CONFIDENCE_SCORES: framework-level — AiPipeline's
                // ConfidenceCapturingSession parses the model-reported
                // confidence field on stream completion. Honest because SK
                // honors SYSTEM_PROMPT and the streaming adapter pushes
                // response text via session.send.
                AiCapability.CONFIDENCE_SCORES,
                // PASSIVATION: AgentPassivation snapshots context.history()
                // into a CheckpointStore. Honest because SK threads history
                // into the ChatHistory before invoking the chat completion.
                AiCapability.PASSIVATION,
                // VISION / MULTI_MODAL: buildChatHistory appends a
                // ChatMessageImageContent (via withImage(mime, bytes)) for
                // every Content.Image part. SK's OpenAI / Azure OpenAI
                // adapter translates the image to the wire-level
                // image_url block, so vision-capable deployments see the
                // bytes. AUDIO is NOT declared — SK 1.5.0 has no audio
                // content type today (Content.Audio is dropped with a
                // debug log).
                AiCapability.VISION,
                AiCapability.MULTI_MODAL,
                // CANCELLATION: doExecuteWithHandle returns an ExecutionHandle
                // wrapping the Reactor subscription's Disposable. cancel()
                // disposes it, which propagates an upstream cancel to the
                // Azure / OpenAI streaming call so a client disconnect aborts
                // the in-flight completion (Invariant #2 — Terminal Path
                // Completeness). Pinned by SemanticKernelAgentRuntimeCancelTest.
                AiCapability.CANCELLATION
        );
    }
}
