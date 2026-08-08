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
}
