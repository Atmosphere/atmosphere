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

import com.embabel.agent.api.channel.*
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.domain.library.HasContent
import com.embabel.chat.Message
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.StreamingSessions
import org.atmosphere.cpr.AtmosphereResource
import org.atmosphere.cpr.Broadcaster
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import java.util.concurrent.Future
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmbabelAdapterTest {

    private lateinit var resource: AtmosphereResource
    private lateinit var broadcaster: Broadcaster
    private lateinit var channel: AtmosphereOutputChannel

    @Suppress("UNCHECKED_CAST")
    @BeforeEach
    fun setUp() {
        resource = mock(AtmosphereResource::class.java)
        broadcaster = mock(Broadcaster::class.java)
        `when`(resource.uuid()).thenReturn("resource-1")
        `when`(resource.getBroadcaster()).thenReturn(broadcaster)
        `when`(broadcaster.broadcast(any<Any>(), any<Set<AtmosphereResource>>())).thenReturn(mock(Future::class.java) as Future<Any>)
        val session = StreamingSessions.start("test-session", resource)
        channel = AtmosphereOutputChannel(session)
    }

    private fun mockMessage(content: String): Message {
        val msg = mock(Message::class.java)
        `when`(msg.content).thenReturn(content)
        return msg
    }

    private fun mockHasContent(content: String): HasContent {
        val hc = mock(HasContent::class.java)
        `when`(hc.content).thenReturn(content)
        return hc
    }

    @Test
    fun `message events are forwarded as text-delta AiEvents`() {
        val event = MessageOutputChannelEvent("proc-1", mockMessage("Hello from agent"))
        channel.send(event)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(broadcaster).broadcast(captor.capture(), any<Set<AtmosphereResource>>())

        val msg = captor.value.toString()
        assertTrue(msg.contains("\"event\":\"text-delta\""))
        assertTrue(msg.contains("Hello from agent"))
    }

    @Test
    fun `content events are forwarded as text-delta AiEvents`() {
        val event = ContentOutputChannelEvent("proc-1", mockHasContent("Structured content here"))
        channel.send(event)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(broadcaster).broadcast(captor.capture(), any<Set<AtmosphereResource>>())

        val msg = captor.value.toString()
        assertTrue(msg.contains("\"event\":\"text-delta\""))
        assertTrue(msg.contains("Structured content here"))
    }

    @Test
    fun `progress events are forwarded as agent-step AiEvents`() {
        val event = ProgressOutputChannelEvent("proc-1", "Thinking...")
        channel.send(event)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(broadcaster).broadcast(captor.capture(), any<Set<AtmosphereResource>>())

        val msg = captor.value.toString()
        assertTrue(msg.contains("\"event\":\"agent-step\""))
        assertTrue(msg.contains("Thinking..."))
    }

    @Test
    fun `info logging events are forwarded as progress AiEvents`() {
        val event = LoggingOutputChannelEvent(
            "proc-1", "Agent step 1 complete", LoggingOutputChannelEvent.Level.INFO, null
        )
        channel.send(event)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(broadcaster).broadcast(captor.capture(), any<Set<AtmosphereResource>>())

        val msg = captor.value.toString()
        assertTrue(msg.contains("\"event\":\"progress\""))
        assertTrue(msg.contains("Agent step 1 complete"))
    }

    @Test
    fun `debug logging events are not forwarded`() {
        val event = LoggingOutputChannelEvent(
            "proc-1", "Internal debug info", LoggingOutputChannelEvent.Level.DEBUG, null
        )
        channel.send(event)

        verify(broadcaster, never()).broadcast(any<Any>(), any<Set<AtmosphereResource>>())
    }

    @Test
    fun `events on closed session are ignored`() {
        val session = StreamingSessions.start("closed-session", resource)
        val closedChannel = AtmosphereOutputChannel(session)

        session.complete()
        reset(broadcaster)

        closedChannel.send(
            MessageOutputChannelEvent("proc-1", mockMessage("Should be ignored"))
        )

        verify(broadcaster, never()).broadcast(any<Any>(), any<Set<AtmosphereResource>>())
    }

    @Test
    fun `full agent lifecycle`() {
        val session = StreamingSessions.start("lifecycle-session", resource)
        val ch = AtmosphereOutputChannel(session)

        ch.send(ProgressOutputChannelEvent("proc-1", "Starting agent..."))
        ch.send(MessageOutputChannelEvent("proc-1", mockMessage("Step 1 result")))
        ch.send(ProgressOutputChannelEvent("proc-1", "Running tool..."))
        ch.send(MessageOutputChannelEvent("proc-1", mockMessage("Final answer")))
        session.complete()

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(broadcaster, times(5)).broadcast(captor.capture(), any<Set<AtmosphereResource>>())

        val messages = captor.allValues.map { it.toString() }
        assertTrue(messages[0].contains("\"event\":\"agent-step\""))
        assertTrue(messages[1].contains("\"event\":\"text-delta\""))
        assertTrue(messages[2].contains("\"event\":\"agent-step\""))
        assertTrue(messages[3].contains("\"event\":\"text-delta\""))
        assertTrue(messages[4].contains("\"type\":\"complete\""))
    }

    @Test
    fun `adapter creates channel and invokes runner`() {
        val adapter = EmbabelStreamingAdapter()
        assertEquals("embabel", adapter.name())

        val session = StreamingSessions.start("adapter-session", resource)
        var channelReceived: AtmosphereOutputChannel? = null

        val request = EmbabelStreamingAdapter.AgentRequest("test-agent") { ch ->
            channelReceived = ch
            ch.send(MessageOutputChannelEvent("proc-1", mockMessage("Agent output")))
        }

        adapter.stream(request, session)

        assertTrue(channelReceived != null, "Runner should receive a channel")

        val captor = ArgumentCaptor.forClass(Any::class.java)
        // progress ("Starting agent: test-agent...") + text-delta + complete
        verify(broadcaster, times(3)).broadcast(captor.capture(), any<Set<AtmosphereResource>>())

        val messages = captor.allValues.map { it.toString() }
        assertTrue(messages[0].contains("\"type\":\"progress\""))
        assertTrue(messages[0].contains("Starting agent: test-agent"))
        assertTrue(messages[1].contains("Agent output"))
        assertTrue(messages[2].contains("\"type\":\"complete\""))
    }

    @Test
    fun `adapter via generic interface`() {
        val adapter = EmbabelStreamingAdapter()
        val session = StreamingSessions.start("generic-session", resource)

        val request = EmbabelStreamingAdapter.AgentRequest("my-agent") { ch ->
            ch.send(ProgressOutputChannelEvent("proc-1", "Working..."))
        }

        adapter.stream(request, session)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(broadcaster, atLeast(2)).broadcast(captor.capture(), any<Set<AtmosphereResource>>())
    }

    /**
     * Regression test for the SYSTEM_PROMPT / CONVERSATION_MEMORY honesty bug.
     *
     * Previous behavior: when `context.agentId()` did not match a deployed
     * Embabel `@Agent`, `execute()` threw `IllegalStateException("Agent '<id>'
     * not deployed on the platform")` — the Atmosphere-supplied system prompt
     * and conversation history were silently unreachable.
     *
     * New behavior: the runtime falls back to the Atmosphere-native dispatch
     * path, which obtains an [Ai] factory from the [AgentPlatform] and drives
     * a direct LLM call via `PromptRunner.withSystemPrompt(...).withMessages(...)`.
     * This test proves the dispatch decision is correct by verifying that the
     * "not deployed" error path is NOT taken when the platform returns an
     * empty agent list — the runtime now attempts the native path instead.
     */
    @Test
    fun `runtime falls back to Ai factory path when no deployed agent matches`() {
        val platform = mock(AgentPlatform::class.java)
        `when`(platform.agents()).thenReturn(emptyList())

        EmbabelAgentRuntime.setAgentPlatform(platform)
        val runtime = EmbabelAgentRuntime()

        val session = StreamingSessions.start("fallback-session", resource)
        val context = AgentExecutionContext(
            "Hello", "You are a helpful assistant", "gpt-4o-mini",
            "unknown-agent", "session-1", "user-1", "conv-1",
            emptyList<org.atmosphere.ai.tool.ToolDefinition>(),
            null, null,
            emptyList<org.atmosphere.ai.ContextProvider>(),
            emptyMap<String, Any>(),
            emptyList<org.atmosphere.ai.llm.ChatMessage>(),
            null, null
        )

        // execute() should not throw — errors route to session.error()
        runtime.execute(context, session)

        // Verify the session received an error (aiFactory will fail against a
        // mock platform that doesn't carry real Spring infrastructure), and
        // that the error is NOT the "Agent not deployed" message — i.e., the
        // dispatch correctly routed away from the deployed-agent path.
        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(broadcaster, atLeastOnce()).broadcast(captor.capture(), any<Set<AtmosphereResource>>())

        val errorFrame = captor.allValues.map { it.toString() }
            .firstOrNull { it.contains("\"type\":\"error\"") }
        assertNotNull(errorFrame, "Runtime should emit an error frame from the native dispatch path")
        assertTrue(
            !errorFrame.contains("not deployed on the platform"),
            "Runtime must not throw the legacy 'Agent not deployed' error when " +
                "context.agentId() is unknown — the new dispatch should route to " +
                "the Ai factory path instead. Got: $errorFrame"
        )
    }
}
