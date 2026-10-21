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
package org.atmosphere.ai.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationCategory
import ai.koog.prompt.dsl.ModerationCategoryResult
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.atmosphere.ai.AiConfig
import org.atmosphere.ai.AiEnvironment
import org.atmosphere.ai.llm.CleartextApiKeyPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies [AtmosphereKoogAutoConfiguration] wires the Koog [PromptExecutor]
 * and default model correctly across its two modes, and degrades safely when
 * no key is present. Reads the private companion fields by reflection (same
 * pattern as [KoogRuntimeContractTest]) since they are intentionally not part
 * of the public surface.
 */
class AtmosphereKoogAutoConfigurationTest {

    private fun executor(): PromptExecutor? {
        val f = KoogAgentRuntime::class.java.getDeclaredField("promptExecutor")
        f.isAccessible = true
        return f.get(null) as PromptExecutor?
    }

    private fun defaultModel(): LLModel? {
        val f = KoogAgentRuntime::class.java.getDeclaredField("defaultModel")
        f.isAccessible = true
        return f.get(null) as LLModel?
    }

    private fun clearExecutor() {
        val f = KoogAgentRuntime::class.java.getDeclaredField("promptExecutor")
        f.isAccessible = true
        f.set(null, null)
    }

    private fun clearDefaultModel() {
        val f = KoogAgentRuntime::class.java.getDeclaredField("defaultModel")
        f.isAccessible = true
        f.set(null, null)
    }

    private fun providerOf(environment: AiEnvironment): ObjectProvider<AiEnvironment> {
        val beanFactory = DefaultListableBeanFactory()
        beanFactory.registerSingleton("aiEnvironment", environment)
        return beanFactory.getBeanProvider(AiEnvironment::class.java)
    }

    /** The Koog instance [environment] resolves, as an endpoint of that application gets it. */
    private fun koogOf(environment: AiEnvironment): KoogAgentRuntime =
        environment.runtimes().resolveAll().first { it.name() == "koog" } as KoogAgentRuntime

    @AfterEach
    fun reset() {
        clearExecutor()
        clearDefaultModel()
    }

    @Test
    fun `blank api key with no local backend leaves the executor unconfigured`() {
        clearExecutor()
        AtmosphereKoogAutoConfiguration().koogAgentRuntime("gpt-4o", "", "", "")
        assertNull(executor(),
            "with no credential and no local backend there is nothing to talk to")
    }

    @Test
    fun `openai mode configures an executor and resolves a known model`() {
        AtmosphereKoogAutoConfiguration().koogAgentRuntime("gpt-4o", "sk-test", "", "")
        assertNotNull(executor(), "OpenAI mode must install a PromptExecutor")
        assertEquals("gpt-4o", defaultModel()?.id)
    }

    @Test
    fun `openai-compatible mode keeps the requested model id verbatim`() {
        // Regression guard: in base-url mode the model id must NOT be coerced to
        // an OpenAIModels entry (e.g. gpt-4o) — Gemini ids must survive verbatim
        // so the OpenAI-compatible endpoint receives the right model.
        AtmosphereKoogAutoConfiguration().koogAgentRuntime(
            "gemini-2.5-flash",
            "test-key",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            ""
        )
        assertNotNull(executor(), "OpenAI-compatible mode must install a PromptExecutor")
        assertEquals("gemini-2.5-flash", defaultModel()?.id,
            "base-url mode must use the requested model id verbatim, not a coerced OpenAI id")
    }

    @Test
    fun `local mode configures an executor without any credential`() {
        // The regression. Keying only on the API key returned a runtime with no
        // PromptExecutor, so a keyless-local deployment started clean and then
        // died on the first agent turn with "PromptExecutor not configured" —
        // far from the startup warning that explained it.
        clearExecutor()
        AtmosphereKoogAutoConfiguration().koogAgentRuntime("qwen2.5:3b", "", "", "local")
        assertNotNull(executor(),
            "a local backend needs no credential, so a blank key must not suppress the executor")
        assertEquals("qwen2.5:3b", defaultModel()?.id)
    }

