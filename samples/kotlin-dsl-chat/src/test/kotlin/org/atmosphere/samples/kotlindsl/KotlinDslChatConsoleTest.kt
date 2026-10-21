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

import org.atmosphere.ai.llm.DemoAgentRuntime
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sample's UI is the Atmosphere Console, served by its own Jetty: `/`
 * redirects to it keeping the query string (GET and HEAD), the bundle is
 * served at `/atmosphere/console/`, and `/api/console/info` points it at the
 * broadcast chat on `/chat`. The favicon is served too.
 */
class KotlinDslChatConsoleTest {

    private lateinit var server: Server
    private lateinit var base: String
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    @BeforeTest
    fun start() {
        server = KotlinDslChat.createServer(0)
        server.start()
        base = "http://localhost:" + (server.connectors[0] as ServerConnector).localPort
    }

    @AfterTest
    fun stop() {
        server.stop()
        DemoAgentRuntime.setResponseStrategy(null)
    }

    private fun send(method: String, path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create(base + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

    @Test
    fun `the root redirects to the Console and keeps the query string, for GET and HEAD`() {
        for (method in listOf("GET", "HEAD")) {
            val withToken = send(method, "/?token=x")
            assertEquals(302, withToken.statusCode(), method)
            assertEquals("/atmosphere/console/?token=x", withToken.headers().firstValue("Location").orElse(""),
                method)

            val bare = send(method, "/")
            assertEquals(302, bare.statusCode(), method)
            assertEquals("/atmosphere/console/", bare.headers().firstValue("Location").orElse(""), method)
        }
    }

    @Test
    fun `the Console bundle is served`() {
        val page = send("GET", "/atmosphere/console/")
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("Atmosphere AI Console"), page.body().take(300))

        val asset = Regex("/atmosphere/console/assets/[^\"]+\\.js").find(page.body())?.value
        assertTrue(asset != null, "the page loads its script")
        assertEquals(200, send("GET", asset).statusCode())
    }

    @Test
    fun `the favicon is served`() {
        val icon = send("GET", "/favicon.ico")
        assertEquals(200, icon.statusCode())
        assertTrue(icon.body().isNotEmpty(), "the icon has content")
    }

    @Test
    fun `the Console is pointed at the broadcast chat`() {
        val info = send("GET", "/api/console/info")
        assertEquals(200, info.statusCode())
        assertTrue(info.body().contains("\"endpoint\":\"/chat\""), info.body())
        assertTrue(info.body().contains("\"mode\":\"broadcast\""), info.body())
        assertTrue(info.body().contains("\"transport\":\"atmosphere\""), info.body())
    }
}
