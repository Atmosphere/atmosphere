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
package org.atmosphere.ai.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.Gemini;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.atmosphere.ai.AbstractAgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link org.atmosphere.ai.AiSupport} implementation backed by Google ADK's {@link Runner}.
 *
 * <p>Auto-detected when {@code google-adk} is on the classpath.
 * The runner must be configured via {@link #setRunner} — typically done
 * by application configuration or Spring auto-configuration.</p>
 */
public class AdkAgentRuntime extends AbstractAgentRuntime<Runner> {

    private static final Logger logger = LoggerFactory.getLogger(AdkAgentRuntime.class);

    private static volatile String defaultUserId = "atmosphere-user";
    private static volatile String defaultSessionId = "atmosphere-session";

    private static volatile com.google.adk.agents.ContextCacheConfig contextCacheConfig;

    /**
     * Configure ADK-level prompt caching. ADK's {@code ContextCacheConfig} is
     * App-scoped (set at construction time, not per-request). Call once at
     * startup — typically from Spring auto-configuration or programmatic setup.
     *
     * @param ttl        cache TTL (Gemini cached-content expiry)
     * @param minTokens  minimum token count to trigger caching (Gemini threshold)
     */
    public static void setCacheConfig(java.time.Duration ttl, int minTokens) {
        contextCacheConfig = new com.google.adk.agents.ContextCacheConfig(0, ttl, minTokens);
        logger.info("ADK ContextCacheConfig set: ttl={}, minTokens={}", ttl, minTokens);
    }

    private static void applyCacheConfig(com.google.adk.apps.App.Builder appBuilder) {
        var config = contextCacheConfig;
        if (config != null) {
            appBuilder.contextCacheConfig(config);
        }
    }

    /**
     * Resolve the {@link com.google.adk.agents.ContextCacheConfig} to apply
     * for the given invocation. Prefers a per-request {@code CacheHint}
     * (derived from {@code context.metadata()}) over the process-wide
     * {@link #setCacheConfig} default, falling back to {@code null} when
     * neither is set.
     *
     * <p>Per-request resolution is what makes {@code PROMPT_CACHING} an
     * honest capability on this runtime: every invocation that carries a
     * {@link org.atmosphere.ai.llm.CacheHint#enabled()} hint gets a fresh
     * per-request {@link com.google.adk.apps.App} wired with that TTL and
     * the default {@code minTokens} threshold, so ADK's caching path fires
     * even when the process has no global config installed.</p>
     */
    private static com.google.adk.agents.ContextCacheConfig resolveCacheConfig(
            AgentExecutionContext context) {
        var hint = org.atmosphere.ai.llm.CacheHint.from(context);
        if (hint.enabled()) {
            // ADK's ContextCacheConfig record: (maxInvocations, ttl, minTokens)
            // maxInvocations=0 means "no limit" per ADK convention.
            // minTokens=32 is a conservative threshold — Gemini charges for
            // cached content above this floor; raise it via setCacheConfig if
            // you want larger prompt blocks to bypass caching.
            var ttl = hint.ttl().orElse(java.time.Duration.ofMinutes(5));
            return new com.google.adk.agents.ContextCacheConfig(0, ttl, 32);
        }
        return contextCacheConfig;
    }

    /**
     * Per-runner cache of initialized {@code userId:sessionId} keys. Weak-keyed
     * so runners that go out of scope (e.g. the per-request tool-calling
     * runners built by {@code buildRequestRunner} on every tool-bearing
     * invocation) do not leak, and so the cache resets on a fresh runner
     * instance. Previously this was a single global {@code Set<String>}, which
     * meant the second tool-calling request with the same {@code userId} and
     * {@code sessionId} on a freshly-built runner would skip session creation
     * (because the global key was still set by the first runner), leaving the
     * new runner's in-memory session service empty and its subsequent
     * {@code runAsync} call stranded.
     */
    private static final Map<Runner, Set<String>> KNOWN_SESSIONS_BY_RUNNER =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    @Override
    public String name() {
        return "google-adk";
    }

    @Override
    protected String nativeClientClassName() {
        return "com.google.adk.runner.Runner";
    }

    @Override
    protected String clientDescription() {
        return "Runner";
    }

    @Override
    protected String configurationHint() {
        return "Call AdkAgentRuntime.setRunner() or use Spring auto-configuration.";
    }

    @Override
    protected Runner createNativeClient(AiConfig.LlmSettings settings) {
        var apiKey = settings.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        if (settings.model() != null && !settings.model().startsWith("gemini")) {
            logger.warn("ADK only supports Gemini models natively. '{}' may not work. "
                    + "Consider atmosphere-spring-ai or atmosphere-langchain4j for other providers.",
                    settings.model());
        }

        var gemini = new Gemini(settings.model(), apiKey);
        var agent = LlmAgent.builder()
                .name("atmosphere-agent")
                .model(gemini)
                .instruction("You are a helpful assistant.")
                .build();
        var appBuilder = com.google.adk.apps.App.builder()
                .name("atmosphere")
                .rootAgent(agent);
        applyCacheConfig(appBuilder);
        var runner = Runner.builder().app(appBuilder.build()).build();
        logger.info("ADK auto-configured: model={}", settings.model());
        return runner;
    }

    /**
     * Set the {@link Runner} to use for streaming.
     */
    public static void setRunner(Runner adkRunner) {
        staticRunner = adkRunner;
    }

    // Held for static setter compatibility with Spring auto-configuration
    private static volatile Runner staticRunner;

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        // If a static runner was set via Spring auto-configuration, use it
        if (getNativeClient() == null && staticRunner != null) {
            setNativeClient(staticRunner);
        }
        super.configure(settings);
    }

    /**
     * Set default user and session IDs for ADK invocations.
     */
    public static void setDefaults(String userId, String sessionId) {
        defaultUserId = userId;
        defaultSessionId = sessionId;
    }

    /**
     * Create and set a tool-less Runner from the given settings. Kept for
     * Spring auto-configuration; tool-calling flows rebuild the runner per request
     * inside {@link #doExecute} so each invocation captures its own
     * {@link StreamingSession} and {@link org.atmosphere.ai.approval.ApprovalStrategy}.
     *
     * @param settings the LLM settings (model, API key, etc.)
     * @param tools    ignored — tool registration is now per-request for HITL correctness
     */
    public static void configureWithTools(AiConfig.LlmSettings settings,
                                          List<ToolDefinition> tools) {
        var apiKey = settings.apiKey();
        var gemini = new Gemini(settings.model(), apiKey);
        var agent = LlmAgent.builder()
                .name("atmosphere-agent")
                .model(gemini)
                .instruction("You are a helpful assistant.")
                .build();
        var appBuilder = com.google.adk.apps.App.builder()
                .name("atmosphere")
                .rootAgent(agent);
        applyCacheConfig(appBuilder);
        staticRunner = Runner.builder().app(appBuilder.build()).build();
        if (tools != null && !tools.isEmpty()) {
            logger.info("ADK configured (model={}); {} tools will be registered per-request",
                    settings.model(), tools.size());
        } else {
            logger.info("ADK configured: model={}", settings.model());
        }
    }

    /**
     * Build a per-request {@link Runner} whose tools capture the caller's
     * {@link StreamingSession} and {@link org.atmosphere.ai.approval.ApprovalStrategy}.
     * ADK requires tools at agent construction time, so each streaming request rebuilds
     * the agent and runner — this is the only way to bind HITL context per invocation
     * without a ThreadLocal that ADK's reactive scheduler would not propagate.
     */
    private Runner buildRequestRunner(AgentExecutionContext context, StreamingSession session) {
        var settings = AiConfig.get();
        if (settings == null) {
            settings = AiConfig.fromEnvironment();
        }
        var modelName = (context.model() != null && !context.model().isBlank())
                ? context.model() : settings.model();
        var gemini = new Gemini(modelName, settings.apiKey());

        var adkTools = AdkToolBridge.toAdkTools(
                context.tools(), session, context.approvalStrategy(), context.listeners(),
                context.approvalPolicy());
        var instruction = context.systemPrompt() != null && !context.systemPrompt().isEmpty()
                ? context.systemPrompt() : "You are a helpful assistant.";

        var agentBuilder = LlmAgent.builder()
                .name("atmosphere-agent")
                .model(gemini)
                .instruction(instruction);

        if (!adkTools.isEmpty()) {
            agentBuilder.tools(adkTools);
        }

        var appBuilder = com.google.adk.apps.App.builder()
                .name("atmosphere")
                .rootAgent(agentBuilder.build());
        var cacheConfig = resolveCacheConfig(context);
        if (cacheConfig != null) {
            appBuilder.contextCacheConfig(cacheConfig);
        }
        logger.debug("ADK per-request runner built with {} tools (cacheConfig={})",
                adkTools.size(), cacheConfig != null);
        return Runner.builder().app(appBuilder.build()).build();
    }

    @Override
    protected void doExecute(Runner adkRunner, AgentExecutionContext context, StreamingSession session) {
        // Delegate to the cancellation-aware variant and block on whenDone() so the
        // legacy void contract holds. This keeps the Flowable subscription logic in
        // one place instead of drifting between execute and executeWithHandle.
        var handle = doExecuteWithHandle(adkRunner, context, session);
        try {
            handle.whenDone().join();
        } catch (Exception e) {
            if (!session.isClosed()) {
                session.error(e);
            }
        }
    }

    @Override
    protected ExecutionHandle doExecuteWithHandle(
            Runner adkRunner, AgentExecutionContext context, StreamingSession session) {
        // For tool-calling requests AND requests carrying a per-request
        // CacheHint, build a fresh runner whose App has the resolved
        // ContextCacheConfig wired in. ADK's ContextCacheConfig is
        // App.Builder-scoped (not mutable at request time), so a caller
        // who supplies a CacheHint forces a per-request Runner — the
        // cached tool-less fallback runner held in getNativeClient() is
        // only usable when neither tools nor a cache hint are present.
        var tools = context.tools();
        var adkHint = org.atmosphere.ai.llm.CacheHint.from(context);
        var needsPerRequestRunner = !tools.isEmpty() || adkHint.enabled();
        Runner requestRunner = needsPerRequestRunner
                ? buildRequestRunner(context, session)
                : adkRunner;

        var userId = context.userId() != null ? context.userId() : defaultUserId;
        var sessionId = context.sessionId() != null ? context.sessionId() : defaultSessionId;

        ensureSession(requestRunner, userId, sessionId);

        if (adkHint.enabled() && logger.isDebugEnabled()) {
            logger.debug("ADK: per-request CacheHint (policy={}, ttl={}) applied via "
                    + "fresh per-request App.Builder.contextCacheConfig(...)",
                    adkHint.policy(), adkHint.ttl().orElse(null));
        }

        // Translate any multi-modal Content.Image / Content.Audio / Content.File
        // parts on the context into ADK Part.fromBytes(byte[], mimeType) and
        // attach them alongside the text prompt. ADK's Content.fromParts
        // accepts a varargs Part[], so we assemble a list and splat it.
        var partsList = new java.util.ArrayList<Part>();
        partsList.add(Part.fromText(context.message()));
        for (var p : context.parts()) {
            if (p instanceof org.atmosphere.ai.Content.Image img) {
                partsList.add(Part.fromBytes(img.data(), img.mimeType()));
            } else if (p instanceof org.atmosphere.ai.Content.Audio audio) {
                partsList.add(Part.fromBytes(audio.data(), audio.mimeType()));
            } else if (p instanceof org.atmosphere.ai.Content.File file) {
                // ADK also accepts generic binary blobs via fromBytes;
                // file-name metadata is lost at this layer but Gemini's
                // multi-modal input doesn't require it.
                partsList.add(Part.fromBytes(file.data(), file.mimeType()));
            }
            // Content.Text is folded into the prompt text block above.
        }

        var events = requestRunner.runAsync(
                userId,
                sessionId,
                Content.fromParts(partsList.toArray(new Part[0]))
        );

        // D-6 follow-up: wire the AdkEventAdapter's existing cancel() + whenDone()
        // into the Phase 2 ExecutionHandle SPI so external callers can abort an
        // in-flight ADK run via the unified API.
        var adapter = AdkEventAdapter.bridge(events, session);
        return new ExecutionHandle() {
            private final java.util.concurrent.atomic.AtomicBoolean cancelled =
                    new java.util.concurrent.atomic.AtomicBoolean();

            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    adapter.cancel();
                }
            }

            @Override
            public boolean isDone() {
                return adapter.whenDone().isDone();
            }

            @Override
            public java.util.concurrent.CompletableFuture<Void> whenDone() {
                return adapter.whenDone();
            }
        };
    }

    private static void ensureSession(Runner adkRunner, String userId, String sessionId) {
        var key = userId + ":" + sessionId;
        var perRunnerCache = KNOWN_SESSIONS_BY_RUNNER.computeIfAbsent(
                adkRunner, r -> ConcurrentHashMap.newKeySet());
        if (perRunnerCache.contains(key)) {
            return;
        }

        var existing = adkRunner.sessionService()
                .getSession(adkRunner.appName(), userId, sessionId, Optional.empty())
                .blockingGet();
        if (existing == null) {
            adkRunner.sessionService()
                    .createSession(adkRunner.appName(), userId, Map.of(), sessionId)
                    .blockingGet();
            logger.debug("Created ADK session: userId={}, sessionId={}", userId, sessionId);
        }
        perRunnerCache.add(key);
    }

    @Override
    public Set<AiCapability> capabilities() {
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.TOOL_CALLING,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.AGENT_ORCHESTRATION,
                AiCapability.CONVERSATION_MEMORY,
                AiCapability.SYSTEM_PROMPT,
                AiCapability.TOOL_APPROVAL,
                // VISION / AUDIO / MULTI_MODAL are honest: doExecute translates
                // Content.Image / Audio / File parts into Gemini Part.fromBytes
                // (byte[], mimeType) and attaches them to the runAsync input.
                // Gemini models support all three input modalities natively.
                AiCapability.VISION,
                AiCapability.AUDIO,
                AiCapability.MULTI_MODAL,
                // TOKEN_USAGE: AdkEventAdapter reads event.usageMetadata() on
                // every ADK Event and emits a typed TokenUsage record via
                // session.usage() — see AdkEventAdapter.java:259-265.
                AiCapability.TOKEN_USAGE,
                // PER_REQUEST_RETRY: honored via AbstractAgentRuntime's
                // outer retry wrapper (executeWithOuterRetry). Pre-stream
                // transient failures retry on top of ADK's native HttpClient
                // retries.
                AiCapability.PER_REQUEST_RETRY,
                // PROMPT_CACHING: resolveCacheConfig reads CacheHint.from(context)
                // and builds a per-request ContextCacheConfig with the hint's
                // TTL. When a hint is present, doExecuteWithHandle forces a
                // fresh per-request Runner via buildRequestRunner so the
                // ContextCacheConfig wires into a new App.Builder — working
                // around ADK's App-scoped caching limitation.
                AiCapability.PROMPT_CACHING
        );
    }

    @Override
    public java.util.List<String> models() {
        // ADK only supports Gemini natively; the configured model name lives
        // on AiConfig. Per-request overrides via context.model() take
        // precedence when the request builds its per-request runner.
        var settings = AiConfig.get();
        if (settings == null || settings.model() == null || settings.model().isBlank()) {
            return java.util.List.of();
        }
        return java.util.List.of(settings.model());
    }
}
