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
package org.atmosphere.samples.springboot.skchat;

import org.atmosphere.ai.AiConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bridges Spring properties into the Atmosphere {@link AiConfig} so the
 * demo-mode fallback in {@link SkChat#onPrompt} can detect a missing key.
 *
 * <p>The Semantic Kernel client beans ({@code OpenAIAsyncClient},
 * {@code ChatCompletionService}) are auto-built by
 * {@code AtmosphereSemanticKernelClientAutoConfiguration} from the
 * {@code llm.*} properties — supplying them manually here is no longer
 * required and would only be useful for advanced cases (e.g. Azure AD
 * auth or a custom SK service).</p>
 */
@Configuration
public class SkConfig {

    @Bean
    public AiConfig.LlmSettings llmSettings(
            @Value("${llm.mode:remote}") String mode,
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.model:gpt-4o-mini}") String model) {

        return AiConfig.configure(mode, model, apiKey, baseUrl.isBlank() ? null : baseUrl);
    }
}
