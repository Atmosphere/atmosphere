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

import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass

/**
 * Auto-configuration that wires a Koog [PromptExecutor] into [KoogAgentRuntime]
 * from environment variables. Defaults to OpenAI — the only LLM client family
 * published under Koog 1.0's stable stream (Anthropic, Bedrock, Ollama, OpenAI).
 *
 * Gemini / Google support requires Koog's google-client artifact, which JetBrains
 * still ships on the beta track (`1.0.0-beta-preview7` against `prompt-llm-jvm:1.0.0-preview7`)
 * and is intentionally not on Atmosphere's stable classpath. Users who need
 * Gemini through Koog can construct their own [PromptExecutor] and inject it via
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
        @Value("\${atmosphere.koog.model:gpt-4o}") modelName: String,
        @Value("\${atmosphere.koog.api-key:\${LLM_API_KEY:\${OPENAI_API_KEY:}}}") apiKey: String
    ): KoogAgentRuntime {
        if (apiKey.isBlank()) {
            logger.warn("No API key configured for Koog. Set LLM_API_KEY or OPENAI_API_KEY.")
            return KoogAgentRuntime()
        }

        val client = OpenAILLMClient(apiKey)
        val executor: PromptExecutor = MultiLLMPromptExecutor(client)
        val model: LLModel = OpenAIModels.models.firstOrNull { it.id == modelName }
            ?: OpenAIModels.Chat.GPT4o

        logger.info("Koog runtime configured: model '{}' via OpenAILLMClient", model.id)
        KoogAgentRuntime.setPromptExecutor(executor)
        KoogAgentRuntime.setDefaultModel(model)
        return KoogAgentRuntime()
    }
}
