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

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.api.tool.ToolCallContext
import com.embabel.agent.api.tool.config.ToolLoopConfiguration
import com.embabel.agent.spi.loop.ImmediateThrowPolicy
import com.embabel.agent.spi.loop.LlmMessageResponse
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.loop.ToolInjectionStrategy
import com.embabel.agent.spi.loop.ToolLoopFactory
import com.embabel.chat.AssistantMessage
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.ToolCall
import com.embabel.chat.UserMessage
import org.atmosphere.ai.ApprovalTimedOutException
import org.atmosphere.ai.CollectingSession
import org.atmosphere.ai.tool.ToolDefinition
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A tool round that ended the turn (an approval that ran out with the turn's
 * time limit ends the session with an error) calls the model no more. Embabel's
 * own tool loop runs the bridged tool over a model that asks for it on its
 * first call.
 */
class EmbabelToolRoundTurnEndTest {

    private val modelCalls = AtomicInteger()

    /** Asks for the `add` tool on its first call, answers with text after. */
    private val sender = LlmMessageSender { _, _ ->
        val message = if (modelCalls.incrementAndGet() == 1) {
            AssistantMessageWithToolCalls("", listOf(ToolCall("call-1", "add", """{"a":1}""")))
        } else {
            AssistantMessage("late answer")
        }
        LlmMessageResponse(message, message.content)
    }

    private fun toolLoop() = ToolLoopFactory.create(ToolLoopConfiguration(), DirectAsyncer, ImmediateThrowPolicy)
        .create(
            sender, ObjectMapper(), ToolInjectionStrategy.NONE, 20, null,
            emptyList(), emptyList(), emptyList(), ToolCallContext.EMPTY
        )

    @Test
    fun `a tool round that ended the turn makes no follow-up model call`() {
        val session = CollectingSession("embabel-turn-end")
        val tool = ToolDefinition.builder("add", "Add numbers")
            .parameter("a", "First", "integer")
            .executor {
                session.error(ApprovalTimedOutException(
                    "Approval for tool 'add' was not given before the turn's time limit", "apr_1", "add"))
                """{"status":"timeout"}"""
            }
            .build()
        val embabelTool = EmbabelToolBridge.toEmbabelTool(tool, session, null, null)

        assertFailsWith<EmbabelToolBridge.TurnEndedSignal> {
            toolLoop().execute(listOf(UserMessage("add")), listOf(embabelTool)) { it }
        }

        assertTrue(session.isClosed, "the tool round ended the turn")
        assertIs<ApprovalTimedOutException>(session.failure())
        assertEquals(1, modelCalls.get(), "no follow-up model call once the turn ended")
    }

    @Test
    fun `a tool round that leaves the turn live still gets the model's answer`() {
        val session = CollectingSession("embabel-live")
        val tool = ToolDefinition.builder("add", "Add numbers")
            .parameter("a", "First", "integer")
            .executor { "2" }
            .build()
        val embabelTool = EmbabelToolBridge.toEmbabelTool(tool, session, null, null)

        val result = toolLoop().execute(listOf(UserMessage("add")), listOf(embabelTool)) { it }

        assertEquals("late answer", result.result)
        assertEquals(2, modelCalls.get(), "the model answers after the tool round")
    }

    /** Runs every block on the caller's thread. */
    private object DirectAsyncer : Asyncer {
        override fun <T> async(block: () -> T): CompletableFuture<T> = CompletableFuture.completedFuture(block())

        override fun <T, R> parallelMap(items: Collection<T>, maxConcurrency: Int, transform: (t: T) -> R): List<R> =
            items.map(transform)
    }
}
