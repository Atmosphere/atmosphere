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
package org.atmosphere.ai.cohere;

import org.atmosphere.ai.AbstractAgentRuntime;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link org.atmosphere.ai.AgentRuntime} backed by a native
 * {@link CohereChatClient} that posts directly to
 * {@code https://api.cohere.com/v2/chat}. Picked up via
 * {@link java.util.ServiceLoader} when this module's jar is on the
 * classpath alongside a {@code cohere.api.key} configuration.
 *
 * <p>The {@code cohere.base.url} system property overrides the default
 * Cohere cloud endpoint so a self-hosted Command A+ deployment that
 * speaks the v2 Chat wire protocol can be addressed without code
 * changes — the same posture {@link org.atmosphere.ai.anthropic.AnthropicAgentRuntime}
 * takes for Anthropic-compatible proxies.</p>
 *
 * <p>Priority is {@code 100} — the same convention every other
 * provider-specific runtime adapter uses. The built-in OpenAI-compatible
 * client at priority {@code 0} remains the fallback when no
 * provider-specific runtime is available.</p>
 */
public class CohereAgentRuntime extends AbstractAgentRuntime<CohereChatClient> {

    private static final String API_KEY_PROPERTY = "cohere.api.key";
    private static final String BASE_URL_PROPERTY = "cohere.base.url";
    private static final String MAX_TOKENS_PROPERTY = "cohere.max.tokens";
    private static final String DEFAULT_MODEL = "command-a-plus-05-2026";

    @Override
    public String name() {
        return "cohere";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    protected String nativeClientClassName() {
        return "org.atmosphere.ai.cohere.CohereChatClient";
    }

    @Override
    protected String clientDescription() {
        return "CohereChatClient";
    }

    /**
     * Build the client from {@link AiConfig.LlmSettings} when the framework
     * has resolved one. Falls back to system-property configuration so
     * standalone tests and CLI usage work without an Atmosphere-wide config
     * (mirrors how the built-in and Anthropic runtimes read their own
     * settings).
     */
    @Override
    protected CohereChatClient createNativeClient(AiConfig.LlmSettings settings) {
        var builder = CohereChatClient.builder()
                .apiKey(systemProperty(API_KEY_PROPERTY,
                        settings != null ? settings.apiKey() : null));
        // Sovereign-deploy story: the {@code cohere.base.url} system property
        // wins outright; otherwise the framework-resolved settings.baseUrl()
        // is honored so the existing {@code LLM_BASE_URL} env var works
        // without per-runtime config. This is the missing link that lets the
        // spring-boot-ai-chat sample target a self-hosted Command A+ endpoint
        // without code changes.
        var baseUrl = systemProperty(BASE_URL_PROPERTY,
                settings != null && settings.baseUrl() != null
                        && !settings.baseUrl().isBlank() ? settings.baseUrl() : null);
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        var maxTokensRaw = System.getProperty(MAX_TOKENS_PROPERTY);
        if (maxTokensRaw != null && !maxTokensRaw.isBlank()) {
            try {
                builder.maxTokens(Integer.parseInt(maxTokensRaw.trim()));
            } catch (NumberFormatException ignored) {
                // Fall back to the client default — opt-in misconfiguration
                // is silent on purpose so an environment with a stray value
                // does not break the build-time wiring.
            }
        }
        return builder.build();
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        if (getNativeClient() == null) {
            setNativeClient(createNativeClient(settings));
        }
    }

    @Override
    public boolean isAvailable() {
        // Honest Runtime Truth: the runtime is available iff an API key has
        // been provided either via system property or AiConfig settings.
        // Without a key, the first POST would 401 — declaring availability
        // off the classpath alone would lie about the runtime state.
        var settings = AiConfig.get();
        var settingsKey = settings != null ? settings.apiKey() : null;
        return (systemProperty(API_KEY_PROPERTY, settingsKey) != null);
    }

    @Override
    protected void doExecute(CohereChatClient client,
                             AgentExecutionContext context,
                             StreamingSession session) {
        admitThroughGateway(context);
        client.stream(
                effectiveModel(context),
                assembleMessages(context),
                context.systemPrompt(),
                context.message(),
                context,
                session,
                null);
    }

    @Override
    protected org.atmosphere.ai.ExecutionHandle doExecuteWithHandle(
            CohereChatClient client, AgentExecutionContext context,
            StreamingSession session) {
        admitThroughGateway(context);
        var cancelled = new AtomicBoolean();
        var done = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try {
                client.stream(
                        effectiveModel(context),
                        assembleMessages(context),
                        context.systemPrompt(),
                        context.message(),
                        context,
                        session,
                        cancelled);
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return new org.atmosphere.ai.ExecutionHandle() {
            @Override public void cancel() {
                cancelled.set(true);
            }

            @Override public boolean isDone() { return done.isDone(); }

            @Override public CompletableFuture<Void> whenDone() { return done; }
        };
    }

    private static String effectiveModel(AgentExecutionContext context) {
        if (context != null && context.model() != null && !context.model().isBlank()) {
            return context.model();
        }
        var settings = AiConfig.get();
        if (settings != null && settings.model() != null && !settings.model().isBlank()) {
            return settings.model();
        }
        return DEFAULT_MODEL;
    }

    private static String systemProperty(String key, String fallback) {
        var value = System.getProperty(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    @Override
    public Set<AiCapability> capabilities() {
        // Honest floor — every entry corresponds to a code path
        // CohereChatClient actually exercises:
        //   TEXT_STREAMING        — content-delta forwarding via session.send()
        //   SYSTEM_PROMPT         — system role threaded into messages[] head
        //   STRUCTURED_OUTPUT     — pipeline-layer schema injection via
        //                            StructuredOutputCapturingSession; the
        //                            runtime honors SYSTEM_PROMPT and Cohere
        //                            response_format={"type":"json_object"}
        //                            could be added later if the pipeline
        //                            asks for it
        //   TOOL_CALLING          — tool-call-start → tool-call-delta →
        //                            tool-call-end loop with shared
        //                            ToolExecutionHelper.executeWithApproval
        //   TOOL_APPROVAL         — every tool dispatch routes through the
        //                            executeWithApproval gate
        //   TOKEN_USAGE           — message-end.delta.usage parsed and
        //                            emitted via session.usage()
        //   CONVERSATION_MEMORY   — assembleMessages threads context.history()
        //                            into every outbound request
        //   BUDGET_ENFORCEMENT    — pipeline-level decorator taps session.usage()
        //   CONFIDENCE_SCORES     — pipeline-level cue appended to the system
        //                            prompt; runtime honors SYSTEM_PROMPT
        //   PASSIVATION           — assembleMessages-based; checkpoint module
        //                            rehydrates context.history() unchanged
        //   PER_REQUEST_RETRY     — AbstractAgentRuntime.executeWithOuterRetry
        //                            wraps doExecute when context.retryPolicy()
        //                            requests retries
        //   VISION                — Content.Image translates to an
        //                            OpenAI-compatible image_url block
        //                            (data: URI) in CohereChatClient
        //                            .userMessageWithParts; Command A+ /
        //                            Command A Vision honor this shape
        //   MULTI_MODAL           — same code path; a Cohere user message
        //                            can interleave text + image_url blocks
        //   TOOL_CALL_DELTA       — CohereChatClient.handleToolCallDelta
        //                            forwards every {@code tool-call-delta}
        //                            event's argument fragment via
        //                            session.toolCallDelta(toolCallId, chunk)
        //                            so browser UIs can render partial tool-
        //                            argument JSON before the consolidated
        //                            AiEvent.ToolStart frame fires. Same
        //                            posture as BuiltInAgentRuntime's
        //                            OpenAiCompatibleClient chat-completions
        //                            loop.
        // NOT claimed:
        //   AUDIO                 — Cohere v2 chat content array has no
        //                            audio block (Content.Audio is dropped
        //                            with a debug log)
        //   PROMPT_CACHING        — Cohere v2 Chat API
        //                            ({@code https://docs.cohere.com/v2/reference/chat})
        //                            does not document a prompt-caching wire
        //                            shape. No {@code cache_control}, no
        //                            ephemeral-block, no top-level TTL field
        //                            appears in the OpenAPI schema. Until
        //                            Cohere ships an API surface we can drive
        //                            from {@link org.atmosphere.ai.llm.CacheHint},
        //                            this capability stays off — declaring it
        //                            from a {@code Class.forName} check would
        //                            lie about runtime state (Correctness
        //                            Invariant #5, Runtime Truth).
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.SYSTEM_PROMPT,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.TOOL_CALLING,
                AiCapability.TOOL_APPROVAL,
                AiCapability.TOKEN_USAGE,
                AiCapability.CONVERSATION_MEMORY,
                AiCapability.BUDGET_ENFORCEMENT,
                AiCapability.CONFIDENCE_SCORES,
                AiCapability.PASSIVATION,
                AiCapability.PER_REQUEST_RETRY,
                AiCapability.VISION,
                AiCapability.MULTI_MODAL,
                AiCapability.TOOL_CALL_DELTA);
    }

    @Override
    public List<String> models() {
        var settings = AiConfig.get();
        if (settings == null || settings.model() == null || settings.model().isBlank()) {
            return List.of();
        }
        return List.of(settings.model());
    }
}
