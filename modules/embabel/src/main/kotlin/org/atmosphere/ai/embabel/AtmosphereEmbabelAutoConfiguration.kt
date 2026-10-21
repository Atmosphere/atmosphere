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
package org.atmosphere.ai.embabel

import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.domain.library.HasContent
import com.embabel.common.ai.model.DefaultModelSelectionCriteria
import org.atmosphere.ai.AiConfig
import org.atmosphere.ai.AiEnvironment
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Response wrapper for GOAP — distinct from String so the planner
 * distinguishes the user's input from the agent's output.
 */
data class EmbabelResponse(override val content: String) : HasContent

/**
 * Auto-configuration that bridges the Spring-managed [AgentPlatform] bean
 * to the [EmbabelAgentRuntime] SPI and deploys a default GOAP agent so
 * that `session.stream(message)` works without any Kotlin code in the
 * application.
 */
@AutoConfiguration
@ConditionalOnClass(name = ["com.embabel.agent.core.AgentPlatform"])
open class AtmosphereEmbabelAutoConfiguration {

    companion object {
        private val logger = LoggerFactory.getLogger(AtmosphereEmbabelAutoConfiguration::class.java)

        /**
         * The provider name `embabel-agent-starter-openai-custom` 1.5.0 registers its models
         * under: `OpenAiCustomModelsConfig.createCustomLlm` passes it to `SpringAiLlmService`.
         */
        const val OPENAI_CUSTOM_PROVIDER = "OpenAI-Custom"

        /**
         * The provider name `embabel-agent-starter-ollama` 1.5.0 registers its models under:
         * `OllamaModelsConfig.ollamaLlmOf` passes it to `SpringAiLlmService`.
         */
        const val OLLAMA_PROVIDER = "Ollama"

        /**
         * Log, once at startup, the LLM Embabel's platform resolved as its
         * default, and WARN when `llm.mode=local` is set but that LLM is not
         * served by a local endpoint (Runtime Truth). Embabel's own starters
         * build its LLM clients, not Atmosphere: only the `openai-custom`
         * starter reads the endpoint the bridge maps, and only the `ollama`
         * starter talks to Ollama, so with the Gemini or OpenAI starter alone
         * a "local" run's prompts go to that cloud provider.
         *
         * @param platform      the Embabel platform
         * @param mode          `llm.mode` / `LLM_MODE`
         * @param customBaseUrl the endpoint the `openai-custom` starter uses
         *                      (`OPENAI_CUSTOM_BASE_URL`, else its `base-url` property)
         */
        @JvmStatic
        fun reportDefaultLlm(platform: AgentPlatform, mode: String?, customBaseUrl: String?) {
            val local = "local".equals(mode?.trim(), ignoreCase = true)
            val llm = try {
                platform.platformServices.modelProvider().getLlm(DefaultModelSelectionCriteria)
            } catch (e: Exception) {
                if (local) {
                    logger.warn("LLM_MODE=local is set, but Embabel resolved no default LLM ({}); "
                        + "set LLM_MODEL and add embabel-agent-starter-openai-custom to use the local "
                        + "endpoint", e.toString(), e)
                } else {
                    logger.warn("Embabel resolved no default LLM: {}", e.toString(), e)
                }
                return
            }
            logger.info("Embabel default LLM in use: model={}, provider={}, local={}",
                llm.name, llm.provider, local)
            if (!local) {
                return
            }
            when (llm.provider) {
                OPENAI_CUSTOM_PROVIDER -> AiConfig.warnIfLocalModeOverridden(mode, customBaseUrl)
                OLLAMA_PROVIDER -> {}
                else -> logger.warn("LLM_MODE=local is set, but Embabel's default LLM {} is served by "
                    + "provider {}, not the local endpoint: Embabel builds its clients from its own "
                    + "starters. Add embabel-agent-starter-openai-custom and set LLM_MODEL to a local "
                    + "model to use the local endpoint.", llm.name, llm.provider)
            }
        }
    }

    @Bean
    @ConditionalOnBean(AgentPlatform::class)
    open fun embabelAgentRuntime(
        platform: AgentPlatform,
        @Value("\${atmosphere.embabel.agent-name:atmosphere-agent}") agentName: String,
        @Value("\${llm.mode:\${LLM_MODE:}}") mode: String,
        @Value("\${OPENAI_CUSTOM_BASE_URL:\${embabel.agent.platform.models.openai.custom.base-url:}}")
        customBaseUrl: String,
        environmentProvider: ObjectProvider<AiEnvironment>? = null
    ): EmbabelAgentRuntime {
        // Bound to this context's AiEnvironment when there is one (the
        // Atmosphere starter registers it), so another context in the JVM
        // keeps its own platform; else to the JVM, as before.
        val environment = environmentProvider?.getIfAvailable()
        if (environment != null) {
            EmbabelAgentRuntime.setAgentPlatform(environment, platform)
        } else {
            // No AiEnvironment bean: the process-wide binder is the only one.
            @Suppress("DEPRECATION")
            EmbabelAgentRuntime.setAgentPlatform(platform)
        }
        EmbabelAgentRuntime.setAgentName(agentName)
        logger.info("Embabel runtime configured: default agent name '{}'", agentName)
        reportDefaultLlm(platform, mode, customBaseUrl)
        return EmbabelAgentRuntime()
    }

    /**
     * Default GOAP agent deployed on the [AgentPlatform]. Uses a single
     * [com.embabel.agent.api.dsl.AgentBuilder.promptedTransformer] that
     * forwards the user message to the LLM and returns the response.
     *
     * Applications can override this bean to provide a custom agent
     * with additional actions, tools, or a different persona.
     */
    @Bean
    @ConditionalOnBean(AgentPlatform::class)
    @ConditionalOnMissingBean(Agent::class)
    open fun atmosphereDefaultAgent(
        @Value("\${atmosphere.embabel.agent-name:atmosphere-agent}") agentName: String
    ): Agent {
        logger.info("Creating default Embabel GOAP agent '{}'", agentName)
        return agent(
            name = agentName,
            description = "Default Atmosphere agent powered by Embabel GOAP planning"
        ) {
            promptedTransformer<String, EmbabelResponse>(
                name = "respond",
                description = "Respond to the user's message"
            ) { ctx ->
                ctx.input
            }
            goal(name = "response", description = "User received a response",
                EmbabelResponse::class)
        }
    }
}
