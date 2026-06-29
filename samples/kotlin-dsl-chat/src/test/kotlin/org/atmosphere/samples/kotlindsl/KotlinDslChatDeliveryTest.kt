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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Delivery proof for the Kotlin DSL + coroutine extensions that [KotlinDslChat]
 * is built on.
 *
 * These tests exercise real behavior: a message is driven through the
 * DSL-built handler, the deterministic agent computes a reply, and the
 * suspending coroutine extension actually delivers it. The assertions are on
 * the observable effect (what was broadcast / written), not on object identity.
 */
class KotlinDslChatDeliveryTest {

    private fun completed(value: Any? = null): Future<Any> =
        @Suppress("UNCHECKED_CAST")
        (CompletableFuture.completedFuture(value) as Future<Any>)

    @Test
    fun `DSL endpoint streams the agent reply through the coroutine broadcast extension`() {
        // Endpoint assembled entirely by the Kotlin DSL.
        val handler = KotlinDslChat.chatHandler()

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
        }

        // Drive a real message through the DSL endpoint.
        handler.onRequest(resource)

        // Proof: the deterministic agent turned "ping" into "pong" and the
        // broadcastSuspend coroutine extension delivered exactly that payload.
        val delivered = argumentCaptor<Any>()
        verify(broadcaster).broadcast(delivered.capture())
        assertEquals("pong", delivered.firstValue)
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
    fun `deterministic agent is reproducible offline`() {
        val agent = DeterministicAgent()
        assertEquals("pong", agent.reply("ping"))
        assertEquals("echo: hi", agent.reply("hi"))
        assertEquals(
            "You asked: \"are you online?\" — here is a deterministic answer.",
            agent.reply("are you online?")
        )
    }
}
