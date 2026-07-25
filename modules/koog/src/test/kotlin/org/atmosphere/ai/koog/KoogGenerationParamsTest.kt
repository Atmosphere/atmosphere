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
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.AiConfig
import org.atmosphere.ai.CollectingSession
import org.atmosphere.ai.GenerationParams
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Koog Runtime-Truth proof (Correctness Invariant #5) for the framework
 * [GenerationParams]: the `temperature` override rides the provider-agnostic
 * [ai.koog.prompt.params.LLMParams] on the executor dispatch path, and an
 * unset override leaves `LLMParams.temperature` null — byte-identical wire
 * output. topP/stop have no field on the base `LLMParams` and maxTokens is
 * ceded to Koog's model-level configuration, so temperature is the single
 * honored knob (see the contract pin in [KoogRuntimeContractTest]).
 */
class KoogGenerationParamsTest {

    @AfterEach
    fun tearDown() {
        // Clear the static executor and reset AiConfig so state does not leak.
        clearExecutor()
        AiConfig.configure("local", "llama3.2", null, null)
    }

    private fun clearExecutor() {
        for (f in KoogAgentRuntime::class.java.declaredFields) {
            if (f.type == PromptExecutor::class.java) {
                f.isAccessible = true
                f.set(null, null)
            }
        }
    }

    private fun context(message: String = "hello"): AgentExecutionContext {
        return AgentExecutionContext(
            message, null, null, null, null, null, null,
            emptyList<org.atmosphere.ai.tool.ToolDefinition>(), null, null,
            emptyList<org.atmosphere.ai.ContextProvider>(),
            emptyMap<String, Any>(),
            emptyList<org.atmosphere.ai.llm.ChatMessage>(),
            null, null
        )
    }

    /** Captures the [Prompt] handed to the executor so tests can pin its params. */
    private class CapturingExecutor : PromptExecutor() {
        @Volatile var capturedPrompt: Prompt? = null
        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> {
            capturedPrompt = prompt
            return flowOf(StreamFrame.End())
        }
        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Message.Assistant {
            capturedPrompt = prompt
            return Message.Assistant(content = "", metaInfo = ResponseMetaInfo.Empty)
        }
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            ModerationResult(false, emptyMap<ModerationCategory, ModerationCategoryResult>())
        override fun close() {}
    }

    private fun installGeneration(generation: GenerationParams) {
        val settings = AiConfig.configure("local", "llama3.2", null, null)
        val f = AiConfig::class.java.getDeclaredField("instance")
        f.isAccessible = true
        f.set(
            null,
            AiConfig.LlmSettings(
                settings.client(), settings.model(), settings.mode(), settings.baseUrl(),
                settings.apiKey(), settings.promptCacheKeyMode(), generation
            )
        )
    }

    @Test
    fun `temperature rides LLMParams on the executor dispatch path`() {
        installGeneration(GenerationParams(0.33, null, null, null))
        val capturing = CapturingExecutor()
        KoogAgentRuntime.setPromptExecutor(capturing)

        KoogAgentRuntime().execute(context(), CollectingSession())

        val prompt = capturing.capturedPrompt
        assertTrue(prompt != null, "executor should have been invoked")
        assertEquals(
            0.33, prompt.params.temperature,
            "GenerationParams.temperature must land on the Koog prompt's LLMParams"
        )
    }

    @Test
    fun `unset generation leaves LLMParams temperature null`() {
        installGeneration(GenerationParams.defaults())
        val capturing = CapturingExecutor()
        KoogAgentRuntime.setPromptExecutor(capturing)

        KoogAgentRuntime().execute(context(), CollectingSession())

        val prompt = capturing.capturedPrompt
        assertTrue(prompt != null, "executor should have been invoked")
        assertNull(
            prompt.params.temperature,
            "unset generation must leave LLMParams.temperature null — byte-identical dispatch"
        )
    }

    @Test
    fun `temperature rides LLMParams on the tool-calling agent path too`() {
        // A tool-bearing context routes through executeWithAgent (the AIAgent
        // factory), whose temperature parameter threads onto the agent
        // prompt's LLMParams — Mode Parity with the executor path (Inv #7).
        installGeneration(GenerationParams(0.42, null, null, null))
        val capturing = CapturingExecutor()
        KoogAgentRuntime.setPromptExecutor(capturing)

        val tool = org.atmosphere.ai.tool.ToolDefinition.builder("echo", "echoes input")
            .parameter("text", "text to echo", "string")
            .executor { args -> args["text"].toString() }
            .build()
        val ctx = AgentExecutionContext(
            "hello", null, null, null, null, null, null,
            listOf(tool), null, null,
            emptyList<org.atmosphere.ai.ContextProvider>(),
            emptyMap<String, Any>(),
            emptyList<org.atmosphere.ai.llm.ChatMessage>(),
            null, null
        )
        KoogAgentRuntime().execute(ctx, CollectingSession())

        val prompt = capturing.capturedPrompt
        assertTrue(prompt != null, "agent path should have reached the executor")
        assertEquals(
            0.42, prompt.params.temperature,
            "GenerationParams.temperature must ride the AIAgent prompt's LLMParams"
        )
    }
}
