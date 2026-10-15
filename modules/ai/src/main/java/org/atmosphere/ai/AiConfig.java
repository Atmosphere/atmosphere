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
package org.atmosphere.ai;

import org.atmosphere.ai.llm.FakeLlmClient;
import org.atmosphere.ai.llm.LlmClient;
import org.atmosphere.ai.llm.OpenAiCompatibleClient;
import org.atmosphere.ai.llm.PromptCacheKeyMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Configuration for the Atmosphere AI module.
 *
 * <p>Properties can be set via Atmosphere init-params (web.xml, {@code @ManagedService(atmosphereConfig=...)}),
 * environment variables, or programmatically. Environment variables take precedence
 * when using {@link #fromEnvironment()}.</p>
 *
 * <p>The {@code atmosphere.ai.*} knobs ({@link #knobs()}: generation parameters,
 * prompt-cache-key, logprobs, native structured output, planning, filesystem,
 * embedding model, runtime) are read from the JVM system property, then the
 * {@code LLM_*} environment variable, then the host configuration a Spring Boot
 * starter or the Quarkus extension installed with {@link #installHostConfig}.</p>
 *
 * <h3>Atmosphere init-params</h3>
 * <pre>
 * {@literal @}ManagedService(path = "/ai-chat", atmosphereConfig = {
 *     AiConfig.LLM_MODEL + "=gemini-2.5-flash",
 *     AiConfig.LLM_MODE + "=remote",
 *     AiConfig.LLM_API_KEY + "=AIza..."
 * })
 * </pre>
 *
 * <h3>Environment variables</h3>
 * <table>
 *   <tr><th>Variable</th><th>Description</th><th>Default</th></tr>
 *   <tr><td>{@code LLM_MODE}</td><td>{@code remote} (cloud API) or {@code local} (Ollama)</td><td>{@code remote}</td></tr>
 *   <tr><td>{@code LLM_MODEL}</td><td>Model name (e.g. {@code gemini-2.5-flash}, {@code gpt-4o}, {@code llama3.2})</td><td>{@code gemini-2.5-flash}</td></tr>
 *   <tr><td>{@code LLM_API_KEY}, {@code OPENAI_API_KEY}, or {@code GEMINI_API_KEY}</td><td>API key for the provider</td><td>(none)</td></tr>
 *   <tr><td>{@code LLM_BASE_URL}</td><td>Override the API endpoint (auto-detected if omitted)</td><td>(auto)</td></tr>
 *   <tr><td>{@code LLM_EMBEDDING_MODEL}</td><td>Embedding model of the Built-in embedding runtime (sysprop {@code atmosphere.ai.embedding-model}); required in {@code local} mode</td><td>{@code text-embedding-3-small} remote, none local</td></tr>
 * </table>
 */
public final class AiConfig {

    private static final Logger logger = LoggerFactory.getLogger(AiConfig.class);

    // -- Atmosphere init-param constants (org.atmosphere.ai.* namespace) --

    /**
     * LLM mode: {@code remote} (cloud API) or {@code local} (Ollama).
     * <p>Default: {@code remote}</p>
     * <p>Value: {@code org.atmosphere.ai.llmMode}</p>
     */
    public static final String LLM_MODE = "org.atmosphere.ai.llmMode";

    /**
     * LLM model name (e.g. {@code gemini-2.5-flash}, {@code gpt-4o}, {@code llama3.2}).
     * <p>Default: {@code gemini-2.5-flash}</p>
     * <p>Value: {@code org.atmosphere.ai.llmModel}</p>
     */
    public static final String LLM_MODEL = "org.atmosphere.ai.llmModel";

    /**
     * API key for the LLM provider.
     * <p>Default: (none)</p>
     * <p>Value: {@code org.atmosphere.ai.llmApiKey}</p>
     */
    public static final String LLM_API_KEY = "org.atmosphere.ai.llmApiKey";

    /**
     * Override the LLM API base URL. When set, disables auto-detection.
     * <p>Default: auto-detected from mode and model name</p>
     * <p>Value: {@code org.atmosphere.ai.llmBaseUrl}</p>
     */
    public static final String LLM_BASE_URL = "org.atmosphere.ai.llmBaseUrl";

    /**
     * Tri-state control of OpenAI {@code prompt_cache_key} emission:
     * {@code enabled} (force-emit), {@code disabled} (force-suppress), or
     * {@code auto} (legacy host heuristic). Parsed leniently — see
     * {@link PromptCacheKeyMode#parse(String)} — so malformed values fall back
     * to {@code auto} instead of throwing.
     * <p>Default: {@code auto}</p>
     * <p>Sysprop: {@code atmosphere.ai.prompt-cache-key}; env: {@code LLM_PROMPT_CACHE_KEY}</p>
     */
    public static final String PROMPT_CACHE_KEY_PROPERTY = "atmosphere.ai.prompt-cache-key";

    /**
     * Environment-variable name for the tri-state {@code prompt_cache_key}
     * control. See {@link #PROMPT_CACHE_KEY_PROPERTY}.
     */
    public static final String PROMPT_CACHE_KEY_ENV = "LLM_PROMPT_CACHE_KEY";

    /**
     * Tri-state control of OpenAI {@code logprobs} emission by the Built-in
     * client when a confidence elicitation is active: {@code enabled}
     * (force-request), {@code disabled} (force-suppress), or {@code auto}
     * (shared endpoint allow-list). Parsed leniently — see
     * {@link org.atmosphere.ai.llm.LogprobsMode#parse(String)} — so malformed
     * values fall back to {@code auto} instead of throwing.
     * <p>Default: {@code auto}</p>
     * <p>Sysprop: {@code atmosphere.ai.logprobs}; env: {@code LLM_LOGPROBS}</p>
     */
    public static final String LOGPROBS_PROPERTY = "atmosphere.ai.logprobs";

    /**
     * Environment-variable name for the tri-state {@code logprobs} control.
     * See {@link #LOGPROBS_PROPERTY}.
     */
    public static final String LOGPROBS_ENV = "LLM_LOGPROBS";

    /**
     * Tri-state control of provider-native structured-output enforcement:
     * {@code enabled} (apply + fail-fast on rejection), {@code disabled}
     * (prompt-injection only), or {@code auto} (apply + graceful fall-back).
     * Parsed leniently — see {@link NativeStructuredOutputMode#parse(String)} — so
     * malformed values fall back to {@code auto} instead of throwing.
     * <p>Default: {@code auto}</p>
     * <p>Sysprop: {@code atmosphere.ai.native-structured-output}; env: {@code LLM_NATIVE_STRUCTURED_OUTPUT}</p>
     */
    public static final String NATIVE_STRUCTURED_OUTPUT_PROPERTY = "atmosphere.ai.native-structured-output";

    /**
     * Environment-variable name for the tri-state native structured-output
     * control. See {@link #NATIVE_STRUCTURED_OUTPUT_PROPERTY}.
     */
    public static final String NATIVE_STRUCTURED_OUTPUT_ENV = "LLM_NATIVE_STRUCTURED_OUTPUT";

    /**
     * Tri-state control of the harness PLANNING surface: {@code auto} (native
     * plan machinery when the resolved runtime advertises
     * {@link AiCapability#PLANNING}, built-in {@code write_todos} floor
     * otherwise), {@code builtin}, or {@code native}. Parsed leniently — see
     * {@link org.atmosphere.ai.plan.PlanningMode#parse(String)} — so malformed
     * values fall back to {@code auto} instead of throwing.
     * <p>Default: {@code auto}</p>
     * <p>Sysprop: {@code atmosphere.ai.planning}; env: {@code LLM_PLANNING}</p>
     */
    public static final String PLANNING_PROPERTY = "atmosphere.ai.planning";

    /**
     * Environment-variable name for the tri-state planning control. See
     * {@link #PLANNING_PROPERTY}.
     */
    public static final String PLANNING_ENV = "LLM_PLANNING";

    /**
     * Tri-state control of the harness FILESYSTEM surface: {@code auto}
     * (native file machinery when the resolved runtime advertises
     * {@link AiCapability#VIRTUAL_FILESYSTEM}, built-in
     * {@code ls}/{@code read_file}/... floor otherwise), {@code builtin}, or
     * {@code native}. Parsed leniently — see
     * {@link org.atmosphere.ai.fs.FilesystemMode#parse(String)} — so malformed
     * values fall back to {@code auto} instead of throwing.
     * <p>Default: {@code auto}</p>
     * <p>Sysprop: {@code atmosphere.ai.filesystem}; env: {@code LLM_FILESYSTEM}</p>
     */
    public static final String FILESYSTEM_PROPERTY = "atmosphere.ai.filesystem";

    /**
     * Environment-variable name for the tri-state filesystem control. See
     * {@link #FILESYSTEM_PROPERTY}.
     */
    public static final String FILESYSTEM_ENV = "LLM_FILESYSTEM";

    // -- Generation parameter knobs (sysprop / env) --
    //
    // All four are opt-in: when unset the resolved GenerationParams collapses to
    // GenerationParams.defaults() (all null) and the wire stays byte-identical
    // to the pre-feature behavior. Malformed numeric values are logged and
    // ignored (never throw — Correctness Invariant #4, fail at the boundary).
    // The sysprop wins over the env var, mirroring the prompt-cache-key precedence.

    /** Sysprop for the sampling temperature override. Env: {@link #TEMPERATURE_ENV}. */
    public static final String TEMPERATURE_PROPERTY = "atmosphere.ai.temperature";
    /** Env var for the sampling temperature override. See {@link #TEMPERATURE_PROPERTY}. */
    public static final String TEMPERATURE_ENV = "LLM_TEMPERATURE";

    /** Sysprop for the max-tokens override. Env: {@link #MAX_TOKENS_ENV}. */
    public static final String MAX_TOKENS_PROPERTY = "atmosphere.ai.max-tokens";
    /** Env var for the max-tokens override. See {@link #MAX_TOKENS_PROPERTY}. */
    public static final String MAX_TOKENS_ENV = "LLM_MAX_TOKENS";

    /** Sysprop for the top-p override. Env: {@link #TOP_P_ENV}. */
    public static final String TOP_P_PROPERTY = "atmosphere.ai.top-p";
    /** Env var for the top-p override. See {@link #TOP_P_PROPERTY}. */
    public static final String TOP_P_ENV = "LLM_TOP_P";

    /**
     * Sysprop naming the model {@code BuiltInEmbeddingRuntime} posts to
     * {@code /v1/embeddings} (e.g. {@code nomic-embed-text} on Ollama). Env:
     * {@link #EMBEDDING_MODEL_ENV}. Required in {@code llm.mode=local} unless
     * {@code LLM_MODEL} is itself an embedding model: a local server does not
     * serve the remote default {@code text-embedding-3-small}.
     */
    public static final String EMBEDDING_MODEL_PROPERTY = "atmosphere.ai.embedding-model";
    /** Env var naming the Built-in embedding model. See {@link #EMBEDDING_MODEL_PROPERTY}. */
    public static final String EMBEDDING_MODEL_ENV = "LLM_EMBEDDING_MODEL";

    /** Sysprop for the comma-separated stop sequences. Env: {@link #STOP_ENV}. */
    public static final String STOP_PROPERTY = "atmosphere.ai.stop";
    /** Env var for the comma-separated stop sequences. See {@link #STOP_PROPERTY}. */
    public static final String STOP_ENV = "LLM_STOP";

    /**
     * Names the {@link AgentRuntime} to run, matched against
     * {@link AgentRuntime#name()} ignoring case and surrounding blanks (for
     * example {@code built-in}, {@code spring-ai}, {@code langchain4j}). It
     * selects among the runtimes on the classpath: the runtime's module must
     * still be a dependency. When it names no available runtime,
     * {@link AgentRuntimeResolver} fails instead of running another one. Unset,
     * the highest-priority available runtime runs. Env: {@link #RUNTIME_ENV}.
     */
    public static final String RUNTIME_PROPERTY = "atmosphere.ai.runtime";
    /** Env var naming the {@link AgentRuntime} to run. See {@link #RUNTIME_PROPERTY}. */
    public static final String RUNTIME_ENV = "LLM_RUNTIME";

    /**
     * Sysprop for how long a run keeps going after the client that started it
     * loses its connection, waiting for that client to reattach with the run id
     * before the model call is cancelled. Env: {@link #REATTACH_GRACE_ENV}.
     * Accepts an ISO-8601 duration ({@code PT30S}), a number with a unit
     * ({@code 500ms}, {@code 30s}, {@code 2m}, {@code 1h}) or a bare number of
     * seconds; {@code 0} cancels on the disconnect, as a client that closes the
     * connection on purpose always is. Default {@link #DEFAULT_REATTACH_GRACE}.
     */
    public static final String REATTACH_GRACE_PROPERTY = "atmosphere.ai.reattach.grace";
    /** Env var for the reattach grace. See {@link #REATTACH_GRACE_PROPERTY}. */
    public static final String REATTACH_GRACE_ENV = "LLM_REATTACH_GRACE";
    /** Default reattach grace. See {@link #REATTACH_GRACE_PROPERTY}. */
    public static final java.time.Duration DEFAULT_REATTACH_GRACE = java.time.Duration.ofSeconds(30);

    // -- Well-known endpoints --

    public static final String GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/openai";
    public static final String OPENAI_ENDPOINT = "https://api.openai.com/v1";
    public static final String OLLAMA_ENDPOINT = "http://localhost:11434/v1";

    // -- Default model --

    public static final String DEFAULT_MODEL = "gemini-2.5-flash";
    public static final String DEFAULT_MODE = "remote";

    private AiConfig() {
    }

    /**
     * Immutable settings record holding the resolved LLM configuration.
     *
     * <p>The {@code apiKey} component carries the framework-resolved provider
     * API key regardless of the concrete {@link LlmClient} type, so every
     * {@link AgentRuntime} adapter can read the same key from
     * {@link #apiKey()} instead of inventing its own
     * {@code <provider>.api.key} lookup. On the OpenAI-compatible path it
     * holds exactly the key the underlying {@link OpenAiCompatibleClient} was
     * built with; in fake mode it is {@code null}.</p>
     */
    public record LlmSettings(LlmClient client, String model, String mode, String baseUrl,
                              String apiKey, PromptCacheKeyMode promptCacheKeyMode,
                              GenerationParams generation) {

        /**
         * Canonical-constructor null guard: a {@code null}
         * {@code promptCacheKeyMode} collapses to {@link PromptCacheKeyMode#AUTO}
         * and a {@code null} {@code generation} collapses to
         * {@link GenerationParams#defaults()} so the default path stays
         * byte-identical to the pre-flag / pre-{@code GenerationParams} wire
         * output.
         */
        public LlmSettings {
            promptCacheKeyMode = promptCacheKeyMode == null ? PromptCacheKeyMode.AUTO : promptCacheKeyMode;
            generation = generation == null ? GenerationParams.defaults() : generation;
        }

        /**
         * Backward-compatible 4-arg constructor. Defaults {@code apiKey} the
         * historical way — deriving it from the client only when that client
         * is an {@link OpenAiCompatibleClient}, {@code null} otherwise — and
         * defaults {@code promptCacheKeyMode} to {@link PromptCacheKeyMode#AUTO}
         * so existing callers that pre-date both stored components keep
         * identical behavior.
         *
         * @param client  the resolved LLM client
         * @param model   the model name
         * @param mode    the mode ({@code remote}, {@code local}, or {@code fake})
         * @param baseUrl the resolved base URL
         */
        public LlmSettings(LlmClient client, String model, String mode, String baseUrl) {
            this(client, model, mode, baseUrl,
                    client instanceof OpenAiCompatibleClient oac ? oac.apiKey() : null,
                    PromptCacheKeyMode.AUTO, GenerationParams.defaults());
        }

        /**
         * Backward-compatible 5-arg constructor preserving the post-B1 shape
         * ({@code apiKey} supplied explicitly) while defaulting the
         * {@code promptCacheKeyMode} component to {@link PromptCacheKeyMode#AUTO}
         * and the {@code generation} component to
         * {@link GenerationParams#defaults()}.
         *
         * @param client  the resolved LLM client
         * @param model   the model name
         * @param mode    the mode ({@code remote}, {@code local}, or {@code fake})
         * @param baseUrl the resolved base URL
         * @param apiKey  the framework-resolved provider API key (may be {@code null})
         */
        public LlmSettings(LlmClient client, String model, String mode, String baseUrl, String apiKey) {
            this(client, model, mode, baseUrl, apiKey, PromptCacheKeyMode.AUTO,
                    GenerationParams.defaults());
        }

        /**
         * Backward-compatible 6-arg constructor preserving the post-B5 shape
         * ({@code promptCacheKeyMode} supplied explicitly) while defaulting the
         * new {@code generation} component to {@link GenerationParams#defaults()}
         * so existing callers that pre-date the generation-parameter component
         * keep identical behavior.
         *
         * @param client             the resolved LLM client
         * @param model              the model name
         * @param mode               the mode ({@code remote}, {@code local}, or {@code fake})
         * @param baseUrl            the resolved base URL
         * @param apiKey             the framework-resolved provider API key (may be {@code null})
         * @param promptCacheKeyMode the tri-state {@code prompt_cache_key} control
         */
        public LlmSettings(LlmClient client, String model, String mode, String baseUrl,
                           String apiKey, PromptCacheKeyMode promptCacheKeyMode) {
            this(client, model, mode, baseUrl, apiKey, promptCacheKeyMode,
                    GenerationParams.defaults());
        }

        /**
         * @return {@code true} if running against a local model (e.g. Ollama)
         */
        public boolean isLocal() {
            return "local".equalsIgnoreCase(mode);
        }

        /**
         * @return {@code true} if running in fake/demo mode
         */
        public boolean isFake() {
            return "fake".equalsIgnoreCase(mode);
        }

        /**
         * Whether there is a model to talk to.
         *
         * <p>Ask this instead of testing {@link #apiKey()} directly. A locally
         * served backend — Ollama, vLLM, LM Studio — needs no credential, so an
         * absent key says nothing about reachability. Every place that used
         * {@code apiKey() != null} as a proxy for "an LLM is available" silently
         * degraded a working keyless-local deployment: {@code DemoAgentRuntime}
         * shadowed every real runtime, the LangChain4j auto-configuration built
         * no model at all, and three samples answered "configure an API key"
         * with a model running and idle on localhost.</p>
         *
         * <p>Fake mode is deliberately excluded — it is the mode that exists
         * precisely to avoid reaching a model.</p>
         *
         * @return {@code true} when a credentialed remote or a local backend is
         *         configured
         */
        public boolean hasReachableModel() {
            if (isFake()) {
                return false;
            }
            return isLocal() || (apiKey != null && !apiKey.isBlank());
        }
    }

    // -- Singleton holder for framework-wide access --

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static volatile LlmSettings instance;

    /**
     * Returns the current LLM settings. Available after {@link #configure} or
     * {@link #fromEnvironment()} has been called.
     *
     * @return the current settings, or {@code null} if not yet configured
     */
    public static LlmSettings get() {
        return instance;
    }

    /**
     * Clears the resolved settings singleton back to the unconfigured state.
     * Package-private — exposed only so tests can exercise the
     * {@code installClient}-before-{@code configure} guard deterministically
     * regardless of test ordering. Not part of the public API.
     */
    static void resetForTesting() {
        LOCK.lock();
        try {
            instance = null;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Configure LLM settings programmatically.
     *
     * @param mode    {@code remote} or {@code local}
     * @param model   model name
     * @param apiKey  API key (may be null for local mode)
     * @param baseUrl explicit base URL (null for auto-detection)
     * @return the resolved settings
     */
    public static LlmSettings configure(String mode, String model, String apiKey, String baseUrl) {
        LOCK.lock();
        try {
            instance = buildSettings(mode, model, apiKey, baseUrl);
            return instance;
        } finally {
            LOCK.unlock();
        }
    }

    /** Per-agent settings overriding the process-wide singleton (registre#39). */
    private static final java.util.concurrent.ConcurrentHashMap<String, LlmSettings>
            AGENT_SETTINGS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Configure settings scoped to one agent — the process-wide singleton is
     * untouched, so a workspace's {@code RUNTIME.md} reconfigures only its
     * own agent, never every agent in the JVM (registre#39). The map is
     * bounded by the number of registered agents.
     *
     * @return the resolved agent-scoped settings
     */
    public static LlmSettings configureForAgent(String agentId, String mode, String model,
                                                String apiKey, String baseUrl) {
        java.util.Objects.requireNonNull(agentId, "agentId");
        var settings = buildSettings(mode, model, apiKey, baseUrl);
        AGENT_SETTINGS.put(agentId, settings);
        logger.info("AI config scoped to agent '{}': mode={}, model={}", agentId, mode, model);
        return settings;
    }

    /**
     * The settings for one agent: its scoped configuration when
     * {@link #configureForAgent} was called for it, else the process-wide
     * {@link #get()} value (which may be {@code null} when unconfigured).
     */
    public static LlmSettings forAgent(String agentId) {
        var scoped = agentScoped(agentId);
        return scoped != null ? scoped : get();
    }

    /** The agent-scoped settings, or {@code null} when the agent has none. */
    public static LlmSettings agentScoped(String agentId) {
        return agentId != null ? AGENT_SETTINGS.get(agentId) : null;
    }

    /** Remove an agent's scoped settings (undeploy / tests). */
    public static void resetAgent(String agentId) {
        if (agentId != null) {
            AGENT_SETTINGS.remove(agentId);
        }
    }

    /** Shared construction for the global and agent-scoped configure paths. */
    private static LlmSettings buildSettings(String mode, String model, String apiKey, String baseUrl) {
        // Tri-state prompt-cache-key control is resolved once here from the
        // sysprop (then env) and stored on the settings so every
        // realization site reads the same value via AiConfig.get(). AUTO
        // (the default) keeps every path byte-identical to the legacy
        // host-heuristic behavior.
        var cacheKeyMode = resolvePromptCacheKeyMode();
        // Generation parameters (temperature/max-tokens/top-p/stop) are
        // resolved once here from sysprops (then env) and stored on the
        // settings so every realization site reads the same opt-in values.
        // GenerationParams.defaults() (all null) keeps the wire byte-identical
        // to the pre-feature behavior when nothing is configured.
        var generation = resolveGenerationParams();

        if ("fake".equalsIgnoreCase(mode)) {
            logger.info("AI config: mode=fake (using FakeLlmClient — no real API calls)");
            return new LlmSettings(new FakeLlmClient(model), model, mode, "fake", null,
                    cacheKeyMode, generation);
        }

        var resolvedUrl = resolveBaseUrl(mode, baseUrl, model);

        logger.info("AI config: mode={}, model={}, endpoint={}", mode, model, resolvedUrl);

        // Normalize the key exactly as the OpenAiCompatibleClient does: a
        // non-blank explicit key is stored verbatim, anything else (null,
        // blank, whitespace) collapses to null. Storing this normalized
        // value as the record's apiKey component keeps settings.apiKey()
        // bit-for-bit identical to the OpenAiCompatibleClient's own
        // apiKey() — the value the ~19 read-side consumers already see —
        // and crucially does NOT inject an ambient-env fallback into the
        // built-in path (that would flip the no-key demo-mode contract).
        // Cross-provider credential resolution is the adapter's job via
        // CredentialResolver when settings.apiKey() is null.
        var resolvedKey = (apiKey != null && !apiKey.isBlank()) ? apiKey : null;

        var builder = OpenAiCompatibleClient.builder().baseUrl(resolvedUrl)
                .generation(generation);
        if (resolvedKey != null) {
            builder.apiKey(resolvedKey);
        } else if (!"local".equalsIgnoreCase(mode)) {
            logger.warn("No API key configured for remote mode. Set LLM_API_KEY or GEMINI_API_KEY environment variable.");
        }

        return new LlmSettings(builder.build(), model, mode, resolvedUrl, resolvedKey,
                cacheKeyMode, generation);
    }

    /**
     * Install a replacement {@link LlmClient} on top of the already-resolved
     * settings, preserving every other component (model, mode, baseUrl,
     * apiKey, promptCacheKeyMode, generation). This is the seam for wrapping
     * or decorating the framework-resolved client — for example, swapping in a
     * {@link org.atmosphere.ai.routing.RoutingLlmClient} that fans a request
     * out to alternate models by content while keeping the resolved client as
     * its fallback.
     *
     * <p>Because {@link AbstractAgentRuntime} resolves the native client
     * lazily on the first {@code execute()} (reading {@link #get()} then), an
     * installation performed at startup — after {@link #configure} but before
     * any request — is what every subsequent dispatch sees. The companion
     * {@link org.atmosphere.ai.AgentRuntimeResolver#reset()} call below drops
     * any runtime that already cached the previous client so the swap is
     * picked up even if a runtime was eagerly configured.</p>
     *
     * @param client the wrapping/decorating client to install (must not be {@code null})
     * @return the new settings now in effect
     * @throws IllegalStateException if {@link #configure} / {@link #fromEnvironment()}
     *                               has not run yet (there is nothing to wrap)
     * @throws NullPointerException  if {@code client} is {@code null}
     */
    public static LlmSettings installClient(LlmClient client) {
        if (client == null) {
            throw new NullPointerException("client must not be null");
        }
        LOCK.lock();
        try {
            var current = instance;
            if (current == null) {
                throw new IllegalStateException(
                        "AiConfig.installClient called before configure()/fromEnvironment() — "
                                + "there is no resolved client to wrap");
            }
            instance = new LlmSettings(client, current.model(), current.mode(),
                    current.baseUrl(), current.apiKey(), current.promptCacheKeyMode(),
                    current.generation());
            // Drop any cached runtime resolution so a runtime that already
            // cached the previous client re-reads the swapped one lazily.
            org.atmosphere.ai.AgentRuntimeResolver.reset();
            logger.info("AI config: installed wrapping client {} (model={}, mode={})",
                    client.getClass().getSimpleName(), current.model(), current.mode());
            return instance;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Resolve the tri-state {@code prompt_cache_key} control from the
     * {@code atmosphere.ai.prompt-cache-key} system property, falling back to
     * the {@code LLM_PROMPT_CACHE_KEY} environment variable and the host
     * configuration (see {@link #knob(String)}), then to
     * {@link PromptCacheKeyMode#AUTO}. Parsing is lenient and never throws (see
     * {@link PromptCacheKeyMode#parse(String)}); the sysprop wins over the env
     * var, mirroring the precedence the per-runtime knobs use elsewhere.
     *
     * @return the resolved mode, never {@code null}
     */
    public static PromptCacheKeyMode resolvePromptCacheKeyMode() {
        return PromptCacheKeyMode.parse(rawKnob(PROMPT_CACHE_KEY_PROPERTY, PROMPT_CACHE_KEY_ENV));
    }

    /**
     * Resolve the tri-state {@code logprobs} control from the
     * {@code atmosphere.ai.logprobs} system property, falling back to the
     * {@code LLM_LOGPROBS} environment variable and the host
     * configuration (see {@link #knob(String)}), then to
     * {@link org.atmosphere.ai.llm.LogprobsMode#AUTO}. Parsing is lenient and
     * never throws (see {@link org.atmosphere.ai.llm.LogprobsMode#parse(String)});
     * the sysprop wins over the env var, mirroring
     * {@link #resolvePromptCacheKeyMode()}.
     *
     * @return the resolved mode, never {@code null}
     */
    public static org.atmosphere.ai.llm.LogprobsMode resolveLogprobsMode() {
        return org.atmosphere.ai.llm.LogprobsMode.parse(rawKnob(LOGPROBS_PROPERTY, LOGPROBS_ENV));
    }

    /**
     * Resolve the tri-state native structured-output control from the
     * {@code atmosphere.ai.native-structured-output} system property, falling
     * back to the {@code LLM_NATIVE_STRUCTURED_OUTPUT} environment variable, then
     * to {@link NativeStructuredOutputMode#AUTO}. Parsing is lenient and never
     * throws (see {@link NativeStructuredOutputMode#parse(String)}); the sysprop
     * wins over the env var, mirroring {@link #resolvePromptCacheKeyMode()}.
     *
     * @return the resolved mode, never {@code null}
     */
    public static NativeStructuredOutputMode resolveNativeStructuredOutputMode() {
        return NativeStructuredOutputMode.parse(rawKnob(NATIVE_STRUCTURED_OUTPUT_PROPERTY, NATIVE_STRUCTURED_OUTPUT_ENV));
    }

    /**
     * Resolve the tri-state planning control from the
     * {@code atmosphere.ai.planning} system property, falling back to the
     * {@code LLM_PLANNING} environment variable and the host
     * configuration (see {@link #knob(String)}), then to
     * {@link org.atmosphere.ai.plan.PlanningMode#AUTO}. Parsing is lenient and
     * never throws; the sysprop wins over the env var, mirroring
     * {@link #resolvePromptCacheKeyMode()}.
     *
     * @return the resolved mode, never {@code null}
     */
    public static org.atmosphere.ai.plan.PlanningMode resolvePlanningMode() {
        return org.atmosphere.ai.plan.PlanningMode.parse(rawKnob(PLANNING_PROPERTY, PLANNING_ENV));
    }

    /**
     * Resolve the tri-state filesystem control from the
     * {@code atmosphere.ai.filesystem} system property, falling back to the
     * {@code LLM_FILESYSTEM} environment variable and the host
     * configuration (see {@link #knob(String)}), then to
     * {@link org.atmosphere.ai.fs.FilesystemMode#AUTO}. Parsing is lenient and
     * never throws; the sysprop wins over the env var, mirroring
     * {@link #resolvePromptCacheKeyMode()}.
     *
     * @return the resolved mode, never {@code null}
     */
    public static org.atmosphere.ai.fs.FilesystemMode resolveFilesystemMode() {
        return org.atmosphere.ai.fs.FilesystemMode.parse(rawKnob(FILESYSTEM_PROPERTY, FILESYSTEM_ENV));
    }

    /**
     * Resolve the opt-in {@link GenerationParams} (temperature, max-tokens,
     * top-p, stop) from system properties, falling back to the matching
     * environment variables and then the host configuration (see
     * {@link #knob(String)}). The sysprop wins over the env var for each knob,
     * mirroring {@link #resolvePromptCacheKeyMode()}. Malformed numeric values
     * are logged and ignored (the component stays unset) — never thrown
     * (Correctness Invariant #4: catch parse errors at the boundary). When no
     * knob is configured this returns {@link GenerationParams#defaults()} so
     * the wire stays byte-identical to the pre-feature behavior.
     *
     * @return the resolved generation parameters, never {@code null}
     */
    public static GenerationParams resolveGenerationParams() {
        var temperature = parseDoubleKnob(TEMPERATURE_PROPERTY, TEMPERATURE_ENV, "temperature");
        var maxTokens = parseIntKnob(MAX_TOKENS_PROPERTY, MAX_TOKENS_ENV, "max-tokens");
        var topP = parseDoubleKnob(TOP_P_PROPERTY, TOP_P_ENV, "top-p");
        var stop = parseStopKnob(STOP_PROPERTY, STOP_ENV);
        return new GenerationParams(temperature, maxTokens, topP, stop);
    }

    /**
     * The embedding model named by the {@code atmosphere.ai.embedding-model}
     * system property, falling back to the {@code LLM_EMBEDDING_MODEL}
     * environment variable and then the host configuration (see
     * {@link #knob(String)}); the sysprop wins, mirroring
     * {@link #resolvePromptCacheKeyMode()}. Read on every call, so a value set
     * after startup is seen by the {@code ServiceLoader}-created
     * {@code BuiltInEmbeddingRuntime}.
     *
     * @return the trimmed model name, or empty when neither is set
     */
    public static java.util.Optional<String> resolveEmbeddingModel() {
        return java.util.Optional.ofNullable(rawKnob(EMBEDDING_MODEL_PROPERTY, EMBEDDING_MODEL_ENV));
    }

    // -- Knobs and host configuration --

    /**
     * One {@code atmosphere.ai.*} knob: its property name and the environment
     * variable that also sets it.
     *
     * @param property the property name, e.g. {@code atmosphere.ai.temperature}
     * @param env      the environment variable, e.g. {@code LLM_TEMPERATURE}
     */
    public record Knob(String property, String env) {
    }

    /**
     * Every knob this class reads. Hosts bridge their configuration by walking
     * this list, so a knob missing from it cannot be set from a host's
     * configuration: add each new knob here.
     */
    private static final java.util.List<Knob> KNOBS = java.util.List.of(
            new Knob(PROMPT_CACHE_KEY_PROPERTY, PROMPT_CACHE_KEY_ENV),
            new Knob(LOGPROBS_PROPERTY, LOGPROBS_ENV),
            new Knob(NATIVE_STRUCTURED_OUTPUT_PROPERTY, NATIVE_STRUCTURED_OUTPUT_ENV),
            new Knob(PLANNING_PROPERTY, PLANNING_ENV),
            new Knob(FILESYSTEM_PROPERTY, FILESYSTEM_ENV),
            new Knob(TEMPERATURE_PROPERTY, TEMPERATURE_ENV),
            new Knob(MAX_TOKENS_PROPERTY, MAX_TOKENS_ENV),
            new Knob(TOP_P_PROPERTY, TOP_P_ENV),
            new Knob(EMBEDDING_MODEL_PROPERTY, EMBEDDING_MODEL_ENV),
            new Knob(STOP_PROPERTY, STOP_ENV),
            new Knob(RUNTIME_PROPERTY, RUNTIME_ENV),
            new Knob(REATTACH_GRACE_PROPERTY, REATTACH_GRACE_ENV));

    private static final java.util.Map<String, Knob> KNOBS_BY_PROPERTY = KNOBS.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(Knob::property, k -> k));

    /**
     * The host configurations installed by {@link #installHostConfig} and not
     * closed yet, oldest first; guarded by {@link #HOST_CONFIG_LOCK}. It holds
     * one entry per host that is running (each host closes its own on stop), so
     * it is bounded by the applications alive in the JVM.
     */
    private static final java.util.ArrayList<HostConfig> HOST_CONFIGS = new java.util.ArrayList<>();
    private static final ReentrantLock HOST_CONFIG_LOCK = new ReentrantLock();
    /** The last entry of {@link #HOST_CONFIGS}, the one read; {@code null} when none. */
    private static volatile HostConfig hostConfig;

    /**
     * The authoritative list of the knobs this class reads, in a stable order.
     *
     * @return every knob, never empty
     */
    public static java.util.List<Knob> knobs() {
        return KNOBS;
    }

    /**
     * The property names of {@link #knobs()}: the keys a host reads from its
     * own configuration and passes to {@link #installHostConfig}.
     *
     * @return the knob property names
     */
    public static java.util.List<String> knobProperties() {
        return KNOBS.stream().map(Knob::property).toList();
    }

    /**
     * The value of one knob, trimmed. Resolution order, first non-blank wins:
     * <ol>
     *   <li>the JVM system property named {@code property} ({@code -D});</li>
     *   <li>the knob's environment variable ({@code LLM_*});</li>
     *   <li>the host configuration installed by {@link #installHostConfig}
     *       (Spring {@code application.yml}/{@code application.properties},
     *       Quarkus {@code application.properties}).</li>
     * </ol>
     * This is the order Spring Boot and MicroProfile Config give their own
     * sources: system properties, then environment, then the application's
     * configuration files.
     *
     * @param property a knob property name, one of {@link #knobProperties()}
     * @return the value, or empty when no source sets it
     * @throws IllegalArgumentException if {@code property} is not a knob
     */
    public static java.util.Optional<String> knob(String property) {
        var knob = KNOBS_BY_PROPERTY.get(property);
        if (knob == null) {
            throw new IllegalArgumentException("Not an AiConfig knob: " + property
                    + " (knobs: " + knobProperties() + ")");
        }
        return java.util.Optional.ofNullable(rawKnob(knob.property(), knob.env()));
    }

    /**
     * Install the host's configuration as the lowest-precedence source of the
     * knobs (see {@link #knob(String)}). {@code lookup} is called once per knob
     * property, now, and the non-blank values are kept: later changes to the
     * host's configuration are not seen. Nothing is written to the JVM system
     * properties.
     *
     * <p>Install before {@link #configure}: the generation parameters and the
     * prompt-cache-key mode are read when the settings are built, so settings
     * built earlier keep the values they were built with (a host calls
     * {@link #reportKnobsNotInEffect()} once it started, to name them). The cached
     * {@link AgentRuntimeResolver} resolution is dropped, so the
     * {@link #RUNTIME_PROPERTY} of the new configuration is honoured.</p>
     *
     * <p>The knobs are JVM-wide, like the system properties and the environment
     * they fall back from: there is no per-application scope. While several
     * installations are open (two applications in one JVM, or Spring's test
     * context cache keeping several contexts alive), the latest one is the one
     * every application reads, whole, and a warning names the earlier ones it
     * shadows. The returned handle removes the installation it belongs to, and
     * only that one: closing the latest gives the previous, still-open
     * installation back, so the host that installed it closes it when it
     * stops.</p>
     *
     * @param lookup the host's configuration, by property name; returns
     *               {@code null} for an unset property
     * @return the handle that uninstalls this host configuration
     */
    public static HostConfig installHostConfig(java.util.function.Function<String, String> lookup) {
        java.util.Objects.requireNonNull(lookup, "lookup");
        var values = new java.util.LinkedHashMap<String, String>();
        for (var knob : KNOBS) {
            var value = lookup.apply(knob.property());
            if (value != null && !value.isBlank()) {
                values.put(knob.property(), value.trim());
            }
        }
        var installed = new HostConfig(java.util.Map.copyOf(values));
        int shadowed;
        HOST_CONFIG_LOCK.lock();
        try {
            shadowed = HOST_CONFIGS.size();
            HOST_CONFIGS.add(installed);
            hostConfig = installed;
        } finally {
            HOST_CONFIG_LOCK.unlock();
        }
        AgentRuntimeResolver.reset();
        if (!values.isEmpty()) {
            logger.info("AI knobs from the host configuration: {}", values.keySet());
        }
        if (shadowed > 0) {
            logger.warn("{} other AI host configuration(s) still installed in this JVM: the knobs are "
                    + "JVM-wide, so every application now reads this one until it is closed", shadowed);
        }
        return installed;
    }

    /**
     * Names, in a warning, the knobs the settings in use ({@link #get()}) do not
     * apply: settings built before the host configuration was installed (in
     * {@code main()}, a static initializer, a startup observer) keep the
     * generation and prompt-cache-key values they were built with, so a value
     * set in the application's configuration would otherwise be silently
     * ignored (Correctness Invariant #5). A host calls it once it started.
     *
     * @return the knob property names not in effect, empty when the settings
     *         apply every knob or none are configured yet
     */
    public static java.util.List<String> reportKnobsNotInEffect() {
        var settings = instance;
        if (settings == null) {
            return java.util.List.of();
        }
        var notInEffect = knobsNotInEffect(settings);
        if (!notInEffect.isEmpty()) {
            logger.warn("The AI settings in use were built before the application's configuration was "
                    + "installed and do not apply {}: build them with AiConfig.configure once the "
                    + "application started, or set these knobs as -D system properties or LLM_* "
                    + "environment variables", notInEffect);
        }
        return notInEffect;
    }

    /**
     * The knobs read when settings are built (prompt-cache-key, temperature,
     * max-tokens, top-p, stop) whose current value differs from the one
     * {@code settings} carries: settings built before the host configuration
     * was installed, which do not apply it.
     *
     * @param settings settings built by {@link #configure}
     * @return the differing knob property names, in {@link #knobs()} order
     */
    static java.util.List<String> knobsNotInEffect(LlmSettings settings) {
        var generation = resolveGenerationParams();
        var baked = settings.generation();
        var differing = new java.util.ArrayList<String>();
        if (resolvePromptCacheKeyMode() != settings.promptCacheKeyMode()) {
            differing.add(PROMPT_CACHE_KEY_PROPERTY);
        }
        if (!java.util.Objects.equals(generation.temperature(), baked.temperature())) {
            differing.add(TEMPERATURE_PROPERTY);
        }
        if (!java.util.Objects.equals(generation.maxTokens(), baked.maxTokens())) {
            differing.add(MAX_TOKENS_PROPERTY);
        }
        if (!java.util.Objects.equals(generation.topP(), baked.topP())) {
            differing.add(TOP_P_PROPERTY);
        }
        if (!java.util.Objects.equals(generation.stop(), baked.stop())) {
            differing.add(STOP_PROPERTY);
        }
        return java.util.List.copyOf(differing);
    }

    /**
     * Remove every installed host configuration. Test hook mirroring
     * {@link AgentRuntimeResolver#reset()}.
     */
    public static void resetHostConfig() {
        HOST_CONFIG_LOCK.lock();
        try {
            HOST_CONFIGS.clear();
            hostConfig = null;
        } finally {
            HOST_CONFIG_LOCK.unlock();
        }
        AgentRuntimeResolver.reset();
    }

    /**
     * Knob values a host installed with {@link #installHostConfig}. Closing it
     * uninstalls it, and the previous installation still open, if any, is read
     * again; closing it again does nothing.
     */
    public static final class HostConfig implements AutoCloseable {

        private final java.util.Map<String, String> values;

        private HostConfig(java.util.Map<String, String> values) {
            this.values = values;
        }

        /**
         * The knob values this installation carries.
         *
         * @return the non-blank values, by knob property name
         */
        public java.util.Map<String, String> values() {
            return values;
        }

        @Override
        public void close() {
            boolean wasRead;
            HOST_CONFIG_LOCK.lock();
            try {
                if (!HOST_CONFIGS.remove(this)) {
                    return;
                }
                wasRead = hostConfig == this;
                hostConfig = HOST_CONFIGS.isEmpty() ? null : HOST_CONFIGS.getLast();
            } finally {
                HOST_CONFIG_LOCK.unlock();
            }
            if (wasRead) {
                AgentRuntimeResolver.reset();
            }
        }
    }

    /**
     * The reattach grace named by the {@code atmosphere.ai.reattach.grace}
     * system property, falling back to the {@code LLM_REATTACH_GRACE}
     * environment variable, then to {@link #DEFAULT_REATTACH_GRACE}; the
     * sysprop wins, mirroring {@link #resolvePromptCacheKeyMode()}. A malformed
     * or negative value is logged and ignored (the default applies), never
     * thrown (Correctness Invariant #4). Read on every call.
     *
     * @return the grace, {@link java.time.Duration#ZERO} when disabled, never {@code null}
     */
    public static java.time.Duration resolveReattachGrace() {
        var raw = rawKnob(REATTACH_GRACE_PROPERTY, REATTACH_GRACE_ENV);
        if (raw == null) {
            return DEFAULT_REATTACH_GRACE;
        }
        var parsed = parseDuration(raw);
        if (parsed == null || parsed.isNegative()) {
            logger.warn("Ignoring malformed reattach grace '{}' (expected e.g. 30s, 500ms, PT30S "
                    + "or a number of seconds); using {}", raw, DEFAULT_REATTACH_GRACE);
            return DEFAULT_REATTACH_GRACE;
        }
        return parsed;
    }

    /** ISO-8601, {@code <n>ms|s|m|h|d}, or a bare number of seconds; null when unparseable. */
    static java.time.Duration parseDuration(String raw) {
        var value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (value.startsWith("p") || value.startsWith("-p")) {
                return java.time.Duration.parse(value.toUpperCase(java.util.Locale.ROOT));
            }
            if (value.endsWith("ms")) {
                return java.time.Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2).trim()));
            }
            var amount = value.isEmpty() ? value : value.substring(0, value.length() - 1).trim();
            return switch (value.isEmpty() ? ' ' : value.charAt(value.length() - 1)) {
                case 's' -> java.time.Duration.ofSeconds(Long.parseLong(amount));
                case 'm' -> java.time.Duration.ofMinutes(Long.parseLong(amount));
                case 'h' -> java.time.Duration.ofHours(Long.parseLong(amount));
                case 'd' -> java.time.Duration.ofDays(Long.parseLong(amount));
                default -> java.time.Duration.ofSeconds(Long.parseLong(value));
            };
        } catch (java.time.format.DateTimeParseException | ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    private static String rawKnob(String property, String env) {
        var raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(env);
        }
        if (raw == null || raw.isBlank()) {
            var host = hostConfig;
            raw = host != null ? host.values.get(property) : null;
        }
        return (raw != null && !raw.isBlank()) ? raw.trim() : null;
    }

    private static Double parseDoubleKnob(String property, String env, String label) {
        var raw = rawKnob(property, env);
        if (raw == null) {
            return null;
        }
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException ex) {
            logger.warn("Ignoring malformed {} value '{}' (expected a number)", label, raw);
            return null;
        }
    }

    private static Integer parseIntKnob(String property, String env, String label) {
        var raw = rawKnob(property, env);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException ex) {
            logger.warn("Ignoring malformed {} value '{}' (expected an integer)", label, raw);
            return null;
        }
    }

    private static java.util.List<String> parseStopKnob(String property, String env) {
        var raw = rawKnob(property, env);
        if (raw == null) {
            return null;
        }
        // Comma-separated; GenerationParams' canonical constructor strips blank
        // entries and collapses an empty result to unset.
        var parts = new java.util.ArrayList<String>();
        for (var token : raw.split(",", -1)) {
            var trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts.isEmpty() ? null : parts;
    }

    /**
     * Configure from environment variables ({@code LLM_MODE}, {@code LLM_MODEL},
     * {@code LLM_API_KEY}/{@code OPENAI_API_KEY}/{@code GEMINI_API_KEY}, {@code LLM_BASE_URL}).
     *
     * @return the resolved settings
     */
    public static LlmSettings fromEnvironment() {
        var mode = env("LLM_MODE", DEFAULT_MODE);
        var model = env("LLM_MODEL", DEFAULT_MODEL);
        var apiKey = env("LLM_API_KEY", env("OPENAI_API_KEY", env("GEMINI_API_KEY", "")));
        var baseUrl = env("LLM_BASE_URL", "");
        return configure(mode, model, apiKey, baseUrl.isBlank() ? null : baseUrl);
    }

    /**
     * Auto-detect the base URL from mode and model name.
     *
     * @param mode        {@code remote} or {@code local}
     * @param explicitUrl an explicit URL override (may be null/blank)
     * @param model       the model name for provider auto-detection
     * @return the resolved base URL
     */
    public static String resolveBaseUrl(String mode, String explicitUrl, String model) {
        if (explicitUrl != null && !explicitUrl.isBlank()) {
            return explicitUrl;
        }

        if ("local".equalsIgnoreCase(mode)) {
            return OLLAMA_ENDPOINT;
        }

        // Auto-detect from model name for remote mode
        if (model != null && (model.startsWith("gpt-") || model.startsWith("o1") || model.startsWith("o3"))) {
            return OPENAI_ENDPOINT;
        }
        // Default to Gemini for remote mode
        return GEMINI_ENDPOINT;
    }

    private static String env(String key, String defaultValue) {
        var val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }
}
