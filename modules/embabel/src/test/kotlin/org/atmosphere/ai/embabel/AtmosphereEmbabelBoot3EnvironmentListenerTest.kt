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

import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The Spring Boot 3 path of the `llm.*` → `embabel.*` bridge: an application
 * listener that maps only where Spring Boot 4's post-processor type is absent,
 * and a mapping that runs at most once per environment when both paths fire.
 */
class AtmosphereEmbabelBoot3EnvironmentListenerTest {

    @Configuration(proxyBeanMethods = false)
    open class NoBeans

    private val openAiKey = "embabel.agent.platform.models.openai.api-key"
    private val geminiKey = "embabel.agent.platform.models.gemini.api-key"

    @Test
    fun `the listener maps only without spring boot 4's post-processor type`() {
        val withType = environment(mapOf("llm.api-key" to "sk-test"))
        AtmosphereEmbabelBoot3EnvironmentListener.onEnvironmentPrepared(withType, true)
        assertFalse(withType.propertySources.contains(EmbabelLlmBridge.SOURCE_NAME),
            "with Spring Boot 4's post-processor type present the post-processor maps, not the listener")

        val withoutType = environment(mapOf("llm.api-key" to "sk-test"))
        AtmosphereEmbabelBoot3EnvironmentListener.onEnvironmentPrepared(withoutType, false)
        assertEquals("sk-test", withoutType.getProperty(openAiKey))
    }

    @Test
    fun `the listener detects spring boot 4's post-processor type on this classpath`() {
        // This module is built on Spring Boot 4: the listener Spring Boot creates must stay inert.
        val env = environment(mapOf("llm.api-key" to "sk-test"))
        AtmosphereEmbabelBoot3EnvironmentListener().onApplicationEvent(prepared(env))
        assertNull(env.getProperty(openAiKey))
    }

    @Test
    fun `both paths on one application map once and keep what the user set`() {
        val app = SpringApplication(NoBeans::class.java)
        app.setWebApplicationType(WebApplicationType.NONE)
        val start = StandardEnvironment()
        start.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        app.setEnvironment(start)
        // The post-processor runs from spring.factories; this listener runs as on Spring Boot 3.
        app.addListeners(ApplicationListener<ApplicationEnvironmentPreparedEvent> {
            AtmosphereEmbabelBoot3EnvironmentListener.onEnvironmentPrepared(it.environment, false)
        })
        app.run("--llm.api-key=sk-boot-test", "--llm.model=gpt-4o-mini",
            "--embabel.models.default-llm=user-model").use { context ->
            val env = context.environment
            // The second run adds nothing: the source holds only what was unset.
            assertEquals(mapOf<String, Any>(openAiKey to "sk-boot-test", geminiKey to "sk-boot-test"),
                (env.propertySources.get(EmbabelLlmBridge.SOURCE_NAME) as MapPropertySource).source)
            assertEquals("user-model", env.getProperty("embabel.models.default-llm"))
        }
    }

    @Test
    fun `a second run leaves the first run's property source alone`() {
        // The first run's source is found by name: the second neither replaces it
        // nor maps llm.model, still unmapped here.
        val env = environment(mapOf("llm.api-key" to "sk-test", "llm.model" to "gpt-4o-mini"))
        val first = MapPropertySource(EmbabelLlmBridge.SOURCE_NAME, mutableMapOf<String, Any>(openAiKey to "sk-test"))
        env.propertySources.addLast(first)
        EmbabelLlmBridge.apply(env)
        assertSame(first, env.propertySources.get(EmbabelLlmBridge.SOURCE_NAME))
        assertNull(env.getProperty("embabel.models.default-llm"))
    }

    private fun environment(properties: Map<String, Any>): StandardEnvironment {
        val env = StandardEnvironment()
        env.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        env.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        env.propertySources.addLast(MapPropertySource("test", HashMap(properties)))
        return env
    }

    private fun prepared(env: StandardEnvironment): ApplicationEnvironmentPreparedEvent =
        mock { on { environment } doReturn env }
}
