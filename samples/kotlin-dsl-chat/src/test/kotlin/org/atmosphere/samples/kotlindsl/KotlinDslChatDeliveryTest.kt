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
package org.atmosphere.samples.kotlindsl

import kotlinx.coroutines.runBlocking
import org.atmosphere.ai.llm.DemoAgentRuntime
import org.atmosphere.ai.processor.AiEndpointHandler
import org.atmosphere.cpr.AtmosphereFramework
import org.atmosphere.cpr.AtmosphereRequest
import org.atmosphere.cpr.AtmosphereResource
import org.atmosphere.cpr.Broadcaster
import org.atmosphere.kotlin.broadcastSuspend
import org.atmosphere.kotlin.writeSuspend
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Delivery proof for the two Kotlin DSLs [KotlinDslChat] is built on.
 *
 * The agent is pinned to the framework's offline `DemoAgentRuntime` so the
 * suite never depends on the developer's API keys or the network; `main` leaves
 * the runtime unpinned and lets the resolver choose.
 *
 * These tests exercise real behavior: the agent DSL registers a real agent into
 * a real [AtmosphereFramework], a message is driven through the DSL-built
 * transport handler, the agent answers through the framework's AI pipeline, and
 * the suspending coroutine extension actually delivers the reply. The
 * assertions are on the observable effect (what was registered, what was
 * broadcast / written), not on object identity.
 */
class KotlinDslChatDeliveryTest {

    private lateinit var framework: AtmosphereFramework

    @BeforeTest
    fun setUp() {
        framework = AtmosphereFramework().init()
    }

    @AfterTest
    fun tearDown() {
        framework.destroy()
        DemoAgentRuntime.setResponseStrategy(null)
    }

    private fun completed(value: Any? = null): Future<Any> =
        @Suppress("UNCHECKED_CAST")
        (CompletableFuture.completedFuture(value) as Future<Any>)

    @Test
    fun `the agent DSL registers a real agent endpoint in the framework`() {
        val assistant = KotlinDslChat.registerAssistant(framework, DemoAgentRuntime())

        // Registered through the framework's own machinery — same path shape
        // and same handler class an @Agent-annotated class produces.
        assertEquals("/atmosphere/agent/kotlin-dsl-chat", assistant.path)
        val wrapper = framework.atmosphereHandlers["/atmosphere/agent/kotlin-dsl-chat"]
        assertNotNull(wrapper, "the DSL agent must be reachable as a framework endpoint")
        assertTrue(wrapper.atmosphereHandler() is AiEndpointHandler)

        // The lambda tool is a first-class registered tool, not decoration.
        assertEquals(listOf("word_count"), assistant.tools.allTools().map { it.name() })
        val counted = assistant.tools.execute("word_count", mapOf("text" to "one two three"))
        assertTrue(counted.success())
        assertEquals("3", counted.result())
    }

    @Test
    fun `DSL endpoint streams the agent reply through the coroutine broadcast extension`() {
        val assistant = KotlinDslChat.registerAssistant(framework, DemoAgentRuntime())
        // Endpoint assembled entirely by the Kotlin transport DSL.
        val handler = KotlinDslChat.chatHandler(assistant)

        val broadcaster = mock<Broadcaster> {
            on { broadcast(any()) } doReturn completed()
        }
        val request = mock<AtmosphereRequest> {
            on { getMethod() } doReturn "POST"
            on { reader } doReturn BufferedReader(StringReader("ping"))
        }
        val resource = mock<AtmosphereResource> {
            on { getRequest() } doReturn request
            on { getBroadcaster() } doReturn broadcaster
            on { uuid() } doReturn "client-1"
        }

        // Drive a real message through the DSL endpoint.
        handler.onRequest(resource)

        // Proof: the message went through the agent's AI pipeline and the
        // resolved runtime answered "pong", and the broadcastSuspend coroutine
        // extension delivered exactly that payload.
        val delivered = argumentCaptor<Any>()
        verify(broadcaster).broadcast(delivered.capture())
        assertEquals("pong", delivered.firstValue)
    }

    @Test
    fun `the agent answers through the AI pipeline with conversation memory`() {
        val assistant = KotlinDslChat.registerAssistant(framework, DemoAgentRuntime())

        val answer = runBlocking { assistant.ask("client-1", "hello") }

        assertEquals("echo: hello", answer)
        // Memory is the framework's conversation memory, fed by the pipeline.
        assertNotNull(assistant.memory)
        assertTrue(assistant.memory!!.getHistory("client-1").isNotEmpty())
    }

    @Test
    fun `coroutine broadcastSuspend awaits delivery and returns the future result`() {
        val broadcaster = mock<Broadcaster> {
            on { broadcast(any()) } doReturn completed("DELIVERED")
        }

        // Calling the suspend extension proves the coroutine machinery runs:
        // it awaits broadcast(...).get() and surfaces the resolved value.
        val result = runBlocking { broadcaster.broadcastSuspend("hello room") }

        verify(broadcaster).broadcast("hello room")
        assertEquals("DELIVERED", result)
    }

    @Test
    fun `coroutine writeSuspend writes the payload to the resource`() {
        val resource = mock<AtmosphereResource>()
        whenever(resource.write(any<String>())).thenReturn(resource)

        val returned = runBlocking { resource.writeSuspend("streamed from a coroutine") }

        // Observable effect: the bytes were written, and the extension returns
        // the same resource for chaining.
        verify(resource).write("streamed from a coroutine")
        assertSame(resource, returned)
    }

    @Test
    fun `the offline reply strategy is reproducible`() {
        assertEquals("pong", KotlinDslChat.offlineReply("ping"))
        assertEquals("echo: hi", KotlinDslChat.offlineReply("hi"))
        assertEquals(
            "You asked: \"are you online?\" — here is a deterministic answer.",
            KotlinDslChat.offlineReply("are you online?")
        )
        assertEquals("Say something and I'll reply.", KotlinDslChat.offlineReply(null))
    }
}
