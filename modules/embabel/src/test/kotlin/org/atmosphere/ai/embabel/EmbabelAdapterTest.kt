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
import com.embabel.agent.domain.library.HasContent
import com.embabel.chat.Message
import org.atmosphere.ai.StreamingSessions
import org.atmosphere.cpr.Broadcaster
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbabelAdapterTest {

    private lateinit var broadcaster: Broadcaster
    private lateinit var channel: AtmosphereOutputChannel

    @BeforeEach
    fun setUp() {
        broadcaster = mock(Broadcaster::class.java)
        val session = StreamingSessions.start("test-session", broadcaster, "resource-1")
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
    fun `message events are forwarded as tokens`() {
        val event = MessageOutputChannelEvent("proc-1", mockMessage("Hello from agent"))
        channel.send(event)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(broadcaster).broadcast(captor.capture())

        val msg = captor.value.toString()
        assertTrue(msg.contains("\"type\":\"token\""))
        assertTrue(msg.contains("\"data\":\"Hello from agent\""))
    }

    @Test
    fun `content events are forwarded as tokens`() {
        val event = ContentOutputChannelEvent("proc-1", mockHasContent("Structured content here"))
        channel.send(event)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(broadcaster).broadcast(captor.capture())

        val msg = captor.value.toString()
        assertTrue(msg.contains("\"type\":\"token\""))
        assertTrue(msg.contains("\"data\":\"Structured content here\""))
    }

    @Test
    fun `progress events are forwarded as progress`() {
        val event = ProgressOutputChannelEvent("proc-1", "Thinking...")
        channel.send(event)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(broadcaster).broadcast(captor.capture())

        val msg = captor.value.toString()
        assertTrue(msg.contains("\"type\":\"progress\""))
        assertTrue(msg.contains("\"data\":\"Thinking...\""))
    }

    @Test
    fun `info logging events are forwarded as progress`() {
        val event = LoggingOutputChannelEvent(
            "proc-1", "Agent step 1 complete", LoggingOutputChannelEvent.Level.INFO, null
        )
        channel.send(event)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(broadcaster).broadcast(captor.capture())

        val msg = captor.value.toString()
        assertTrue(msg.contains("\"type\":\"progress\""))
        assertTrue(msg.contains("\"data\":\"Agent step 1 complete\""))
    }

    @Test
    fun `debug logging events are not forwarded`() {
        val event = LoggingOutputChannelEvent(
            "proc-1", "Internal debug info", LoggingOutputChannelEvent.Level.DEBUG, null
        )
        channel.send(event)

        verifyNoInteractions(broadcaster)
    }

    @Test
    fun `events on closed session are ignored`() {
        val session = StreamingSessions.start("closed-session", broadcaster, "resource-2")
        val closedChannel = AtmosphereOutputChannel(session)

        session.complete()
        reset(broadcaster)

        closedChannel.send(
            MessageOutputChannelEvent("proc-1", mockMessage("Should be ignored"))
        )

        verifyNoInteractions(broadcaster)
    }

    @Test
    fun `full agent lifecycle`() {
        val session = StreamingSessions.start("lifecycle-session", broadcaster, "resource-3")
        val ch = AtmosphereOutputChannel(session)

        ch.send(ProgressOutputChannelEvent("proc-1", "Starting agent..."))
        ch.send(MessageOutputChannelEvent("proc-1", mockMessage("Step 1 result")))
        ch.send(ProgressOutputChannelEvent("proc-1", "Running tool..."))
        ch.send(MessageOutputChannelEvent("proc-1", mockMessage("Final answer")))
        session.complete()

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(broadcaster, times(5)).broadcast(captor.capture())

        val messages = captor.allValues.map { it.toString() }
        assertTrue(messages[0].contains("\"type\":\"progress\""))
        assertTrue(messages[1].contains("\"type\":\"token\""))
        assertTrue(messages[2].contains("\"type\":\"progress\""))
        assertTrue(messages[3].contains("\"type\":\"token\""))
        assertTrue(messages[4].contains("\"type\":\"complete\""))
    }

    @Test
    fun `adapter creates channel and invokes runner`() {
        val adapter = EmbabelStreamingAdapter()
        assertEquals("embabel", adapter.name())

        val session = StreamingSessions.start("adapter-session", broadcaster, "resource-4")
        var channelReceived: AtmosphereOutputChannel? = null

        val request = EmbabelStreamingAdapter.AgentRequest("test-agent") { ch ->
            channelReceived = ch
            ch.send(MessageOutputChannelEvent("proc-1", mockMessage("Agent output")))
        }

        adapter.stream(request, session)

        assertTrue(channelReceived != null, "Runner should receive a channel")

        val captor = ArgumentCaptor.forClass(Any::class.java)
        // progress ("Starting agent: test-agent...") + token
        verify(broadcaster, times(2)).broadcast(captor.capture())

        val messages = captor.allValues.map { it.toString() }
        assertTrue(messages[0].contains("\"type\":\"progress\""))
        assertTrue(messages[0].contains("Starting agent: test-agent"))
        assertTrue(messages[1].contains("\"data\":\"Agent output\""))
    }

    @Test
    fun `adapter via generic interface`() {
        val adapter = EmbabelStreamingAdapter()
        val session = StreamingSessions.start("generic-session", broadcaster, "resource-5")

        val request = EmbabelStreamingAdapter.AgentRequest("my-agent") { ch ->
            ch.send(ProgressOutputChannelEvent("proc-1", "Working..."))
        }

        adapter.stream(request, session)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(broadcaster, atLeast(2)).broadcast(captor.capture())
    }
}
