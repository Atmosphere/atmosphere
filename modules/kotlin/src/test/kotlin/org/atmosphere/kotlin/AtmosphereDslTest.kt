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
package org.atmosphere.kotlin

import org.atmosphere.cpr.AtmosphereResource
import org.atmosphere.cpr.AtmosphereResourceEvent
import org.atmosphere.cpr.Broadcaster
import org.mockito.kotlin.*
import java.io.BufferedReader
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AtmosphereDslTest {

    private fun mockResource(method: String = "GET", body: String = ""): AtmosphereResource {
        val request = mock<org.atmosphere.cpr.AtmosphereRequest> {
            on { getMethod() } doReturn method
            on { reader } doReturn BufferedReader(StringReader(body))
        }
        val broadcaster = mock<Broadcaster>()
        return mock<AtmosphereResource> {
            on { getRequest() } doReturn request
            on { getBroadcaster() } doReturn broadcaster
        }
    }

    @Test
    fun `atmosphere DSL creates handler`() {
        val handler = atmosphere {
            onConnect { }
            onMessage { _, _ -> }
        }
        assertNotNull(handler)
    }

    @Test
    fun `onConnect is called on GET request`() {
        var connected = false
        val handler = atmosphere {
            onConnect { connected = true }
        }

        val resource = mockResource("GET")
        handler.onRequest(resource)

        assertEquals(true, connected)
        verify(resource).suspend()
    }

    @Test
    fun `onMessage is called on POST with body`() {
        var received: String? = null
        val handler = atmosphere {
            onMessage { _, msg -> received = msg }
        }

        val resource = mockResource("POST", "hello world")
        handler.onRequest(resource)

        assertEquals("hello world", received)
    }

    @Test
    fun `onDisconnect is called when closed by client`() {
        var disconnected = false
        val handler = atmosphere {
            onDisconnect { disconnected = true }
        }

        val resource = mockResource()
        val event = mock<AtmosphereResourceEvent> {
            on { isClosedByClient } doReturn true
            on { isClosedByApplication } doReturn false
            on { isResumedOnTimeout } doReturn false
            on { isResuming } doReturn false
            on { getResource() } doReturn resource
        }

        handler.onStateChange(event)

        assertEquals(true, disconnected)
    }

    @Test
    fun `onTimeout is called on timeout`() {
        var timedOut = false
        val handler = atmosphere {
            onTimeout { timedOut = true }
        }

        val resource = mockResource()
        val event = mock<AtmosphereResourceEvent> {
            on { isClosedByClient } doReturn false
            on { isClosedByApplication } doReturn false
            on { isResumedOnTimeout } doReturn true
            on { isResuming } doReturn false
            on { getResource() } doReturn resource
        }

        handler.onStateChange(event)

        assertEquals(true, timedOut)
    }

    @Test
    fun `broadcast message is written on state change`() {
        val handler = atmosphere { }

        val resource = mockResource()
        val event = mock<AtmosphereResourceEvent> {
            on { isClosedByClient } doReturn false
            on { isClosedByApplication } doReturn false
            on { isResumedOnTimeout } doReturn false
            on { isResuming } doReturn false
            on { message } doReturn "broadcast msg"
            on { getResource() } doReturn resource
        }

        handler.onStateChange(event)

        verify(resource).write("broadcast msg")
    }

    @Test
    fun `handler destroy is no-op`() {
        val handler = atmosphere { }
        handler.destroy() // should not throw
    }
}
