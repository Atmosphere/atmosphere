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

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass

/**
 * Auto-configuration that bridges the Spring-managed [PromptExecutor] bean
 * (from `koog-spring-boot-starter`) to the [KoogAgentRuntime] SPI so that
 * `session.stream(message)` works transparently when Koog is on the classpath.
 */
@AutoConfiguration
@ConditionalOnClass(name = ["ai.koog.prompt.executor.model.PromptExecutor"])
open class AtmosphereKoogAutoConfiguration {

    companion object {
        private val logger = LoggerFactory.getLogger(AtmosphereKoogAutoConfiguration::class.java)
    }

    @org.springframework.context.annotation.Bean
    @ConditionalOnBean(PromptExecutor::class)
    open fun koogAgentRuntime(
        executor: PromptExecutor,
        @Value("\${atmosphere.koog.model:gpt-4o}") modelName: String
    ): KoogAgentRuntime {
        KoogAgentRuntime.setPromptExecutor(executor)
        KoogAgentRuntime.setDefaultModel(LLModel(LLMProvider.OpenAI, modelName))
        logger.info("Koog runtime configured: default model '{}'", modelName)
        return KoogAgentRuntime()
    }
}
