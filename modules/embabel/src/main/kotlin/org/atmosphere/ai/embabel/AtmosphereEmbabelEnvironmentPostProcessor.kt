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

import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

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
 */
class AtmosphereEmbabelEnvironmentPostProcessor : EnvironmentPostProcessor {

    private val logger = LoggerFactory.getLogger(AtmosphereEmbabelEnvironmentPostProcessor::class.java)

    override fun postProcessEnvironment(env: ConfigurableEnvironment, app: SpringApplication) {
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

        if (mapped.isNotEmpty()) {
            env.propertySources.addLast(MapPropertySource(SOURCE_NAME, mapped))
            logger.info(
                "Bridged llm.* → embabel.* defaults: keys={}, model={}",
                if (apiKey != null) "real" else "placeholder",
                model ?: "(embabel default)"
            )
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    companion object {
        const val SOURCE_NAME = "atmosphere-embabel-llm-bridge"
    }
}
