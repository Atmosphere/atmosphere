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

import jakarta.servlet.ServletConfig
import jakarta.servlet.ServletContext
import org.atmosphere.cache.UUIDBroadcasterCache
import org.atmosphere.cpr.Action
import org.atmosphere.cpr.ApplicationConfig
import org.atmosphere.cpr.AsynchronousProcessor
import org.atmosphere.cpr.AtmosphereFramework
import org.atmosphere.cpr.AtmosphereRequest
import org.atmosphere.cpr.AtmosphereRequestImpl
import org.atmosphere.cpr.AtmosphereResponse
import org.atmosphere.cpr.AtmosphereResponseImpl
import org.atmosphere.cpr.Broadcaster
import org.atmosphere.cpr.HeaderConfig
import org.atmosphere.cpr.WebSocketProcessorFactory
import org.atmosphere.websocket.WebSocket
import org.mockito.kotlin.mock
import java.util.Collections
import java.util.Enumeration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A DSL endpoint behind a [UUIDBroadcasterCache], driven over WebSocket: a
 * client that closes, misses broadcasts and reconnects under its tracking id
 * gets each missed message as a frame of its own.
 */
class DslCacheReplayIntegrationTest {

    private val framework = AtmosphereFramework()

    @AfterTest
    fun tearDown() {
        framework.destroy()
    }

    private fun start() {
        framework.addInitParameter(ApplicationConfig.BROADCASTER_CACHE, UUIDBroadcasterCache::class.java.name)
        framework.addInitParameter(ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE, "false")
        framework.setAsyncSupport(object : AsynchronousProcessor(framework.atmosphereConfig) {
            override fun service(req: AtmosphereRequest, res: AtmosphereResponse): Action = suspended(req, res)
        })
        framework.init(object : ServletConfig {
            override fun getServletName() = "dsl"
            override fun getServletContext(): ServletContext = mock()
            override fun getInitParameter(name: String?): String? = null
            override fun getInitParameterNames(): Enumeration<String> = Collections.emptyEnumeration()
        })
        framework.addAtmosphereHandler("/chat", atmosphere { })
    }

    private fun open(trackingId: String): FrameRecorder {
        val processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework)
        val webSocket = FrameRecorder(framework)
        val request: AtmosphereRequest = AtmosphereRequestImpl.Builder()
            .destroyable(false)
            .pathInfo("/chat")
            .headers(mapOf(HeaderConfig.X_ATMOSPHERE_TRACKING_ID to trackingId))
            .build()
        processor.open(webSocket, request,
            AtmosphereResponseImpl.newInstance(framework.atmosphereConfig, request, webSocket))
        assertEquals(trackingId, webSocket.resource()?.uuid(), "the connection keeps the client's tracking id")
        return webSocket
    }

    private fun close(webSocket: WebSocket) {
        WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework).close(webSocket, 1000)
    }

    private fun chat(): Broadcaster = framework.broadcasterFactory.lookup("/chat", false)

    @Test
    fun `each message a reconnecting client missed arrives as a frame of its own`() {
        start()
        val trackingId = UUID.randomUUID().toString()

        close(open(trackingId))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (chat().atmosphereResources.isNotEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(chat().atmosphereResources.isEmpty(), "the closed client left /chat")

        chat().broadcast("echo: missed-1").get(5, TimeUnit.SECONDS)
        chat().broadcast("echo: missed-2").get(5, TimeUnit.SECONDS)

        val reconnected = open(trackingId)

        assertEquals(listOf("echo: missed-1", "echo: missed-2"), reconnected.frames.toList(),
            "the cached messages are replayed one frame each, never as one List.toString() frame")
    }

    /** A WebSocket that records each frame written to it, but the whitespace padding written on connect. */
    private class FrameRecorder(framework: AtmosphereFramework) : WebSocket(framework.atmosphereConfig) {

        val frames: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun isOpen() = true

        override fun write(s: String): WebSocket {
            if (s.isNotBlank()) frames.add(s)
            return this
        }

        override fun write(b: ByteArray, offset: Int, length: Int): WebSocket {
            String(b, offset, length, Charsets.UTF_8).takeIf { it.isNotBlank() }?.let(frames::add)
            return this
        }

        override fun close() {
        }
    }
}
