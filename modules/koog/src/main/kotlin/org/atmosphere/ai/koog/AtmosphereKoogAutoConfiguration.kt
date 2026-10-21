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
package org.atmosphere.ai.koog

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import org.atmosphere.ai.AiConfig
import org.atmosphere.ai.AiEnvironment
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.core.env.Environment
import org.springframework.core.env.PropertyResolver

/**
 * Auto-configuration that wires a Koog [PromptExecutor] into [KoogAgentRuntime]
 * from environment variables, using Koog 1.0's stable `OpenAILLMClient`.
 *
 * Two modes:
 *  - **OpenAI** (no base URL, OpenAI-shaped model id): talks to `api.openai.com`;
 *    the model is resolved from [OpenAIModels].
 *  - **OpenAI-compatible** (`atmosphere.koog.base-url` / `LLM_BASE_URL` set, or
 *    derived by [AiConfig.resolveBaseUrl] from mode/model when neither is):
 *    points the same client at any OpenAI-compatible endpoint. This is the
 *    supported path for Gemini — Koog 1.0's native Google client ships only on
 *    the beta track (`1.0.0-beta-preview7`, incompatible with stable `1.0.0`) —
 *    via Google's OpenAI surface
 *    (`https://generativelanguage.googleapis.com/v1beta/openai`). The requested
 *    `model` id is used verbatim with GPT-4o's capability profile, so any
 *    OpenAI-compatible model name (e.g. `gemini-2.5-flash`) works.
 *
 * Users needing a non-OpenAI-compatible Koog client (Anthropic, Bedrock,
 * Ollama) build their own [PromptExecutor] and inject it via
 * [KoogAgentRuntime.setPromptExecutor].
 */
@AutoConfiguration
@ConditionalOnClass(name = ["ai.koog.prompt.executor.model.PromptExecutor"])
open class AtmosphereKoogAutoConfiguration {

    companion object {
        private val logger = LoggerFactory.getLogger(AtmosphereKoogAutoConfiguration::class.java)

        /**
         * The endpoint the executor will talk to, or `""` to use Koog's own
         * OpenAI client defaults.
         *
         * A configured [baseUrl] always wins — that is what lets `LLM_BASE_URL`
         * point at vLLM or LM Studio. With nothing configured, resolution is
         * delegated to [AiConfig.resolveBaseUrl], the same resolver the built-in
         * runtime uses, so mode and model steer the endpoint identically across
         * runtimes: `local` yields loopback Ollama and a remote non-OpenAI model
         * id (`gemini-*`, ...) yields Gemini's OpenAI-compatible surface.
         *
         * Deriving Gemini here rather than hard-coding it in a sample's YAML is
         * load-bearing. A configured URL out-ranks mode, so a sample that
         * defaults `atmosphere.koog.base-url` to the Gemini URL makes
         * `LLM_MODE=local` unreachable and a keyless local run silently dials
         * Google. With the default left blank, both quickstarts work.
         *
         * An OpenAI endpoint collapses back to `""` so the native branch keeps
         * Koog's own client settings and its [OpenAIModels] catalogue.
         */
        internal fun resolveEndpoint(baseUrl: String, mode: String, model: String): String {
            if (baseUrl.isNotBlank()) {
                AiConfig.warnIfLocalModeOverridden(mode, baseUrl)
                return baseUrl
            }
            val derived = AiConfig.resolveBaseUrl(mode, null, model)
            return if (derived == AiConfig.OPENAI_ENDPOINT) "" else derived
        }

        /**
         * The model, key, base URL and mode this runtime is built with: for each,
         * the first non-blank of `atmosphere.koog.*`, `llm.*` (Spring maps the
         * `LLM_*` environment variable onto it), the `LLM_*` property itself, and
         * for the key `OPENAI_API_KEY`. Read as first non-blank rather than as a
         * placeholder chain, whose default applies only when a key is absent: a
         * blank `llm.api-key: ${LLM_API_KEY:}` would otherwise hide
         * `OPENAI_API_KEY`. With a Spring Boot starter, `atmosphere.ai.*` reaches
         * `llm.*` when that is unset (`AtmosphereAiLlmPropertyBridge`).
         */
        internal fun resolveSettings(properties: PropertyResolver): KoogSettings {
            fun first(vararg keys: String): String =
                keys.firstNotNullOfOrNull { key -> properties.getProperty(key)?.takeIf { it.isNotBlank() } } ?: ""
            return KoogSettings(
                model = first("atmosphere.koog.model", "llm.model", "LLM_MODEL").ifEmpty { "gpt-4o" },
                apiKey = first("atmosphere.koog.api-key", "llm.api-key", "LLM_API_KEY", "OPENAI_API_KEY"),
                baseUrl = first("atmosphere.koog.base-url", "llm.base-url", "LLM_BASE_URL"),
                mode = first("llm.mode", "LLM_MODE"))
        }
    }