    @Test
    fun `local mode keeps an explicit base url`() {
        // LLM_BASE_URL must win over the local default, so a vLLM or LM Studio
        // endpoint on another host is still honoured in local mode.
        clearExecutor()
        AtmosphereKoogAutoConfiguration().koogAgentRuntime(
            "qwen2.5:3b", "", "http://gpu-box:8000/v1", "local")
        assertNotNull(executor())
        assertEquals("qwen2.5:3b", defaultModel()?.id)
    }

    @Test
    fun `local mode is case insensitive`() {
        clearExecutor()
        AtmosphereKoogAutoConfiguration().koogAgentRuntime("qwen2.5:3b", "", "", "LOCAL")
        assertNotNull(executor())
    }

    @Test
    fun `local mode is read trimmed, as AiConfig reads it`() {
        clearExecutor()
        AtmosphereKoogAutoConfiguration().koogAgentRuntime("qwen2.5:3b", "", "", " local ")
        assertNotNull(executor())
    }

    @Test
    fun `a blank base url derives Gemini for a remote non-OpenAI model`() {
        // The regression this closes is a *config* one, but it can only be fixed
        // here. samples/spring-boot-multi-agent-startup-team defaulted
        // atmosphere.koog.base-url to the Gemini URL so the keyed quickstart
        // worked; a configured URL out-ranks mode at every resolver, so
        // LLM_MODE=local was unreachable and a keyless local run silently dialled
        // Google. The sample can only drop that default if a blank base-url still
        // reaches Gemini for the gemini-2.5-flash it asks for.
        assertEquals(AiConfig.GEMINI_ENDPOINT,
            AtmosphereKoogAutoConfiguration.resolveEndpoint("", "remote", "gemini-2.5-flash"),
            "a blank base-url in remote mode must derive the provider from the model id")
    }

    @Test
    fun `a blank base url in local mode wins over the model-derived provider`() {
        // The half that was broken in the sample: LLM_MODE=local and nothing else
        // exported must reach loopback Ollama, even though the configured model
        // id still names a cloud model.
        assertEquals(AiConfig.OLLAMA_ENDPOINT,
            AtmosphereKoogAutoConfiguration.resolveEndpoint("", "local", "gemini-2.5-flash"),
            "local mode must reach loopback Ollama, not the model's cloud provider")
    }

    @Test
    fun `an explicit base url out-ranks both mode and model`() {
        assertEquals("http://gpu-box:8000/v1",
            AtmosphereKoogAutoConfiguration.resolveEndpoint(
                "http://gpu-box:8000/v1", "local", "gemini-2.5-flash"),
            "LLM_BASE_URL must still win — that is how vLLM / LM Studio are reached")
    }

    @Test
    fun `an OpenAI-shaped model keeps the native client branch`() {
        // Collapsing back to blank is what keeps OpenAIModels resolution (and
        // Koog's own client settings) in charge for real OpenAI ids.
        assertEquals("",
            AtmosphereKoogAutoConfiguration.resolveEndpoint("", "remote", "gpt-4o"),
            "an OpenAI model must not be pinned to an explicit endpoint")
    }

    @Test
    fun `a key over plain http to a non-loopback endpoint fails the start`() {
        // Before, LLM_BASE_URL=http://<any host> sent the key in cleartext.
        val refused = assertFailsWith<IllegalArgumentException> {
            AtmosphereKoogAutoConfiguration().koogAgentRuntime(
                "qwen2.5:7b", "sk-test", "http://llm.invalid:8000/v1", "remote")
        }
        assertTrue(refused.message!!.contains("http://llm.invalid:8000/v1"), refused.message)
        assertTrue(refused.message!!.contains(CleartextApiKeyPolicy.ALLOW_PROPERTY), refused.message)
    }

