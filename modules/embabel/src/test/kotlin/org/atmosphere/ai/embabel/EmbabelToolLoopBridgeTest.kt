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

import com.embabel.agent.api.tool.callback.AfterLlmCallContext
import com.embabel.agent.api.tool.callback.BeforeLlmCallContext
import com.embabel.agent.core.Usage
import com.embabel.chat.AssistantMessage
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.Message
import com.embabel.chat.ToolCall
import org.atmosphere.ai.AiEvent
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.llm.ToolLoopPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the native Embabel tool-loop enforcement bridge: the transformer seam
 * strips tool calls at the cap so Embabel's own loop stops, and breach
 * reporting matches the wire-layer `ToolLoopGuard` (a single
 * `ToolLoopExhaustedException` under FAIL, silent completion under
 * COMPLETE_WITHOUT_TOOLS).
 */
class EmbabelToolLoopBridgeTest {

    @Test
    fun `below the cap a tool-call response passes through unchanged`() {
        val session = RecordingSession()
        val bridge = EmbabelToolLoopBridge(ToolLoopPolicy.strict(3), session)

        val result = bridge.transformAfterLlmCall(afterCall(toolCallResponse("keep going")))

        assertInstanceOf(AssistantMessageWithToolCalls::class.java, result,
            "under the cap the tool calls must survive so Embabel's loop continues")
        assertTrue(session.errors.isEmpty())
    }

    @Test
    fun `at the cap the tool calls are stripped so the loop stops`() {
        val session = RecordingSession()
        val bridge = EmbabelToolLoopBridge(ToolLoopPolicy.strict(1), session)

        val result = bridge.transformAfterLlmCall(afterCall(toolCallResponse("final answer")))

        assertFalse(result is AssistantMessageWithToolCalls,
            "at the cap the returned message must carry no tool calls — Embabel's " +
                "loop continuation check keys on that")
        assertEquals("final answer", result.content)
    }

    @Test
    fun `FAIL fires a single ToolLoopExhaustedException even past the cap`() {
        val session = RecordingSession()
        val bridge = EmbabelToolLoopBridge(ToolLoopPolicy.strict(1), session)

        bridge.transformAfterLlmCall(afterCall(toolCallResponse("a")))
        bridge.transformAfterLlmCall(afterCall(toolCallResponse("b")))

        assertEquals(1, session.errors.size, "the breach frame must fire exactly once")
        assertInstanceOf(ToolLoopPolicy.ToolLoopExhaustedException::class.java, session.errors.single())
        assertEquals(1, (session.errors.single() as ToolLoopPolicy.ToolLoopExhaustedException).maxIterations())
    }

    @Test
    fun `COMPLETE_WITHOUT_TOOLS stops the loop without erroring`() {
        val session = RecordingSession()
        val bridge = EmbabelToolLoopBridge(ToolLoopPolicy.maxIterations(1), session)

        val result = bridge.transformAfterLlmCall(afterCall(toolCallResponse("done")))

        assertFalse(result is AssistantMessageWithToolCalls)
        assertTrue(session.errors.isEmpty(),
            "COMPLETE_WITHOUT_TOOLS completes with the last text, it must not error")
    }

    @Test
    fun `a plain response without tool calls passes through even past the cap`() {
        val session = RecordingSession()
        val bridge = EmbabelToolLoopBridge(ToolLoopPolicy.strict(1), session)

        val plain: Message = AssistantMessage("no tools here")
        val result = bridge.transformAfterLlmCall(afterCall(plain))

        assertEquals(plain, result, "there are no tool calls to strip — nothing to enforce")
        assertTrue(session.errors.isEmpty())
    }

    @Test
    fun `the inspector seam is a FAIL backstop when dispatch runs past the cap`() {
        val session = RecordingSession()
        val bridge = EmbabelToolLoopBridge(ToolLoopPolicy.strict(1), session)

        bridge.beforeLlmCall(beforeCall())   // dispatch 1 — at the cap, allowed
        bridge.beforeLlmCall(beforeCall())   // dispatch 2 — past the cap, backstop trips

        assertEquals(1, session.errors.size)
        assertInstanceOf(ToolLoopPolicy.ToolLoopExhaustedException::class.java, session.errors.single())
    }

    @Test
    fun `a blank stripped response falls back to the last assistant text`() {
        val session = RecordingSession()
        val bridge = EmbabelToolLoopBridge(ToolLoopPolicy.maxIterations(1), session)

        val history = listOf<Message>(AssistantMessage("earlier useful answer"))
        val result = bridge.transformAfterLlmCall(
            afterCall(toolCallResponse(""), history = history))

        assertEquals("earlier useful answer", result.content,
            "a blank final message would re-enter Embabel's EmptyResponsePolicy loop")
    }

    // ---------- Helpers ----------

    private fun toolCallResponse(content: String): AssistantMessageWithToolCalls =
        AssistantMessageWithToolCalls(content, listOf(ToolCall("id-1", "search", "{}")))

    private fun afterCall(response: Message, history: List<Message> = emptyList()) =
        AfterLlmCallContext(history, 0, response, Usage(0, 0, null))

    private fun beforeCall() =
        BeforeLlmCallContext(emptyList(), 0, emptyList(), null)

    private class RecordingSession : StreamingSession {
        val errors = mutableListOf<Throwable>()
        override fun sessionId(): String = "tool-loop-bridge-test"
        override fun send(text: String) { }
        override fun sendMetadata(key: String, value: Any) { }
        override fun progress(message: String) { }
        override fun complete() { }
        override fun complete(summary: String) { }
        override fun error(t: Throwable) { errors.add(t) }
        override fun isClosed(): Boolean = false
        override fun emit(event: AiEvent) { }
    }
}
