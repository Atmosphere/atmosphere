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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.spi.LlmService
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.NoSuitableModelException
import org.atmosphere.ai.AiEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runtime Truth for `LLM_MODE=local` on Embabel: Embabel's own starters build
 * its LLM clients, so the mode is honoured only when the default LLM Embabel
 * resolved is served by the `openai-custom` starter (which the bridge points at
 * the local endpoint) or the `ollama` starter. With the Gemini or OpenAI
 * starter the prompts go to that cloud provider, and startup must say so.
 */
class AtmosphereEmbabelLocalModeReportTest {

    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(AtmosphereEmbabelAutoConfiguration::class.java) as Logger

    @BeforeEach
    fun captureLog() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun releaseLog() {
        logger.detachAppender(appender)
    }

    @Test
    fun `local mode with a cloud starter warns that the local endpoint is not used`() {
        AtmosphereEmbabelAutoConfiguration.reportDefaultLlm(platform("gemini-2.5-flash", "Google"), "local", "")

        assertEquals(listOf("Embabel default LLM in use: model=gemini-2.5-flash, provider=Google, local=true"),
            messages(Level.INFO))
        val warnings = messages(Level.WARN)
        assertEquals(1, warnings.size, "one WARN expected: $warnings")
        assertTrue(warnings[0].contains("not the local endpoint"), warnings[0])
    }

    /** The runtime bean, which every Embabel application builds, makes the report. */
    @Test
    fun `the runtime bean reports the default llm at startup`() {
        val agentNameField = EmbabelAgentRuntime::class.java.getDeclaredField("agentName")
        agentNameField.isAccessible = true
        val agentNameBefore = agentNameField.get(null)
        val environment = AiEnvironment.create { null }
        try {
            val beanFactory = DefaultListableBeanFactory()
            beanFactory.registerSingleton("aiEnvironment", environment)
            AtmosphereEmbabelAutoConfiguration().embabelAgentRuntime(
                platform("gemini-2.5-flash", "Google"), "atmosphere-agent", "local", "",
                beanFactory.getBeanProvider(AiEnvironment::class.java))

            assertTrue(messages(Level.WARN).any { it.contains("not the local endpoint") },
                "startup must say a local run is not served locally: ${messages(Level.WARN)}")
        } finally {
            environment.close()
            agentNameField.set(null, agentNameBefore)
        }
    }

    @Test
    fun `local mode served by the openai-custom starter on this machine does not warn`() {
        AtmosphereEmbabelAutoConfiguration.reportDefaultLlm(
            platform("qwen2.5:7b", AtmosphereEmbabelAutoConfiguration.OPENAI_CUSTOM_PROVIDER),
            "LOCAL", "http://localhost:11434/v1")

        assertEquals(listOf("Embabel default LLM in use: model=qwen2.5:7b, provider=OpenAI-Custom, local=true"),
            messages(Level.INFO))
        assertEquals(emptyList(), messages(Level.WARN))
    }

    @Test
    fun `local mode served by the ollama starter does not warn`() {
        AtmosphereEmbabelAutoConfiguration.reportDefaultLlm(
            platform("llama3.2", AtmosphereEmbabelAutoConfiguration.OLLAMA_PROVIDER), "local", "")

        assertEquals(emptyList(), messages(Level.WARN))
    }

    @Test
    fun `remote mode with a cloud starter only reports the llm`() {
        AtmosphereEmbabelAutoConfiguration.reportDefaultLlm(platform("gpt-4.1-mini", "OpenAI"), "", "")

        assertEquals(listOf("Embabel default LLM in use: model=gpt-4.1-mini, provider=OpenAI, local=false"),
            messages(Level.INFO))
        assertEquals(emptyList(), messages(Level.WARN))
    }

    @Test
    fun `local mode with no default llm warns`() {
        val modelProvider = mock(ModelProvider::class.java)
        doThrow(NoSuitableModelException::class.java).`when`(modelProvider).getLlm(any())

        AtmosphereEmbabelAutoConfiguration.reportDefaultLlm(platform(modelProvider), "local", "")

        val warnings = messages(Level.WARN)
        assertEquals(1, warnings.size, "one WARN expected: $warnings")
        assertTrue(warnings[0].startsWith("LLM_MODE=local is set, but Embabel resolved no default LLM"), warnings[0])
    }

    private fun platform(name: String, provider: String): AgentPlatform {
        val llm = mock(LlmService::class.java)
        doReturn(name).`when`(llm).name
        doReturn(provider).`when`(llm).provider
        val modelProvider = mock(ModelProvider::class.java)
        doReturn(llm).`when`(modelProvider).getLlm(any())
        return platform(modelProvider)
    }

    private fun platform(modelProvider: ModelProvider): AgentPlatform {
        val services = mock(PlatformServices::class.java)
        doReturn(modelProvider).`when`(services).modelProvider()
        val platform = mock(AgentPlatform::class.java)
        doReturn(services).`when`(platform).platformServices
        return platform
    }

    private fun messages(level: Level): List<String> =
        appender.list.filter { it.level == level }.map { it.formattedMessage }
}