    @Test
    fun `the opt-in is read from the application's environment and lets the key through`() {
        val properties = StandardEnvironment()
        properties.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        properties.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        properties.propertySources.addFirst(MapPropertySource("test", mapOf<String, Any>(
            "llm.api-key" to "sk-test", "llm.base-url" to "http://llm.invalid:8000/v1",
            CleartextApiKeyPolicy.ALLOW_PROPERTY to "true")))
        val settings = AtmosphereKoogAutoConfiguration.resolveSettings(properties)
        assertTrue(settings.allowCleartextApiKey)
        clearExecutor()
        AtmosphereKoogAutoConfiguration().koogAgentRuntime(settings.model, settings.apiKey, settings.baseUrl,
            settings.mode, null, settings.allowCleartextApiKey)
        assertNotNull(executor())
        // Keyless, the same endpoint needs no opt-in.
        AtmosphereKoogAutoConfiguration().koogAgentRuntime("qwen2.5:7b", "", "http://llm.invalid:8000/v1", "local")
    }

    @Test
    fun `a blank base url with a Gemini model keeps the model id verbatim`() {
        // Wiring-level proof of the same fix: before it, a blank base-url took the
        // native OpenAI branch, where 'gemini-2.5-flash' is absent from
        // OpenAIModels and got silently coerced to gpt-4o — a Gemini key sent to
        // api.openai.com under the wrong model name.
        clearExecutor()
        AtmosphereKoogAutoConfiguration().koogAgentRuntime(
            "gemini-2.5-flash", "test-key", "", "remote")
        assertNotNull(executor())
        assertEquals("gemini-2.5-flash", defaultModel()?.id,
            "a Gemini id must survive verbatim when no base-url is configured")
    }

    @Test
    fun `an executor the application sets itself wins over the one the auto-configuration built`() {
        // The documented path for a non-OpenAI Koog client (Anthropic, Bedrock,
        // Ollama) is KoogAgentRuntime.setPromptExecutor(custom), typically from
        // an ApplicationRunner. The auto-configuration builds its OpenAI
        // executor on every start, so it must offer it to the context's
        // environment, never bind it there, or the custom executor is ignored.
        val environment = AiEnvironment.create { null }
        try {
            AtmosphereKoogAutoConfiguration().koogAgentRuntime(
                "gpt-4o", "sk-test", "", "", providerOf(environment))
            val auto = koogOf(environment).applicationExecutor()
            assertNotNull(auto, "with no executor of the application's own, the auto-configured one serves")

            val custom = object : PromptExecutor() {
                override fun executeStreaming(
                    prompt: Prompt,
                    model: LLModel,
                    tools: List<ToolDescriptor>
                ): Flow<StreamFrame> = emptyFlow()

                override suspend fun execute(
                    prompt: Prompt,
                    model: LLModel,
                    tools: List<ToolDescriptor>
                ): Message.Assistant = Message.Assistant(content = "", metaInfo = ResponseMetaInfo.Empty)

                override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
                    ModerationResult(false, emptyMap<ModerationCategory, ModerationCategoryResult>())

                override fun close() {}
            }
            KoogAgentRuntime.setPromptExecutor(custom)
            KoogAgentRuntime.setDefaultModel(LLModel(LLMProvider.Anthropic, "claude-custom"))

            val runtime = koogOf(environment)
            assertSame(custom, runtime.applicationExecutor(),
                "KoogAgentRuntime.setPromptExecutor(custom) must win over the auto-configured executor")
            assertNotSame(auto, runtime.applicationExecutor())
            assertEquals("claude-custom", runtime.applicationDefaultModel().id,
                "KoogAgentRuntime.setDefaultModel(custom) must win over the auto-configured model")
        } finally {
            environment.close()
        }
    }

