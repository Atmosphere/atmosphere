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
import org.atmosphere.ai.AgentRuntime
import org.atmosphere.ai.llm.DemoAgentRuntime
import org.atmosphere.cpr.ApplicationConfig
import org.atmosphere.cpr.AtmosphereFramework
import org.atmosphere.cpr.AtmosphereHandler
import org.atmosphere.cpr.AtmosphereServlet
import org.atmosphere.kotlin.ai.KotlinAgent
import org.atmosphere.kotlin.ai.registerAgent
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
 * Two Kotlin DSLs, one app:
 *
 * * the **transport DSL** ([atmosphere] `{ ... }`) assembles the `/chat`
 *   endpoint, and every delivery goes through the **coroutine extension**
 *   [broadcastSuspend], which suspends until the broadcast has actually been
 *   written to the connected clients;
 * * the **agent DSL** ([registerAgent] `{ ... }`) declares a real Atmosphere
 *   agent — system prompt, conversation memory, a tool as a lambda — and
 *   registers it through the framework's own machinery, so it lands at
 *   `/atmosphere/agent/kotlin-dsl-chat` on the same `AiEndpointHandler` an
 *   `@Agent`-annotated class would produce.
 *
 * Replies come from the agent's resolved `AgentRuntime`. With no API key
 * configured that is the framework's built-in `DemoAgentRuntime`, and this
 * sample installs a deterministic response strategy on it — so the app runs
 * fully offline and reproducibly while still traversing the complete AI
 * pipeline (memory, guardrails, metrics, streaming frames). Configure a
 * provider (see the README) and the resolver hands the same agent to that
 * runtime instead; nothing else in this file changes.
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

    /** Agent name — also its endpoint: `/atmosphere/agent/kotlin-dsl-chat`. */
    const val AGENT_NAME = "kotlin-dsl-chat"

    /**
     * Declare and register the chat agent with the Kotlin agent DSL.
     *
     * The deterministic strategy installed here belongs to the framework's
     * offline `DemoAgentRuntime` — the runtime the resolver selects when no API
     * key is configured. It is what keeps this sample (and its delivery test)
     * reproducible without a network; the moment a real provider is configured
     * the resolver stops selecting the demo runtime and the strategy is never
     * consulted.
     *
     * @param runtime pins the agent to one runtime. Leave `null` (what `main`
     *                does) to let `AgentRuntimeResolver` choose — demo runtime
     *                offline, your provider once one is configured. The
     *                delivery test pins the demo runtime so it never depends on
     *                the developer's environment.
     */
    fun registerAssistant(framework: AtmosphereFramework, runtime: AgentRuntime? = null): KotlinAgent {
        DemoAgentRuntime.setResponseStrategy { context -> offlineReply(context.message()) }

        val pinnedRuntime = runtime
        val assistant = framework.registerAgent(AGENT_NAME) {
            this.runtime = pinnedRuntime
            systemPrompt = """
                You are the Atmosphere Kotlin DSL demo assistant.
                Answer in one short sentence. Reply to "ping" with exactly "pong".
            """.trimIndent()
            maxHistory = 20

            // A tool declared as a lambda. It is registered in the agent's
            // ToolRegistry exactly like an @AiTool method, so a tool-calling
            // runtime can invoke it. The offline demo runtime does not do tool
            // calling, so it stays unused until you configure a provider.
            tool("word_count", "Count the words in a sentence") {
                param("text", "The sentence to measure")
                execute { args ->
                    (args["text"] as? String).orEmpty()
                        .split(Regex("\\s+"))
                        .count { it.isNotBlank() }
                }
            }
        }

        log.info(
            "Chat agent '{}' ready at {} (runtime: {})",
            assistant.name, assistant.path, assistant.runtime.name()
        )
        return assistant
    }

    /**
     * Deterministic offline replies. Pure and side-effect free so the same
     * input always yields the same output.
     */
    fun offlineReply(message: String?): String {
        val text = message?.trim().orEmpty()
        return when {
            text.isEmpty() -> "Say something and I'll reply."
            text.equals("ping", ignoreCase = true) -> "pong"
            text.endsWith("?") -> "You asked: \"$text\" — here is a deterministic answer."
            else -> "echo: $text"
        }
    }

    /**
     * Builds the chat [AtmosphereHandler] entirely with the Kotlin transport DSL.
     *
     * Each lifecycle callback hands its delivery to the suspending
     * [broadcastSuspend] coroutine extension, so the broadcast is awaited
     * (not fire-and-forget) before the callback returns. Inbound messages are
     * answered by the DSL-declared agent through its suspending
     * [KotlinAgent.ask], which drives the framework's AI pipeline.
     */
    fun chatHandler(assistant: KotlinAgent): AtmosphereHandler = atmosphere {
        onConnect { resource ->
            runBlocking {
                resource.broadcaster.broadcastSuspend("${resource.uuid()} joined")
            }
        }
        onMessage { resource, message ->
            runBlocking {
                // Per-connection conversation key: the agent's memory keeps
                // each client's turns apart.
                val answer = assistant.ask(resource.uuid() ?: "anonymous", message)
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
        // Honor the SERVER_PORT env var (the convention the Spring Boot samples use),
        // then the -Dserver.port system property, then the default.
        val port = System.getenv("SERVER_PORT")?.trim()?.toIntOrNull()
            ?: Integer.getInteger("server.port", 8099)

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

        // Register both DSL-built pieces programmatically: there is no annotation
        // scanning here — the agent comes from registerAssistant() and the
        // endpoint from chatHandler().
        val servlet = object : AtmosphereServlet() {
            @Throws(ServletException::class)
            override fun configureFramework(sc: ServletConfig, init: Boolean): AtmosphereServlet {
                super.configureFramework(sc, init)
                val assistant = registerAssistant(framework())
                framework().addAtmosphereHandler("/chat", chatHandler(assistant))
                return this
            }
        }

        val holder = ServletHolder(servlet)
        holder.setInitParameter(ApplicationConfig.WEBSOCKET_SUPPORT, "true")
        holder.setAsyncSupported(true)
        holder.setInitOrder(1)
        // Map the servlet at the context root so BOTH DSL-registered endpoints
        // are routable: the transport DSL's /chat and the agent DSL's
        // /atmosphere/agent/kotlin-dsl-chat.
        context.addServlet(holder, "/*")

        server.handler = context
        server.start()
        log.info("Kotlin DSL chat started on http://localhost:{}/chat", port)
        log.info("Subscribe: curl -N http://localhost:{}/chat   |   Send: curl -d 'ping' http://localhost:{}/chat", port, port)
        server.join()
    }
}
