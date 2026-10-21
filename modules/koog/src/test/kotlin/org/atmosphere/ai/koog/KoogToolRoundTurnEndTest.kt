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
import org.atmosphere.ai.ApprovalTimedOutException
import org.atmosphere.ai.CollectingSession
import org.atmosphere.ai.tool.ToolDefinition
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A tool round that ended the turn (an approval that ran out with the turn's
 * time limit ends the session with an error) calls the model no more. Koog's
 * `AIAgent` owns the loop; the runtime drives it over a model that asks for a
 * tool on its first call.
 */
class KoogToolRoundTurnEndTest {

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
    fun `a tool round that ended the turn makes no follow-up model call`() {
        val session = CollectingSession("turn-end-1")
        val tool = ToolDefinition.builder("echo", "Echo a value")
            .parameter("value", "the value", "string")
            .executor {
                session.error(ApprovalTimedOutException(
                    "Approval for tool 'echo' was not given before the turn's time limit", "apr_1", "echo"))
                """{"status":"timeout"}"""
            }
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

        assertTrue(session.isClosed, "the tool round ended the turn")
        assertIs<ApprovalTimedOutException>(session.failure(),
            "the turn ends with the approval that ran out, not a later error")
        assertEquals(1, modelCalls.get(), "no follow-up model call once the turn ended")
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
                Message.Assistant(content = "late answer", metaInfo = ResponseMetaInfo.Empty)
            }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> {
            calls.incrementAndGet()
            return flowOf(StreamFrame.TextDelta("late answer"))
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
