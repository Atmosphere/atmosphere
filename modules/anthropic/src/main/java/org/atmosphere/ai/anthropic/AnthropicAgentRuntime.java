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
package org.atmosphere.ai.anthropic;

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
 * {@link AnthropicMessagesClient} that posts directly to
 * {@code https://api.anthropic.com/v1/messages}. Picked up via
 * {@link java.util.ServiceLoader} when this module's jar is on the
 * classpath alongside an {@code anthropic.api.key} configuration.
 *
 * <p>Priority is {@code 100} — the same convention every framework
 * runtime adapter (LangChain4j, Spring AI, ADK, Koog, Embabel,
 * Semantic Kernel) uses. The built-in OpenAI-compatible client at
 * priority {@code 0} remains the fallback when no provider-specific
 * runtime is available.</p>
 */
public class AnthropicAgentRuntime extends AbstractAgentRuntime<AnthropicMessagesClient> {

    private static final String API_KEY_PROPERTY = "anthropic.api.key";
    private static final String VERSION_PROPERTY = "anthropic.version";
    private static final String BASE_URL_PROPERTY = "anthropic.base.url";
    private static final String MAX_TOKENS_PROPERTY = "anthropic.max.tokens";

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    protected String nativeClientClassName() {
        return "org.atmosphere.ai.anthropic.AnthropicMessagesClient";
    }

    @Override
    protected String clientDescription() {
        return "AnthropicMessagesClient";
    }

    /**
     * Build the client from {@link AiConfig.LlmSettings} when the framework
     * has resolved one. Falls back to system-property configuration so
     * standalone tests and CLI usage work without an Atmosphere-wide config
     * (mirrors how the built-in runtime reads its own settings).
     */
    @Override
    protected AnthropicMessagesClient createNativeClient(AiConfig.LlmSettings settings) {
        var builder = AnthropicMessagesClient.builder()
                .apiKey(systemProperty(API_KEY_PROPERTY,
                        settings != null ? settings.apiKey() : null))
                .anthropicVersion(systemProperty(VERSION_PROPERTY, "2023-06-01"));
        var baseUrl = systemProperty(BASE_URL_PROPERTY, null);
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
    protected void doExecute(AnthropicMessagesClient client,
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
            AnthropicMessagesClient client, AgentExecutionContext context,
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
        return "claude-opus-4-7";
    }

    private static String systemProperty(String key, String fallback) {
        var value = System.getProperty(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    @Override
    public Set<AiCapability> capabilities() {
        // Honest floor — every entry here corresponds to a code path
        // AnthropicMessagesClient actually exercises:
        //   TEXT_STREAMING        — SSE text_delta forwarding via session.send()
        //   SYSTEM_PROMPT         — top-level "system" field on every request
        //   STRUCTURED_OUTPUT     — pipeline-layer schema injection via
        //                            StructuredOutputCapturingSession works
        //                            for any runtime that honors SYSTEM_PROMPT
        //   TOOL_CALLING          — tool_use → tool_result loop with shared
        //                            ToolExecutionHelper.executeWithApproval
        //   TOOL_APPROVAL         — every tool dispatch routes through the
        //                            executeWithApproval gate, so
        //                            @RequiresApproval fires uniformly
        //   TOKEN_USAGE           — message_delta.usage parsed and emitted via
        //                            session.usage()
        //   CONVERSATION_MEMORY   — assembleMessages threads context.history()
        //                            into every outbound request
        //   BUDGET_ENFORCEMENT    — pipeline-level decorator taps session.usage()
        //   CONFIDENCE_SCORES     — pipeline-level cue appended to the system
        //                            prompt; runtime honors SYSTEM_PROMPT
        //   PASSIVATION           — assembleMessages-based; checkpoint module
        //                            rehydrates context.history() unchanged.
        //   PER_REQUEST_RETRY     — AbstractAgentRuntime.executeWithOuterRetry
        //                            wraps doExecute when context.retryPolicy()
        //                            requests retries; same posture as
        //                            AgentScope / Spring AI Alibaba.
        // NOT claimed:
        //   TOOL_CALL_DELTA       — input_json_delta arrives but is not
        //                            forwarded via session.toolCallDelta yet
        //   VISION/AUDIO/MULTI_MODAL — image_block translation deferred
        //   PROMPT_CACHING        — cache_control blocks deferred
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
                AiCapability.PER_REQUEST_RETRY);
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
