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
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.atmosphere.ai.AiCapability
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.StreamingSessions
import org.atmosphere.cpr.AtmosphereResource
import org.atmosphere.cpr.Broadcaster
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import java.util.concurrent.Future
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class KoogAgentRuntimeTest {

    private lateinit var resource: AtmosphereResource
    private lateinit var broadcaster: Broadcaster

    @Suppress("UNCHECKED_CAST")
    @BeforeEach
    fun setUp() {
        resource = mock(AtmosphereResource::class.java)
        broadcaster = mock(Broadcaster::class.java)
        `when`(resource.uuid()).thenReturn("resource-1")
        `when`(resource.getBroadcaster()).thenReturn(broadcaster)
        `when`(broadcaster.broadcast(any<Any>(), any<Set<AtmosphereResource>>()))
            .thenReturn(mock(Future::class.java) as Future<Any>)
    }

    @AfterEach
    fun tearDown() {
        // Clear static executor to avoid test leakage
        clearExecutor()
    }

    private fun clearExecutor() {
        try {
            // Kotlin companion object stores the field on the outer class as a static
            val field = KoogAgentRuntime::class.java.getDeclaredField("promptExecutor")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {
            // Fallback: try companion inner class
            try {
                val companionClass = Class.forName("org.atmosphere.ai.koog.KoogAgentRuntime\$Companion")
                for (f in KoogAgentRuntime::class.java.declaredFields) {
                    if (f.type == PromptExecutor::class.java ||
                        f.name.contains("promptExecutor", ignoreCase = true)) {
                        f.isAccessible = true
                        f.set(null, null)
                        return
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun context(message: String = "hello"): AgentExecutionContext {
        return AgentExecutionContext(
            message, null, null, null, null, null, null,
            emptyList(), null, null, emptyList(), emptyMap(), emptyList(), null
        )
    }

    /** Creates a fake PromptExecutor that streams the given frames. */
    private fun fakeExecutor(vararg frames: StreamFrame): PromptExecutor {
        return object : PromptExecutor() {
            override fun executeStreaming(
                prompt: Prompt,
                model: LLModel,
                tools: List<ToolDescriptor>
            ): Flow<StreamFrame> = flowOf(*frames)

            override suspend fun execute(
                prompt: Prompt,
                model: LLModel,
                tools: List<ToolDescriptor>
            ): List<Message.Response> = emptyList()

            override suspend fun moderate(
                prompt: Prompt,
                model: LLModel
            ): ModerationResult = ModerationResult(false, emptyMap())

            override fun close() {}
        }
    }

    /** Creates a fake PromptExecutor that throws on streaming. */
    private fun failingExecutor(error: Exception): PromptExecutor {
        return object : PromptExecutor() {
            override fun executeStreaming(
                prompt: Prompt,
                model: LLModel,
                tools: List<ToolDescriptor>
            ): Flow<StreamFrame> = throw error

            override suspend fun execute(
                prompt: Prompt,
                model: LLModel,
                tools: List<ToolDescriptor>
            ): List<Message.Response> = throw error

            override suspend fun moderate(
                prompt: Prompt,
                model: LLModel
            ): ModerationResult = ModerationResult(false, emptyMap())

            override fun close() {}
        }
    }

    private fun capturedMessages(): List<String> {
        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(broadcaster, atLeast(1)).broadcast(captor.capture(), any<Set<AtmosphereResource>>())
        return captor.allValues.map { it.toString() }
    }

    // ── Basic properties ──

    @Test
    fun `name returns koog`() {
        assertEquals("koog", KoogAgentRuntime().name())
    }

    @Test
    fun `isAvailable returns true when koog is on classpath`() {
        assertTrue(KoogAgentRuntime().isAvailable())
    }

    @Test
    fun `priority is 100`() {
        assertEquals(100, KoogAgentRuntime().priority())
    }

    @Test
    fun `capabilities include all expected values`() {
        val caps = KoogAgentRuntime().capabilities()
        assertTrue(caps.contains(AiCapability.TEXT_STREAMING))
        assertTrue(caps.contains(AiCapability.TOOL_CALLING))
        assertTrue(caps.contains(AiCapability.STRUCTURED_OUTPUT))
        assertTrue(caps.contains(AiCapability.AGENT_ORCHESTRATION))
        assertTrue(caps.contains(AiCapability.CONVERSATION_MEMORY))
        assertTrue(caps.contains(AiCapability.SYSTEM_PROMPT))
    }

    @Test
    fun `execute without executor throws IllegalStateException`() {
        clearExecutor()
        try {
            KoogAgentRuntime().execute(
                context(),
                mock(org.atmosphere.ai.StreamingSession::class.java)
            )
            fail("Should have thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("PromptExecutor not configured"))
        }
    }

    // ── StreamFrame → AiEvent mapping ──

    @Test
    fun `TextDelta frames are forwarded as text-delta AiEvents`() {
        KoogAgentRuntime.setPromptExecutor(fakeExecutor(
            StreamFrame.TextDelta("Hello "),
            StreamFrame.TextDelta("world!"),
            StreamFrame.End()
        ))

        val session = StreamingSessions.start("td-session", resource)
        KoogAgentRuntime().execute(context(), session)

        val messages = capturedMessages()
        assertTrue(messages.any { it.contains("\"event\":\"text-delta\"") && it.contains("Hello ") })
        assertTrue(messages.any { it.contains("\"event\":\"text-delta\"") && it.contains("world!") })
    }

    @Test
    fun `TextComplete frame is forwarded as text-complete AiEvent`() {
        KoogAgentRuntime.setPromptExecutor(fakeExecutor(
            StreamFrame.TextComplete("Full response"),
            StreamFrame.End()
        ))

        val session = StreamingSessions.start("tc-session", resource)
        KoogAgentRuntime().execute(context(), session)

        val messages = capturedMessages()
        assertTrue(messages.any { it.contains("\"event\":\"text-complete\"") && it.contains("Full response") })
    }

    @Test
    fun `ToolCallComplete frame triggers tool-start event`() {
        KoogAgentRuntime.setPromptExecutor(fakeExecutor(
            StreamFrame.ToolCallComplete("call-1", "get_weather", "{\"city\":\"Montreal\"}"),
            StreamFrame.End()
        ))

        val session = StreamingSessions.start("tc-session", resource)
        KoogAgentRuntime().execute(context(), session)

        val messages = capturedMessages()
        assertTrue(messages.any { it.contains("\"event\":\"tool-start\"") && it.contains("get_weather") })
    }

    @Test
    fun `ReasoningDelta frame is forwarded as progress AiEvent`() {
        KoogAgentRuntime.setPromptExecutor(fakeExecutor(
            StreamFrame.ReasoningDelta("Thinking about the answer..."),
            StreamFrame.End()
        ))

        val session = StreamingSessions.start("rd-session", resource)
        KoogAgentRuntime().execute(context(), session)

        val messages = capturedMessages()
        assertTrue(messages.any { it.contains("\"event\":\"progress\"") && it.contains("Thinking about the answer") })
    }

    @Test
    fun `ToolCallDelta frames are silently accumulated`() {
        KoogAgentRuntime.setPromptExecutor(fakeExecutor(
            StreamFrame.ToolCallDelta("call-1", "search", "{\"q\":"),
            StreamFrame.ToolCallDelta("call-1", null, "\"test\"}"),
            StreamFrame.End()
        ))

        val session = StreamingSessions.start("tcd-session", resource)
        KoogAgentRuntime().execute(context(), session)

        val messages = capturedMessages()
        // ToolCallDelta should NOT produce agent-step events
        assertTrue(messages.none { it.contains("\"event\":\"agent-step\"") && it.contains("search") })
    }

    @Test
    fun `End frame triggers session complete`() {
        KoogAgentRuntime.setPromptExecutor(fakeExecutor(
            StreamFrame.TextDelta("Done"),
            StreamFrame.End()
        ))

        val session = StreamingSessions.start("end-session", resource)
        KoogAgentRuntime().execute(context(), session)

        val messages = capturedMessages()
        assertTrue(messages.any { it.contains("\"type\":\"complete\"") })
    }

    @Test
    fun `events on closed session are ignored`() {
        KoogAgentRuntime.setPromptExecutor(fakeExecutor(
            StreamFrame.TextDelta("Should be ignored"),
            StreamFrame.End()
        ))

        val session = StreamingSessions.start("closed-session", resource)
        session.complete()
        reset(broadcaster)

        @Suppress("UNCHECKED_CAST")
        `when`(broadcaster.broadcast(any<Any>(), any<Set<AtmosphereResource>>()))
            .thenReturn(mock(Future::class.java) as Future<Any>)

        KoogAgentRuntime().execute(context(), session)

        // Session was already closed, no text-delta should get through
        verify(broadcaster, atMost(1)).broadcast(any<Any>(), any<Set<AtmosphereResource>>())
    }

    // ── Full lifecycle ──

    @Test
    fun `full streaming lifecycle with mixed frame types`() {
        KoogAgentRuntime.setPromptExecutor(fakeExecutor(
            StreamFrame.ReasoningDelta("Let me think..."),
            StreamFrame.TextDelta("Hello, "),
            StreamFrame.TextDelta("world!"),
            StreamFrame.TextDelta(" The time is 3pm."),
            StreamFrame.End()
        ))

        val session = StreamingSessions.start("lifecycle-session", resource)
        KoogAgentRuntime().execute(context("What time is it?"), session)

        val messages = capturedMessages()
        assertTrue(messages.any { it.contains("Let me think") })
        assertTrue(messages.any { it.contains("Hello, ") })
        assertTrue(messages.any { it.contains("world!") })
        assertTrue(messages.any { it.contains("The time is 3pm") })
        assertTrue(messages.last().contains("\"type\":\"complete\""))
    }

    @Test
    fun `executor error produces session error`() {
        KoogAgentRuntime.setPromptExecutor(failingExecutor(RuntimeException("LLM unavailable")))

        val session = StreamingSessions.start("error-session", resource)
        KoogAgentRuntime().execute(context(), session)

        val messages = capturedMessages()
        assertTrue(messages.any { it.contains("\"type\":\"error\"") || it.contains("LLM unavailable") })
    }
}
