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
import org.atmosphere.ai.ApiKeyResolver;
import org.atmosphere.ai.StreamingSession;

import java.util.Set;

/**
 * {@link org.atmosphere.ai.AgentRuntime} backed by a native
 * {@link AnthropicMessagesClient} that posts directly to
 * {@code https://api.anthropic.com/v1/messages}. Picked up via
 * {@link java.util.ServiceLoader} when this module's jar is on the
 * classpath alongside an Anthropic API key — resolved by
 * {@link ApiKeyResolver} from the {@code anthropic.api.key} system
 * property, the {@code ANTHROPIC_API_KEY} environment variable, or the
 * framework-resolved {@link AiConfig} key (in that order).
 *
 * <p>Priority is {@code 100} — the same convention every framework
 * runtime adapter (LangChain4j, Spring AI, ADK, Koog, Embabel,
 * Semantic Kernel) uses. The built-in OpenAI-compatible client at
 * priority {@code 0} remains the fallback when no provider-specific
 * runtime is available.</p>
 */
public class AnthropicAgentRuntime extends AbstractAgentRuntime<AnthropicMessagesClient> {

    /**
     * The provider name handed to {@link ApiKeyResolver#resolveProvider},
     * which derives the top-priority {@code anthropic.api.key} system-property
     * override from it ({@code <providerName>.api.key}).
     */
    private static final String PROVIDER_NAME = "anthropic";
    /**
     * The conventional Anthropic SDK key name. Resolved via
     * {@link ApiKeyResolver#resolveProvider} as a system property and then
     * an environment variable, ranking below the {@code anthropic.api.key}
     * override but above the framework-resolved generic key. Matches the name
     * the official Anthropic SDKs read from the environment.
     */
    private static final String API_KEY_ENV_VAR = "ANTHROPIC_API_KEY";
    private static final String VERSION_PROPERTY = "anthropic.version";
    private static final String BASE_URL_PROPERTY = "anthropic.base.url";
    private static final String MAX_TOKENS_PROPERTY = "anthropic.max.tokens";
    private static final String MODEL_PROPERTY = "anthropic.model";
    private static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    /**
     * The fallback model used only when neither the per-request
     * {@link AgentExecutionContext#model()} nor {@link AiConfig#model()} pins
     * one. Deployers override it without code via the {@code anthropic.model}
     * system property; the built-in default is {@value #DEFAULT_MODEL}.
     * Per-request and {@code AiConfig} models still win over this fallback
     * through {@link #effectiveModel(AgentExecutionContext, String)}.
     *
     * <p>Package-private so the regression test can assert the default and the
     * property override without depending on {@code AiConfig} static state.</p>
     */
    String defaultModel() {
        return systemProperty(MODEL_PROPERTY, DEFAULT_MODEL);
    }

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
                .apiKey(ApiKeyResolver.resolveProvider(PROVIDER_NAME, API_KEY_ENV_VAR,
                        settings != null ? settings.apiKey() : null))
                .anthropicVersion(systemProperty(VERSION_PROPERTY, "2023-06-01"));
        // Sovereign-deploy story: {@code anthropic.base.url} system property
        // wins outright; otherwise honor framework-resolved settings.baseUrl()
        // so the existing {@code LLM_BASE_URL} env var routes to an Anthropic-
        // compatible proxy without per-runtime config. Closes the Runtime
        // Truth gap surfaced by docs/sovereign-deploy.md (recipe section).
        var baseUrl = systemProperty(BASE_URL_PROPERTY,
                settings != null && settings.baseUrl() != null
                        && !settings.baseUrl().isBlank() ? settings.baseUrl() : null);
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        // max_tokens precedence (highest wins):
        //   1. anthropic.max.tokens system property (explicit per-runtime knob)
        //   2. AiConfig GenerationParams.maxTokens() (framework-level opt-in)
        //   3. the client default (DEFAULT_MAX_TOKENS)
        // The sysprop still wins over the framework override exactly as before;
        // GenerationParams only fills the gap when the sysprop is unset, so a
        // deployment that already pins anthropic.max.tokens is unaffected.
        var maxTokensRaw = System.getProperty(MAX_TOKENS_PROPERTY);
        if (maxTokensRaw != null && !maxTokensRaw.isBlank()) {
            try {
                builder.maxTokens(Integer.parseInt(maxTokensRaw.trim()));
            } catch (NumberFormatException ignored) {
                // Fall back to the client default — opt-in misconfiguration
                // is silent on purpose so an environment with a stray value
                // does not break the build-time wiring.
            }
        } else if (settings != null && settings.generation().maxTokens() != null) {
            builder.maxTokens(settings.generation().maxTokens());
        }
        // temperature / top_p / stop_sequences: the framework GenerationParams
        // overrides ride through to the Anthropic Messages request body when
        // set. Unset components leave the body byte-identical to today.
        if (settings != null) {
            builder.generation(settings.generation());
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
        // Honest Runtime Truth: the runtime is available iff an Anthropic API
        // key has been provided via the anthropic.api.key system property, the
        // ANTHROPIC_API_KEY env var, or AiConfig settings. Without one the
        // first POST to api.anthropic.com would 401 — and ApiKeyResolver
        // deliberately does NOT fall back to OPENAI_API_KEY/GEMINI_API_KEY, so
        // another provider's key can never mark Anthropic available.
        var settings = AiConfig.get();
        var settingsKey = settings != null ? settings.apiKey() : null;
        return ApiKeyResolver.resolveProvider(PROVIDER_NAME, API_KEY_ENV_VAR, settingsKey) != null;
    }

