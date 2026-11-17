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
import org.atmosphere.ai.llm.CleartextApiKeyPolicy
import org.slf4j.LoggerFactory
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * The `llm.*` → `embabel.*` mapping documented on [AtmosphereEmbabelEnvironmentPostProcessor],
 * which runs it on Spring Boot 4, and [AtmosphereEmbabelBoot3EnvironmentListener],
 * which runs it on Spring Boot 3. It names no Spring Boot post-processor type,
 * so either line loads it.
 */
internal object EmbabelLlmBridge {

    const val SOURCE_NAME = "atmosphere-embabel-llm-bridge"

    /** The properties of Embabel's `embabel-agent-starter-openai-custom` (1.5.0). */
    const val CUSTOM_PREFIX = "embabel.agent.platform.models.openai.custom"

    private val logger = LoggerFactory.getLogger(EmbabelLlmBridge::class.java)

    /**
     * Maps the unset `embabel.*` properties from `llm.*`. A second call on the
     * same environment changes nothing: the property source is added once, and
     * a property already set is never overwritten.
     */
    fun apply(env: ConfigurableEnvironment) {
        if (env.propertySources.contains(SOURCE_NAME)) {
            return
        }
        val mapped = mutableMapOf<String, Any>()

        val apiKey = firstNonBlank(
            env.getProperty("llm.api-key"),
            env.getProperty("LLM_API_KEY"),
            env.getProperty("GEMINI_API_KEY"),
            env.getProperty("OPENAI_API_KEY")
        )
        // Embabel's *ModelsConfig classes throw at construction when
        // api-key is null/blank, so set a placeholder when we're missing
        // a real key. The application is expected to short-circuit to
        // demo mode before the placeholder is sent over the wire.
        val resolvedKey = apiKey ?: "demo-placeholder"
        if (env.getProperty("embabel.agent.platform.models.gemini.api-key").isNullOrBlank()) {
            mapped["embabel.agent.platform.models.gemini.api-key"] = resolvedKey
        }
        if (env.getProperty("embabel.agent.platform.models.openai.api-key").isNullOrBlank()) {
            mapped["embabel.agent.platform.models.openai.api-key"] = resolvedKey
        }

        val model = firstNonBlank(
            env.getProperty("llm.model"),
            env.getProperty("LLM_MODEL")
        )
        if (model != null && env.getProperty("embabel.models.default-llm").isNullOrBlank()) {
            mapped["embabel.models.default-llm"] = model
        }

        val local = "local".equals(
            firstNonBlank(env.getProperty("llm.mode"), env.getProperty("LLM_MODE"))?.trim(),
            ignoreCase = true
        )
        // An explicit base URL wins over the mode, as for every other runtime.
        val endpoint = firstNonBlank(env.getProperty("llm.base-url"), env.getProperty("LLM_BASE_URL"))?.trim()
            ?: if (local) AiConfig.OLLAMA_ENDPOINT else null
        if (endpoint != null) {
            if (env.getProperty("$CUSTOM_PREFIX.base-url").isNullOrBlank()) {
                mapped["$CUSTOM_PREFIX.base-url"] = endpoint
            }
            // The custom starter refuses to build without a key; a local backend ignores it.
            if (env.getProperty("$CUSTOM_PREFIX.api-key").isNullOrBlank()) {
                mapped["$CUSTOM_PREFIX.api-key"] = resolvedKey
            }
            if (model != null && env.getProperty("$CUSTOM_PREFIX.models").isNullOrBlank()) {
                mapped["$CUSTOM_PREFIX.models"] = model
            }
            if (mapped.containsKey("$CUSTOM_PREFIX.base-url") || mapped.containsKey("$CUSTOM_PREFIX.api-key")) {
                // Atmosphere picks the endpoint or routes the key to it: a real
                // key goes over plain http only to a loopback host, unless the
                // application opts in. Thrown here, the application fails to
                // start. The placeholder (no llm.api-key) is no secret.
                val effectiveKey = firstNonBlank(env.getProperty("$CUSTOM_PREFIX.api-key"),
                    if (mapped.containsKey("$CUSTOM_PREFIX.api-key")) apiKey else null)
                val effectiveUrl = firstNonBlank(env.getProperty("$CUSTOM_PREFIX.base-url"), endpoint)
                CleartextApiKeyPolicy.requireAllowed(logger, "Embabel (openai-custom)", effectiveUrl, effectiveKey,
                    CleartextApiKeyPolicy.optedIn(env.getProperty(CleartextApiKeyPolicy.ALLOW_PROPERTY)))
            }
        }

        if (mapped.isNotEmpty()) {
            env.propertySources.addLast(MapPropertySource(SOURCE_NAME, mapped))
            logger.info(
                "Bridged llm.* → embabel.* defaults: keys={}, model={}, openai-custom endpoint={}",
                if (apiKey != null) "real" else "placeholder",
                model ?: "(embabel default)",
                endpoint ?: "(none)"
            )
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }
}
