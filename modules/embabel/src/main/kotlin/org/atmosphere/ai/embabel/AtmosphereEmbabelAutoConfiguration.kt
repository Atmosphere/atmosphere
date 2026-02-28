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

import com.embabel.agent.core.AgentPlatform
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration that bridges the Spring-managed [AgentPlatform] bean
 * to the [EmbabelAiSupport] SPI so that `@AiEndpoint` methods
 * can stream via `session.stream(message)`.
 */
@AutoConfiguration
@ConditionalOnClass(name = ["com.embabel.agent.core.AgentPlatform"])
open class AtmosphereEmbabelAutoConfiguration {

    @Bean
    @ConditionalOnBean(AgentPlatform::class)
    open fun embabelAiSupportBridge(platform: AgentPlatform): EmbabelAiSupport {
        EmbabelAiSupport.setAgentPlatform(platform)
        return EmbabelAiSupport()
    }
}