    /** The settings [resolveSettings] reads; blank when unset, except the model. */
    internal data class KoogSettings(val model: String, val apiKey: String, val baseUrl: String, val mode: String)

    @org.springframework.context.annotation.Bean
    open fun koogAgentRuntime(
        environment: Environment,
        environmentProvider: ObjectProvider<AiEnvironment>?
    ): KoogAgentRuntime {
        val settings = resolveSettings(environment)
        return koogAgentRuntime(settings.model, settings.apiKey, settings.baseUrl, settings.mode, environmentProvider)
    }

    /** Builds the runtime from resolved settings; the bean above reads them from the environment. */
    open fun koogAgentRuntime(
        modelName: String,
        apiKey: String,
        baseUrl: String,
        mode: String,
        environmentProvider: ObjectProvider<AiEnvironment>? = null
    ): KoogAgentRuntime {
        // A locally served backend (Ollama, vLLM, LM Studio) needs no credential,
        // so a blank key says nothing about whether a model is reachable. Keying
        // only on the key returned a runtime with no PromptExecutor, and the
        // failure surfaced far away — the first agent turn died with
        // "PromptExecutor not configured" long after startup logged only a warning.
        val local = mode.trim().equals("local", ignoreCase = true)
        if (apiKey.isBlank() && !local) {
            logger.warn("No API key configured for Koog and llm.mode is not 'local'. "
                + "Set LLM_API_KEY / OPENAI_API_KEY, or LLM_MODE=local for a local backend.")
            return KoogAgentRuntime()
        }

        // The local server ignores the credential but the client still requires a
        // non-blank one, so send a placeholder rather than failing to construct.
        val resolvedApiKey = if (apiKey.isBlank()) "not-needed-for-local" else apiKey

        val resolvedBaseUrl = resolveEndpoint(baseUrl, mode, modelName)

        val executor: PromptExecutor
        val model: LLModel

        if (resolvedBaseUrl.isBlank()) {
            executor = MultiLLMPromptExecutor(AtmosphereOpenAILLMClient(resolvedApiKey))
            model = OpenAIModels.models.firstOrNull { it.id == modelName } ?: OpenAIModels.Chat.GPT4o
            logger.info("Koog runtime configured: model '{}' via OpenAILLMClient (api.openai.com)", model.id)
        } else {
            // OpenAI-compatible endpoint (e.g. Gemini). Koog joins baseUrl with the
            // chatCompletionsPath, so the base must include the version segment and
            // the path drops the default "v1/" prefix.
            val settings = OpenAIClientSettings(
                baseUrl = resolvedBaseUrl.trimEnd('/'),
                chatCompletionsPath = "chat/completions"
            )
            executor = MultiLLMPromptExecutor(AtmosphereOpenAILLMClient(resolvedApiKey, settings))
            val template = OpenAIModels.Chat.GPT4o
            model = LLModel(LLMProvider.OpenAI, modelName, template.capabilities,
                template.contextLength, template.maxOutputTokens)
            logger.info("Koog runtime configured: model '{}' via OpenAILLMClient ({})", model.id, resolvedBaseUrl)
        }

        // Offered to this context's AiEnvironment when there is one (the
        // Atmosphere starter registers it), so another context in the JVM
        // keeps its own executor and model. Offered, not bound: this executor
        // is built on every start, and an executor the application sets itself
        // (KoogAgentRuntime.setPromptExecutor, the path for a non-OpenAI
        // client) must win over it. Without an environment, set for the JVM,
        // as before.
        val environment = environmentProvider?.getIfAvailable()
        if (environment != null) {
            KoogAgentRuntime.offerPromptExecutor(environment, executor)
            KoogAgentRuntime.offerDefaultModel(environment, model)
        } else {
            // No AiEnvironment bean: the process-wide binders are the only ones.
            @Suppress("DEPRECATION")
            KoogAgentRuntime.setPromptExecutor(executor)
            @Suppress("DEPRECATION")
            KoogAgentRuntime.setDefaultModel(model)
        }
        return KoogAgentRuntime()
    }
}
