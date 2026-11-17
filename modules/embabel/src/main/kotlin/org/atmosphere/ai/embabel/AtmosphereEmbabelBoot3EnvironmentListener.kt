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

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.context.ApplicationListener
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.util.ClassUtils

/**
 * Runs the `llm.*` → `embabel.*` mapping of [AtmosphereEmbabelEnvironmentPostProcessor]
 * on Spring Boot 3, which neither has `org.springframework.boot.EnvironmentPostProcessor`
 * nor reads its `spring.factories` key. Registered as an application listener,
 * a type every Spring Boot line loads, it does nothing when that post-processor
 * type is present (Spring Boot 4), where the post-processor runs the mapping.
 *
 * <p>The type is looked up by name, never loaded. The listener runs after
 * Spring Boot's `EnvironmentPostProcessorApplicationListener`
 * (`HIGHEST_PRECEDENCE + 10`), once `application.yml` is loaded and the Spring
 * Boot 3 starter has mapped `atmosphere.ai.*` onto `llm.*`, and before the
 * application context is created.</p>
 */
class AtmosphereEmbabelBoot3EnvironmentListener : ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    // One constructor: Spring Boot instantiates this class from spring.factories,
    // and a Kotlin internal constructor would be a second public one on the JVM.
    private val boot4PostProcessorPresent =
        ClassUtils.isPresent(BOOT4_POST_PROCESSOR, AtmosphereEmbabelBoot3EnvironmentListener::class.java.classLoader)

    override fun onApplicationEvent(event: ApplicationEnvironmentPreparedEvent) {
        onEnvironmentPrepared(event.environment, boot4PostProcessorPresent)
    }

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    internal companion object {
        /** The post-processor type of Spring Boot 4, which does not exist on Spring Boot 3. */
        const val BOOT4_POST_PROCESSOR = "org.springframework.boot.EnvironmentPostProcessor"

        /** Maps unless Spring Boot 4's post-processor type is present, where the post-processor maps. */
        fun onEnvironmentPrepared(environment: ConfigurableEnvironment, boot4PostProcessorPresent: Boolean) {
            if (!boot4PostProcessorPresent) {
                EmbabelLlmBridge.apply(environment)
            }
        }
    }
}
