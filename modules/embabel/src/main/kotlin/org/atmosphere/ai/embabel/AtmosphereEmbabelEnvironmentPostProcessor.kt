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

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Bridges Atmosphere's `llm.*` property convention into the `embabel.*`
 * namespace expected by Embabel's auto-configuration. Runs as an
 * [EnvironmentPostProcessor] so the mapped values land in the
 * [ConfigurableEnvironment] **before** Embabel's `@AutoConfiguration`
 * classes are evaluated — Embabel's `modelProvider` bean fails fast at
 * construction if `embabel.models.default-llm` is unknown or if no
 * provider's `api-key` is set, so the bridge has to win the race.
 *
 * <p>Mappings (each only applied when the source `llm.*` value is set):</p>
 * <ul>
 *   <li>{@code llm.api-key} → {@code embabel.agent.platform.models.{gemini,openai}.api-key}</li>
 *   <li>{@code llm.model} → {@code embabel.models.default-llm}</li>
 *   <li>{@code llm.base-url} → {@code embabel.agent.platform.models.openai.custom.base-url};
 *       with none, {@code llm.mode=local} ({@code LLM_MODE=local}) maps the local
 *       Ollama endpoint ({@link AiConfig#OLLAMA_ENDPOINT}, {@code /v1} included:
 *       Embabel 1.5 hands it to the OpenAI SDK verbatim). With an endpoint, the key
 *       goes to {@code ...openai.custom.api-key} and {@code llm.model} to
 *       {@code ...openai.custom.models}, the model list Embabel's
 *       {@code embabel-agent-starter-openai-custom} registers. Only that starter
 *       reads these properties: with the Gemini or OpenAI starter alone, Embabel
 *       does not use the local endpoint, and [AtmosphereEmbabelAutoConfiguration]
 *       says so at startup.</li>
 * </ul>
 *
 * <p>The property source registered here has the lowest precedence among
 * Atmosphere-supplied sources, so users who set the `embabel.*` properties
 * directly (in {@code application.yml}, env vars, or CLI args) always win.</p>
 *
 * <p>When {@code llm.api-key} is unset the bridge writes a placeholder so
 * Embabel's strict bean construction succeeds in demo-mode boots — sample
 * code is expected to short-circuit to a demo response producer before the
 * placeholder ever hits the wire.</p>
 *
 * <p>When the bridge maps the `openai-custom` endpoint or its key, a real key
 * goes over plain {@code http} only to a loopback host: with any other
 * {@code http} host the application fails to start (logged at {@code ERROR})
 * unless it sets {@code atmosphere.ai.allow-cleartext-api-key=true}
 * (`CleartextApiKeyPolicy`). The placeholder is no secret and goes anywhere.</p>
 *
 * <p>Registered under the `org.springframework.boot.EnvironmentPostProcessor`
 * key of `META-INF/spring.factories`, the only one Spring Boot 4.2 reads;
 * Spring Boot 4.0 and 4.1 read it too. Spring Boot 3 has neither that type nor
 * that key: there [AtmosphereEmbabelBoot3EnvironmentListener] runs the same
 * mapping.</p>
 */
class AtmosphereEmbabelEnvironmentPostProcessor : EnvironmentPostProcessor {

    override fun postProcessEnvironment(env: ConfigurableEnvironment, app: SpringApplication) {
        EmbabelLlmBridge.apply(env)
    }

    companion object {
        const val SOURCE_NAME = EmbabelLlmBridge.SOURCE_NAME

        /** The properties of Embabel's `embabel-agent-starter-openai-custom` (1.5.0). */
        const val CUSTOM_PREFIX = EmbabelLlmBridge.CUSTOM_PREFIX
    }
}
