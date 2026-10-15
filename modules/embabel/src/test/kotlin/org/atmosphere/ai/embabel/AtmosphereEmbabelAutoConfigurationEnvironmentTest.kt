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
import org.atmosphere.ai.AiEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The auto-configuration binds the context's [AgentPlatform] to the context's
 * [AiEnvironment], so another context in the JVM keeps its own, and leaves the
 * JVM-wide setter alone.
 */
class AtmosphereEmbabelAutoConfigurationEnvironmentTest {

    private fun staticPlatformField(): java.lang.reflect.Field {
        val field = EmbabelAgentRuntime::class.java.getDeclaredField("agentPlatform")
        field.isAccessible = true
        return field
    }

    private fun agentNameField(): java.lang.reflect.Field {
        val field = EmbabelAgentRuntime::class.java.getDeclaredField("agentName")
        field.isAccessible = true
        return field
    }

    private val agentNameBefore = agentNameField().get(null)

    @AfterEach
    fun restoreStatics() {
        staticPlatformField().set(null, null)
        // The auto-configuration still sets the agent name for the JVM.
        agentNameField().set(null, agentNameBefore)
    }

    @Test
    fun `the context platform is bound to the context application`() {
        val platform = mock(AgentPlatform::class.java)
        val environment = AiEnvironment.create { null }
        try {
            val beanFactory = DefaultListableBeanFactory()
            beanFactory.registerSingleton("aiEnvironment", environment)
            AtmosphereEmbabelAutoConfiguration().embabelAgentRuntime(
                platform, "atmosphere-agent", beanFactory.getBeanProvider(AiEnvironment::class.java))

            assertSame(platform,
                environment.client(EmbabelAgentRuntime::class.java, AgentPlatform::class.java, null),
                "the context's platform must serve the context's application")
            assertSame(platform,
                environment.client(EmbabelAgentRuntime::class.java, AgentPlatform::class.java,
                    mock(AgentPlatform::class.java)),
                "the application's own platform bean is bound: it wins over the JVM-wide setter")
            assertNull(staticPlatformField().get(null),
                "the context's platform must not become every application's")
        } finally {
            environment.close()
        }
    }
}
