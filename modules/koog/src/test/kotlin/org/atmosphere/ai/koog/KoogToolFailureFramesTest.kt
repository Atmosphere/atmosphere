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
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.AiEnvironment
import org.atmosphere.ai.AiEvent
import org.atmosphere.ai.CollectingSession
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.tool.ToolDefinition
import org.atmosphere.ai.tool.ToolExecutor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * Each Koog tool call is reported once, by ToolExecutionHelper, and a call
 * that failed ends with a tool-error. Koog's own tool events emitted a second
 * tool-start and a tool-result for every call, so a failed call also read
 * "Done" in the Console.
 */
class KoogToolFailureFramesTest {

    private val modelCalls = AtomicInteger()
    private lateinit var environment: AiEnvironment
    private lateinit var runtime: KoogAgentRuntime

    @BeforeEach
    fun setUp() {
        environment = AiEnvironment.create { null }
        KoogAgentRuntime.setPromptExecutor(environment, ToolThenTextExecutor(modelCalls))
        runtime = KoogAgentRuntime().also { it.attach(environment) }
    }

    @AfterEach
    fun tearDown() {
        environment.close()
    }

    @Test
    fun `a tool call is reported once`() {
        assertEquals(listOf("ToolStart:echo", "ToolResult:echo"), run(ToolExecutor { "x" }))
    }

    @Test
    fun `a tool that fails ends with a tool error and no result`() {
        assertEquals(listOf("ToolStart:echo", "ToolError:echo service down"),
            run(ToolExecutor { throw IllegalStateException("echo service down") }))
    }

    private fun run(executor: ToolExecutor): List<String> {
        val session = FrameSession(CollectingSession("koog-tool-frames"))
        val tool = ToolDefinition.builder("echo", "Echo a value")
            .parameter("value", "the value", "string")
            .executor(executor)
            .build()
        val context = AgentExecutionContext(
            "Echo x.", "You are helpful", null,
            null, "session-1", "user-1", "conv-1",
            listOf(tool), null, null,
            emptyList<org.atmosphere.ai.ContextProvider>(),
            emptyMap<String, Any>(),
            emptyList<org.atmosphere.ai.llm.ChatMessage>(),
            null, null
        ).withRetryPolicy(org.atmosphere.ai.RetryPolicy.NONE)

        runtime.executeWithHandle(context, session).whenDone().get(10, TimeUnit.SECONDS)
        return session.frames.toList()
    }

    /** Records the tool frames a turn emits. */
    private class FrameSession(private val inner: CollectingSession) : StreamingSession by inner {
        val frames = CopyOnWriteArrayList<String>()

        override fun emit(event: AiEvent) {
            when (event) {
                is AiEvent.ToolStart -> frames += "ToolStart:" + event.toolName()
                is AiEvent.ToolResult -> frames += "ToolResult:" + event.toolName()
                is AiEvent.ToolError -> frames += "ToolError:" + event.error()
                else -> inner.emit(event)
            }
        }
    }

    /** Asks for the `echo` tool on its first call, answers with text after. */
    private class ToolThenTextExecutor(private val calls: AtomicInteger) : PromptExecutor() {

        private fun answer(): Message.Assistant =
            if (calls.incrementAndGet() == 1) {
                Message.Assistant(
                    MessagePart.Tool.Call(id = "call_1", tool = "echo", args = """{"value":"x"}"""),
                    ResponseMetaInfo.Empty
                )
            } else {
                Message.Assistant(content = "done", metaInfo = ResponseMetaInfo.Empty)
            }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> {
            calls.incrementAndGet()
            return flowOf(StreamFrame.TextDelta("done"))
        }

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Message.Assistant = answer()

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            ModerationResult(false, emptyMap<ModerationCategory, ModerationCategoryResult>())

        override fun close() {}
    }
}
