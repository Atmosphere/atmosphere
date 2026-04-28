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
package org.atmosphere.samples.springboot.embabelchat

import org.atmosphere.ai.AiConfig
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Bridges Spring properties into the Atmosphere [AiConfig] so the demo-mode
 * fallback can detect whether an API key is configured.
 */
@Configuration
open class LlmConfig {

    @Bean
    open fun llmSettings(
        @Value("\${llm.mode:remote}") mode: String,
        @Value("\${llm.base-url:}") baseUrl: String,
        @Value("\${llm.api-key:}") apiKey: String,
        @Value("\${llm.model:gemini-2.5-flash}") model: String,
    ): AiConfig.LlmSettings =
        AiConfig.configure(mode, model, apiKey, baseUrl.ifBlank { null })
}
