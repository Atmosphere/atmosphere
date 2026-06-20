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
                              String apiKey, PromptCacheKeyMode promptCacheKeyMode) {

        /**
         * Canonical-constructor null guard: a {@code null}
         * {@code promptCacheKeyMode} collapses to {@link PromptCacheKeyMode#AUTO}
         * so the default path stays byte-identical to the pre-flag wire output.
         */
        public LlmSettings {
            promptCacheKeyMode = promptCacheKeyMode == null ? PromptCacheKeyMode.AUTO : promptCacheKeyMode;
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
                    PromptCacheKeyMode.AUTO);
        }

        /**
         * Backward-compatible 5-arg constructor preserving the post-B1 shape
         * ({@code apiKey} supplied explicitly) while defaulting the new
         * {@code promptCacheKeyMode} component to {@link PromptCacheKeyMode#AUTO}.
         *
         * @param client  the resolved LLM client
         * @param model   the model name
         * @param mode    the mode ({@code remote}, {@code local}, or {@code fake})
         * @param baseUrl the resolved base URL
         * @param apiKey  the framework-resolved provider API key (may be {@code null})
         */
        public LlmSettings(LlmClient client, String model, String mode, String baseUrl, String apiKey) {
            this(client, model, mode, baseUrl, apiKey, PromptCacheKeyMode.AUTO);
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
            // Tri-state prompt-cache-key control is resolved once here from the
            // sysprop (then env) and stored on the settings so every
            // realization site reads the same value via AiConfig.get(). AUTO
            // (the default) keeps every path byte-identical to the legacy
            // host-heuristic behavior.
            var cacheKeyMode = resolvePromptCacheKeyMode();

            if ("fake".equalsIgnoreCase(mode)) {
                logger.info("AI config: mode=fake (using FakeLlmClient — no real API calls)");
                instance = new LlmSettings(new FakeLlmClient(model), model, mode, "fake", null, cacheKeyMode);
                return instance;
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

            var builder = OpenAiCompatibleClient.builder().baseUrl(resolvedUrl);
            if (resolvedKey != null) {
                builder.apiKey(resolvedKey);
            } else if (!"local".equalsIgnoreCase(mode)) {
                logger.warn("No API key configured for remote mode. Set LLM_API_KEY or GEMINI_API_KEY environment variable.");
            }

            instance = new LlmSettings(builder.build(), model, mode, resolvedUrl, resolvedKey, cacheKeyMode);
            return instance;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Resolve the tri-state {@code prompt_cache_key} control from the
     * {@code atmosphere.ai.prompt-cache-key} system property, falling back to
     * the {@code LLM_PROMPT_CACHE_KEY} environment variable, then to
     * {@link PromptCacheKeyMode#AUTO}. Parsing is lenient and never throws (see
     * {@link PromptCacheKeyMode#parse(String)}); the sysprop wins over the env
     * var, mirroring the precedence the per-runtime knobs use elsewhere.
     *
     * @return the resolved mode, never {@code null}
     */
    public static PromptCacheKeyMode resolvePromptCacheKeyMode() {
        var raw = System.getProperty(PROMPT_CACHE_KEY_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(PROMPT_CACHE_KEY_ENV);
        }
        return PromptCacheKeyMode.parse(raw);
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