    @Override
    protected void doExecute(AnthropicMessagesClient client,
                             AgentExecutionContext context,
                             StreamingSession session) {
        streamThroughGateway(context, session, defaultModel(), client::stream);
    }

    @Override
    protected org.atmosphere.ai.ExecutionHandle doExecuteWithHandle(
            AnthropicMessagesClient client, AgentExecutionContext context,
            StreamingSession session) {
        return streamThroughGatewayWithHandle(context, session, defaultModel(), client::stream);
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
        //   VISION                — Content.Image translates to a native
        //                            image_block (base64 inline) in
        //                            AnthropicMessagesClient.userMessageWithParts
        //   MULTI_MODAL           — same code path; an Anthropic user message
        //                            can interleave text + image blocks
        //   NATIVE_STRUCTURED_OUTPUT — buildRequestBody threads the generated
        //                            JSON Schema into Anthropic's GA
        //                            output_config.format field so the model
        //                            enforces the schema at the provider level;
        //                            AUTO mode falls back to prompt injection on
        //                            a provider rejection.
        //   VIRTUAL_FILESYSTEM   — AnthropicMessagesClient declares the
        //                            memory_20250818 tool when the harness
        //                            FILESYSTEM feature scoped an
        //                            AgentFileSystem onto the session, and
        //                            AnthropicMemoryTool services all six
        //                            commands (view/create/str_replace/
        //                            insert/delete/rename) against
        //                            Atmosphere's bounded store.
        // NOT claimed:
        //   PLANNING              — the Anthropic API has no plan object to
        //                            delegate to; the harness PLANNING floor
        //                            (write_todos) covers this runtime.
        //   TOOL_CALL_DELTA       — input_json_delta arrives but is not
        //                            forwarded via session.toolCallDelta yet
        //   AUDIO                 — Anthropic Messages has no audio block
        //                            (Content.Audio is dropped with a debug log)
        //   PROMPT_CACHING        — cache_control blocks deferred
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.SYSTEM_PROMPT,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.NATIVE_STRUCTURED_OUTPUT,
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
                AiCapability.VIRTUAL_FILESYSTEM,
                // CANCELLATION: doExecuteWithHandle returns a live handle whose
                // cancel() flips a `cancelled` flag the streaming worker polls,
                // stopping token forwarding and settling whenDone().
                AiCapability.CANCELLATION);
    }
}
