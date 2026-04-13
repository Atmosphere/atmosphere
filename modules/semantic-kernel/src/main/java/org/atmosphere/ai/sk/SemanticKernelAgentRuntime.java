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

        var icBuilder = InvocationContext.builder();
        if (toolPlugin != null) {
            icBuilder = icBuilder.withToolCallBehavior(
                    ToolCallBehavior.allowAllKernelFunctions(true));
        }
        var invocationContext = icBuilder.build();

        logger.debug("SK streaming: model={}, history messages={}, tools={}",
                context.model(), chatHistory.getMessages().size(),
                toolPlugin != null ? context.tools().size() : 0);

        var flux = service.getStreamingChatMessageContentsAsync(
                chatHistory, kernel, invocationContext);

        SemanticKernelStreamingAdapter.drain(flux, session);
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
        return history;
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
                AiCapability.PER_REQUEST_RETRY
        );
    }

    @Override
    public java.util.List<String> models() {
        // SK's ChatCompletionService carries the deployment / model name
        // configured at client construction time. The 1.4.0 accessor surface
        // varies between providers, so fall back to AiConfig's resolved
        // default model. Per-request overrides via context.model() take
        // precedence at dispatch time.
        var settings = org.atmosphere.ai.AiConfig.get();
        if (settings == null || settings.model() == null || settings.model().isBlank()) {
            return java.util.List.of();
        }
        return java.util.List.of(settings.model());
    }
}
