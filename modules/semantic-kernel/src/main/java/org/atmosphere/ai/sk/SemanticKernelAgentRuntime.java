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
 * Semantic Kernel for Java. Phase 12 of the unified {@code @Agent} API — the
 * 6th runtime, added to validate the SPI generalises against a hyperscaler-
 * maintained Java AI framework.
 *
 * <p>This initial release supports text streaming, system prompts, and
 * conversation memory via {@link ChatHistory}. Tool calling (SK's
 * {@code @DefineKernelFunction} plugin system) is deferred as a follow-up
 * because mapping Atmosphere's dynamic {@code @AiTool} tool definitions to
 * SK's annotation-driven {@code KernelPluginFactory} requires either a
 * compile-time annotation processor or runtime bytecode synthesis — neither
 * fits the minimal Phase 12 scope.</p>
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
        var kernel = Kernel.builder().build();
        var invocationContext = InvocationContext.builder().build();

        logger.debug("SK streaming: model={}, history messages={}",
                context.model(), chatHistory.getMessages().size());

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
        // Phase 12 initial release — tool calling deferred, see class Javadoc.
        // STRUCTURED_OUTPUT is declared because any runtime that honors
        // SYSTEM_PROMPT gets pipeline-level structured output for free via
        // {@code AiPipeline.StructuredOutputCapturingSession} + system-prompt
        // schema injection. This is the same reasoning the Built-in runtime
        // uses (Invariant #5 — Runtime Truth), enforced by the contract test
        // {@code runtimeWithSystemPromptAlsoDeclaresStructuredOutput}.
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.SYSTEM_PROMPT,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.CONVERSATION_MEMORY,
                AiCapability.TOKEN_USAGE
        );
    }
}
