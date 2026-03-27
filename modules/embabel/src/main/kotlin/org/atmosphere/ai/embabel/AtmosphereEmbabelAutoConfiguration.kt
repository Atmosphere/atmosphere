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
import org.slf4j.LoggerFactory
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
    }

    @Bean
    @ConditionalOnBean(AgentPlatform::class)
    open fun embabelAgentRuntime(
        platform: AgentPlatform,
        @Value("\${atmosphere.embabel.agent-name:atmosphere-agent}") agentName: String
    ): EmbabelAgentRuntime {
        EmbabelAgentRuntime.setAgentPlatform(platform)
        EmbabelAgentRuntime.setAgentName(agentName)
        logger.info("Embabel runtime configured: default agent name '{}'", agentName)
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
