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

import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass

/**
 * Auto-configuration that creates a Koog [PromptExecutor] from environment
 * variables and wires it into the [KoogAgentRuntime] SPI. Uses the simple
 * executor factories from koog-agents (same as Koog's own examples) to
 * bypass the Spring Boot starter's multi-executor complexity.
 */
@AutoConfiguration
@ConditionalOnClass(name = ["ai.koog.prompt.executor.model.PromptExecutor"])
open class AtmosphereKoogAutoConfiguration {

    companion object {
        private val logger = LoggerFactory.getLogger(AtmosphereKoogAutoConfiguration::class.java)
    }

    @org.springframework.context.annotation.Bean
    open fun koogAgentRuntime(
        @Value("\${atmosphere.koog.model:gemini-2.5-flash}") modelName: String,
        @Value("\${atmosphere.koog.api-key:\${LLM_API_KEY:\${GEMINI_API_KEY:\${OPENAI_API_KEY:}}}}") apiKey: String
    ): KoogAgentRuntime {
        val model: LLModel
        val executor: PromptExecutor

        if (apiKey.isBlank()) {
            logger.warn("No API key configured for Koog. Set LLM_API_KEY, GEMINI_API_KEY, or OPENAI_API_KEY.")
            // Return runtime without executor — sample will use DemoResponseProducer
            return KoogAgentRuntime()
        }

        if (modelName.startsWith("gemini")) {
            executor = simpleGoogleAIExecutor(apiKey)
            model = GoogleModels.models.firstOrNull { it.id == modelName }
                ?: GoogleModels.Gemini2_5Flash
            logger.info("Koog runtime configured: model '{}' via GoogleLLMClient", model.id)
        } else {
            executor = simpleOpenAIExecutor(apiKey)
            model = OpenAIModels.models.firstOrNull { it.id == modelName }
                ?: OpenAIModels.Chat.GPT4o
            logger.info("Koog runtime configured: model '{}' via OpenAILLMClient", model.id)
        }

        KoogAgentRuntime.setPromptExecutor(executor)
        KoogAgentRuntime.setDefaultModel(model)
        return KoogAgentRuntime()
    }
}
