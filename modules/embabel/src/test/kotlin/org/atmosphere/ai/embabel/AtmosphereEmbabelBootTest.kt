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
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boots a real [SpringApplication] and asserts the `llm.*` → `embabel.*`
 * bridge ran, whichever key and type the running Spring Boot line loads it
 * through: Spring Boot 4.2 reads only `org.springframework.boot.EnvironmentPostProcessor`,
 * and on Spring Boot 3 the bridge runs as an application listener. No
 * post-processor type is named here, so the source compiles on every line.
 */
class AtmosphereEmbabelBootTest {

    @Configuration(proxyBeanMethods = false)
    open class NoBeans

    @Test
    fun `spring boot runs the bridge and llm settings reach embabel`() {
        val app = SpringApplication(NoBeans::class.java)
        app.setWebApplicationType(WebApplicationType.NONE)
        // The shell running the build may export LLM_*; it must not decide the outcome.
        val environment = StandardEnvironment()
        environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        app.setEnvironment(environment)
        app.run("--llm.api-key=sk-boot-test", "--llm.model=gpt-4o-mini").use { context ->
            val env = context.environment
            assertTrue(env.propertySources.contains("atmosphere-embabel-llm-bridge"),
                "the atmosphere-embabel-llm-bridge property source is missing")
            assertEquals("sk-boot-test", env.getProperty("embabel.agent.platform.models.openai.api-key"))
            assertEquals("gpt-4o-mini", env.getProperty("embabel.models.default-llm"))
        }
    }
}
