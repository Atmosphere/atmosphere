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
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass

/**
 * Auto-configuration that wires a Koog [PromptExecutor] into [KoogAgentRuntime]
 * from environment variables, using Koog 1.0's stable `OpenAILLMClient`.
 *
 * Two modes:
 *  - **OpenAI** (no base URL): talks to `api.openai.com`; the model is resolved
 *    from [OpenAIModels].
 *  - **OpenAI-compatible** (`atmosphere.koog.base-url` / `LLM_BASE_URL` set):
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
    }

    @org.springframework.context.annotation.Bean
    open fun koogAgentRuntime(
        @Value("\${atmosphere.koog.model:\${LLM_MODEL:gpt-4o}}") modelName: String,
        @Value("\${atmosphere.koog.api-key:\${LLM_API_KEY:\${OPENAI_API_KEY:}}}") apiKey: String,
        @Value("\${atmosphere.koog.base-url:\${LLM_BASE_URL:}}") baseUrl: String
    ): KoogAgentRuntime {
        if (apiKey.isBlank()) {
            logger.warn("No API key configured for Koog. Set LLM_API_KEY or OPENAI_API_KEY.")
            return KoogAgentRuntime()
        }

        val executor: PromptExecutor
        val model: LLModel

        if (baseUrl.isBlank()) {
            executor = MultiLLMPromptExecutor(OpenAILLMClient(apiKey))
            model = OpenAIModels.models.firstOrNull { it.id == modelName } ?: OpenAIModels.Chat.GPT4o
            logger.info("Koog runtime configured: model '{}' via OpenAILLMClient (api.openai.com)", model.id)
        } else {
            // OpenAI-compatible endpoint (e.g. Gemini). Koog joins baseUrl with the
            // chatCompletionsPath, so the base must include the version segment and
            // the path drops the default "v1/" prefix.
            val settings = OpenAIClientSettings(
                baseUrl = baseUrl.trimEnd('/'),
                chatCompletionsPath = "chat/completions"
            )
            executor = MultiLLMPromptExecutor(OpenAILLMClient(apiKey, settings))
            val template = OpenAIModels.Chat.GPT4o
            model = LLModel(LLMProvider.OpenAI, modelName, template.capabilities,
                template.contextLength, template.maxOutputTokens)
            logger.info("Koog runtime configured: model '{}' via OpenAILLMClient ({})", model.id, baseUrl)
        }

        KoogAgentRuntime.setPromptExecutor(executor)
        KoogAgentRuntime.setDefaultModel(model)
        return KoogAgentRuntime()
    }
}
