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

import org.atmosphere.ai.AiConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `LLM_MODE=local` maps the local Ollama endpoint into the properties of
 * Embabel's `embabel-agent-starter-openai-custom`, the Embabel starter that
 * builds an LLM for an arbitrary OpenAI-compatible endpoint and model name.
 * Before, the bridge mapped only the key and the model, so no Embabel starter
 * was ever pointed at the local endpoint. An explicit base URL wins over the
 * mode, and an explicit `embabel.*` property wins over both.
 *
 * The process environment is removed from every environment built here, so an
 * `LLM_*` variable exported by the shell running the build cannot change the
 * outcome.
 */
class AtmosphereEmbabelEnvironmentPostProcessorTest {

    private val prefix = AtmosphereEmbabelEnvironmentPostProcessor.CUSTOM_PREFIX

    @Test
    fun `local mode maps the local endpoint and the model into the openai-custom starter`() {
        val env = bridged(mapOf("LLM_MODE" to "local", "LLM_MODEL" to "qwen2.5:7b"))

        assertEquals(AiConfig.OLLAMA_ENDPOINT, env.getProperty("$prefix.base-url"),
            "Embabel 1.5 hands the base URL to the OpenAI SDK verbatim: /v1 stays")
        assertEquals("http://localhost:11434/v1", env.getProperty("$prefix.base-url"))
        assertEquals("qwen2.5:7b", env.getProperty("$prefix.models"))
        assertEquals("qwen2.5:7b", env.getProperty("embabel.models.default-llm"))
        assertEquals("demo-placeholder", env.getProperty("$prefix.api-key"),
            "the custom starter refuses to build without a key; a local backend ignores it")
    }

    @Test
    fun `an explicit llm base url wins over local mode`() {
        val env = bridged(mapOf("llm.mode" to "local", "LLM_BASE_URL" to "http://127.0.0.1:8000/v1",
            "LLM_API_KEY" to "k"))

        assertEquals("http://127.0.0.1:8000/v1", env.getProperty("$prefix.base-url"))
        assertEquals("k", env.getProperty("$prefix.api-key"))
    }

    @Test
    fun `an explicit openai-custom base url is never rewritten`() {
        val env = bridged(mapOf("LLM_MODE" to "local", "$prefix.base-url" to "https://gateway.example/v1",
            "$prefix.models" to "mine"))

        assertEquals("https://gateway.example/v1", env.getProperty("$prefix.base-url"))
        assertEquals("mine", env.getProperty("$prefix.models"))
    }

    @Test
    fun `remote mode without a base url maps no endpoint`() {
        val env = bridged(mapOf("LLM_MODEL" to "gemini-2.5-flash"))

        assertNull(env.getProperty("$prefix.base-url"),
            "remote mode with no base URL must not invent an endpoint")
        assertNull(env.getProperty("$prefix.models"))
    }

    private fun bridged(properties: Map<String, Any>): ConfigurableEnvironment {
        val env = StandardEnvironment()
        env.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        env.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        env.propertySources.addFirst(MapPropertySource("test", properties))
        AtmosphereEmbabelEnvironmentPostProcessor().postProcessEnvironment(env, SpringApplication())
        return env
    }
}
