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

import jakarta.servlet.ServletConfig
import jakarta.servlet.ServletException
import kotlinx.coroutines.runBlocking
import org.atmosphere.cpr.ApplicationConfig
import org.atmosphere.cpr.AtmosphereHandler
import org.atmosphere.cpr.AtmosphereServlet
import org.atmosphere.kotlin.atmosphere
import org.atmosphere.kotlin.broadcastSuspend
import org.eclipse.jetty.ee10.servlet.ServletContextHandler
import org.eclipse.jetty.ee10.servlet.ServletHolder
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.slf4j.LoggerFactory

/**
 * Kotlin-first Atmosphere chat.
 *
 * The whole endpoint is assembled through the Atmosphere **Kotlin DSL**
 * ([atmosphere] `{ ... }`) and every delivery goes through a **coroutine
 * extension** ([broadcastSuspend]) that suspends until the broadcast has
 * actually been written to the connected clients.
 *
 * A [DeterministicAgent] produces the replies so the sample runs fully offline
 * (no API key, no network) and is reproducible end to end.
 *
 * Run it:
 * ```
 * ./mvnw -q -pl samples/kotlin-dsl-chat -am package -DskipTests
 * java -jar samples/kotlin-dsl-chat/target/atmosphere-kotlin-dsl-chat-*.jar
 * # then POST a message:
 * curl -d 'ping' http://localhost:8099/chat
 * ```
 */
object KotlinDslChat {

    private val log = LoggerFactory.getLogger(KotlinDslChat::class.java)
    private val agent = DeterministicAgent()

    /**
     * Builds the chat [AtmosphereHandler] entirely with the Kotlin DSL.
     *
     * Each lifecycle callback hands its delivery to the suspending
     * [broadcastSuspend] coroutine extension, so the broadcast is awaited
     * (not fire-and-forget) before the callback returns.
     */
    fun chatHandler(): AtmosphereHandler = atmosphere {
        onConnect { resource ->
            runBlocking {
                resource.broadcaster.broadcastSuspend("${resource.uuid()} joined")
            }
        }
        onMessage { resource, message ->
            val answer = agent.reply(message)
            runBlocking {
                resource.broadcaster.broadcastSuspend(answer)
            }
        }
        onDisconnect { resource ->
            runBlocking {
                resource.broadcaster.broadcastSuspend("${resource.uuid()} left")
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val port = Integer.getInteger("server.port", 8099)

        val server = Server()
        val connector = ServerConnector(server)
        connector.port = port
        server.addConnector(connector)

        val context = ServletContextHandler(ServletContextHandler.SESSIONS)
        context.contextPath = "/"

        // Provision the jakarta.websocket ServerContainer BEFORE Atmosphere
        // starts: the jetty-ee10-websocket-jakarta-server dependency alone does
        // nothing in embedded Jetty, and without the container Atmosphere's
        // JSR356 support cannot deploy — WebSocket upgrades then fail with 501.
        JakartaWebSocketServletContainerInitializer.configure(context, null)

        // Register the DSL-built handler programmatically: there is no annotation
        // scanning here — the endpoint is created by code in chatHandler().
        val servlet = object : AtmosphereServlet() {
            @Throws(ServletException::class)
            override fun configureFramework(sc: ServletConfig, init: Boolean): AtmosphereServlet {
                super.configureFramework(sc, init)
                framework().addAtmosphereHandler("/chat", chatHandler())
                return this
            }
        }

        val holder = ServletHolder(servlet)
        holder.setInitParameter(ApplicationConfig.WEBSOCKET_SUPPORT, "true")
        holder.setAsyncSupported(true)
        holder.setInitOrder(1)
        context.addServlet(holder, "/chat/*")

        server.handler = context
        server.start()
        log.info("Kotlin DSL chat started on http://localhost:{}/chat", port)
        log.info("Subscribe: curl -N http://localhost:{}/chat   |   Send: curl -d 'ping' http://localhost:{}/chat", port, port)
        server.join()
    }
}