    @Test
    fun `the auto-configured model serves the application when nothing else is set`() {
        val environment = AiEnvironment.create { null }
        try {
            AtmosphereKoogAutoConfiguration().koogAgentRuntime(
                "qwen2.5:3b", "", "", "local", providerOf(environment))
            assertEquals("qwen2.5:3b", koogOf(environment).applicationDefaultModel().id)
            assertNull(executor(), "an application's executor must not leak into the JVM-wide setter")
        } finally {
            environment.close()
        }
    }

    /**
     * An environment with these application properties and these environment
     * variables only, so a host that exports `LLM_*` does not change the outcome.
     */
    private fun springEnvironment(
        properties: Map<String, Any> = emptyMap(),
        variables: Map<String, Any> = emptyMap()
    ): StandardEnvironment {
        val environment = StandardEnvironment()
        val sources = environment.propertySources
        sources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables))
        sources.replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
            MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, emptyMap()))
        sources.addLast(MapPropertySource("application", properties))
        return environment
    }

    @Test
    fun `llm properties reach Koog, as the other adapters read them`() {
        // The Spring Boot starters map atmosphere.ai.* onto an unset llm.*; Koog
        // read only LLM_* and atmosphere.koog.*, so neither reached it.
        val settings = AtmosphereKoogAutoConfiguration.resolveSettings(springEnvironment(mapOf(
            "llm.base-url" to "http://gpu-box:8000/v1",
            "llm.model" to "qwen2.5:7b",
            "llm.api-key" to "sk-llm",
            "llm.mode" to "local")))
        assertEquals(AtmosphereKoogAutoConfiguration.KoogSettings(
            model = "qwen2.5:7b", apiKey = "sk-llm", baseUrl = "http://gpu-box:8000/v1", mode = "local"), settings)
    }

    @Test
    fun `atmosphere koog properties win over llm properties`() {
        val settings = AtmosphereKoogAutoConfiguration.resolveSettings(springEnvironment(mapOf(
            "atmosphere.koog.base-url" to "http://koog-only:8000/v1",
            "atmosphere.koog.model" to "gemini-2.5-flash",
            "llm.base-url" to "http://gpu-box:8000/v1",
            "llm.model" to "qwen2.5:7b")))
        assertEquals("http://koog-only:8000/v1", settings.baseUrl)
        assertEquals("gemini-2.5-flash", settings.model)
    }

    @Test
    fun `the LLM environment variables and OPENAI_API_KEY are still read`() {
        val settings = AtmosphereKoogAutoConfiguration.resolveSettings(springEnvironment(
            variables = mapOf("LLM_BASE_URL" to "http://from-env:8000/v1", "LLM_MODEL" to "llama3.2",
                "LLM_MODE" to "local", "OPENAI_API_KEY" to "sk-openai")))
        assertEquals(AtmosphereKoogAutoConfiguration.KoogSettings(
            model = "llama3.2", apiKey = "sk-openai", baseUrl = "http://from-env:8000/v1", mode = "local"), settings)
    }

    @Test
    fun `a blank llm api key does not hide OPENAI_API_KEY`() {
        // llm.api-key: ${LLM_API_KEY:} is blank when the variable is unset; a
        // placeholder chain would stop at it.
        val settings = AtmosphereKoogAutoConfiguration.resolveSettings(springEnvironment(
            properties = mapOf("llm.api-key" to "", "llm.base-url" to ""),
            variables = mapOf("OPENAI_API_KEY" to "sk-openai")))
        assertEquals("sk-openai", settings.apiKey)
        assertEquals("", settings.baseUrl)
    }

    @Test
    fun `nothing set keeps the defaults`() {
        assertEquals(AtmosphereKoogAutoConfiguration.KoogSettings(
            model = "gpt-4o", apiKey = "", baseUrl = "", mode = ""),
            AtmosphereKoogAutoConfiguration.resolveSettings(springEnvironment()))
    }
}
