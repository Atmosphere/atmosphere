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

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import org.atmosphere.ai.AiConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    private fun defaultModel(): LLModel {
        val f = KoogAgentRuntime::class.java.getDeclaredField("defaultModel")
        f.isAccessible = true
        return f.get(null) as LLModel
    }

    private fun clearExecutor() {
        val f = KoogAgentRuntime::class.java.getDeclaredField("promptExecutor")
        f.isAccessible = true
        f.set(null, null)
    }

    @AfterEach
    fun reset() = clearExecutor()

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
        assertEquals("gpt-4o", defaultModel().id)
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
        assertEquals("gemini-2.5-flash", defaultModel().id,
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
        assertEquals("qwen2.5:3b", defaultModel().id)
    }

    @Test
    fun `local mode keeps an explicit base url`() {
        // LLM_BASE_URL must win over the local default, so a vLLM or LM Studio
        // endpoint on another host is still honoured in local mode.
        clearExecutor()
        AtmosphereKoogAutoConfiguration().koogAgentRuntime(
            "qwen2.5:3b", "", "http://gpu-box:8000/v1", "local")
        assertNotNull(executor())
        assertEquals("qwen2.5:3b", defaultModel().id)
    }

    @Test
    fun `local mode is case insensitive`() {
        clearExecutor()
        AtmosphereKoogAutoConfiguration().koogAgentRuntime("qwen2.5:3b", "", "", "LOCAL")
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
    fun `a blank base url with a Gemini model keeps the model id verbatim`() {
        // Wiring-level proof of the same fix: before it, a blank base-url took the
        // native OpenAI branch, where 'gemini-2.5-flash' is absent from
        // OpenAIModels and got silently coerced to gpt-4o — a Gemini key sent to
        // api.openai.com under the wrong model name.
        clearExecutor()
        AtmosphereKoogAutoConfiguration().koogAgentRuntime(
            "gemini-2.5-flash", "test-key", "", "remote")
        assertNotNull(executor())
        assertEquals("gemini-2.5-flash", defaultModel().id,
            "a Gemini id must survive verbatim when no base-url is configured")
    }
}
