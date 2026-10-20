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
import org.atmosphere.cache.UUIDBroadcasterCache
import org.atmosphere.client.TrackMessageSizeInterceptor
import org.atmosphere.cpr.ApplicationConfig
import org.atmosphere.cpr.AtmosphereFramework
import org.atmosphere.cpr.AtmosphereHandler
import org.atmosphere.cpr.AtmosphereServlet
import org.atmosphere.kotlin.ai.KotlinAgent
import org.atmosphere.kotlin.ai.registerAgent
import org.atmosphere.kotlin.atmosphere
import org.atmosphere.kotlin.broadcastSuspend
import org.eclipse.jetty.ee10.servlet.ResourceServlet
import org.eclipse.jetty.ee10.servlet.ServletContextHandler
import org.eclipse.jetty.ee10.servlet.ServletHolder
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.resource.ResourceFactory
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

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
 * # then open the Atmosphere Console (the sample UI): http://localhost:8099/
 * # or POST a message:
 * curl -d 'ping' http://localhost:8099/chat
 * ```
 *
 * `/chat` speaks the Console's broadcast dialect: every frame is a JSON
 * `{author, message, time}` envelope — the sender's message, then the
 * agent's reply — and a message may arrive as that envelope or as plain text.
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
                resource.broadcaster.broadcastSuspend(envelope(SYSTEM_AUTHOR, "${resource.uuid()} joined"))
            }
        }
        onMessage { resource, message ->
            runBlocking {
                val (author, text) = parseMessage(message)
                // Everyone in the chat sees what was said, then the answer.
                resource.broadcaster.broadcastSuspend(envelope(author, text))
                // Per-connection conversation key: the agent's memory keeps
                // each client's turns apart.
                val answer = assistant.ask(resource.uuid() ?: "anonymous", text)
                resource.broadcaster.broadcastSuspend(envelope(AGENT_NAME, answer))
            }
        }
        onDisconnect { resource ->
            runBlocking {
                resource.broadcaster.broadcastSuspend(envelope(SYSTEM_AUTHOR, "${resource.uuid()} left"))
            }
        }
    }

    /** Author of the join/leave notices. */
    const val SYSTEM_AUTHOR = "system"

    private val json = JsonMapper.builder().build()

    /**
     * The chat's wire frame — the Console's broadcast dialect: a JSON
     * `{author, message, time}` envelope.
     */
    fun envelope(author: String, message: String): String =
        json.writeValueAsString(mapOf("author" to author, "message" to message, "time" to System.currentTimeMillis()))

    /**
     * Read an inbound message: the Console's `{author, message}` envelope, or
     * plain text (`curl -d 'ping'`) from an anonymous sender.
     */
    fun parseMessage(raw: String): Pair<String, String> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            try {
                val node = json.readTree(trimmed)
                val message = node.get("message")
                if (message != null && message.isString) {
                    val author = node.get("author")?.takeIf { it.isString }?.stringValue()
                    return (author?.takeIf { it.isNotBlank() } ?: "anonymous") to message.stringValue()
                }
            } catch (e: tools.jackson.core.JacksonException) {
                log.debug("Not a chat envelope, reading it as text: {}", e.message)
            }
        }
        return "anonymous" to raw
    }

    /**
     * Init parameters of the Atmosphere servlet [main] deploys.
     *
     * `/chat` delivers every reply through its Broadcaster. A long-polling
     * client between two polls, or a stream reconnecting after a drop, is not
     * attached when a reply is broadcast; [UUIDBroadcasterCache] keeps it for
     * that client's next connection instead of losing it.
     */
    val SERVLET_INIT_PARAMS: Map<String, String> = mapOf(
        ApplicationConfig.WEBSOCKET_SUPPORT to "true",
        ApplicationConfig.BROADCASTER_CACHE to UUIDBroadcasterCache::class.java.name,
        // Jetty closes a WebSocket that carried nothing for 30 s; a heartbeat
        // inside that window keeps an idle Console connected.
        ApplicationConfig.HEARTBEAT_INTERVAL_IN_SECONDS to "20",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        // Honor the SERVER_PORT env var (the convention the Spring Boot samples use),
        // then the -Dserver.port system property, then the default.
        val port = System.getenv("SERVER_PORT")?.trim()?.toIntOrNull()
            ?: Integer.getInteger("server.port", 8099)

        val server = createServer(port)
        server.start()
        log.info("Kotlin DSL chat started: the Atmosphere Console at http://localhost:{}/ (chat at /chat)", port)
        log.info("Subscribe: curl -N http://localhost:{}/chat   |   Send: curl -d 'ping' http://localhost:{}/chat", port, port)
        server.join()
    }

    /**
     * The sample's Jetty server, not started: the DSL chat at `/chat`, the
     * DSL agent at `/atmosphere/agent/kotlin-dsl-chat`, and the Atmosphere
     * Console (the sample UI, synced by `scripts/sync-console-bundle.sh`) at
     * `/atmosphere/console/`, with `/` redirecting to it.
     *
     * @param port the port to listen on; `0` picks a free one
     */
    fun createServer(port: Int): Server {
        val server = Server()
        val connector = ServerConnector(server)
        connector.port = port
        server.addConnector(connector)

        val context = ServletContextHandler(ServletContextHandler.SESSIONS)
        context.contextPath = "/"
        // A plain ServletContextHandler does not hide WEB-INF/META-INF.
        context.protectedTargets = arrayOf("/WEB-INF", "/META-INF")

        // Provision the jakarta.websocket ServerContainer BEFORE Atmosphere
        // starts: the jetty-ee10-websocket-jakarta-server dependency alone does
        // nothing in embedded Jetty, and without the container Atmosphere's
        // JSR356 support cannot deploy — WebSocket upgrades then fail with 501.
        JakartaWebSocketServletContainerInitializer.configure(context, null)

        // The Console bundle rides in the jar under webapp/.
        val webapp = KotlinDslChat::class.java.classLoader.getResource("webapp/")
            ?: throw IllegalStateException("No webapp/ on the classpath: the Console bundle is missing")
        context.baseResource = ResourceFactory.of(server).newResource(webapp.toURI())
        val console = ServletHolder("console", ResourceServlet::class.java)
        console.setInitParameter("dirAllowed", "false")
        // Resolve the full request path (atmosphere/console/assets/...) against webapp/.
        console.setInitParameter("pathInfoOnly", "false")
        context.addServlet(console, "/atmosphere/console/*")
        // The directory URL itself is the Console's page.
        context.addServlet(ServletHolder("console-index", ConsoleIndexServlet::class.java), "/atmosphere/console/")
        context.addServlet(ServletHolder("console-info", ConsoleInfoServlet::class.java), "/api/console/info")
        // "" is the context root alone: / goes to the Console, query kept.
        context.addServlet(ServletHolder("root", ConsoleRootRedirectServlet::class.java), "")

        // Register both DSL-built pieces programmatically: there is no annotation
        // scanning here — the agent comes from registerAssistant() and the
        // endpoint from chatHandler().
        val servlet = object : AtmosphereServlet() {
            @Throws(ServletException::class)
            override fun configureFramework(sc: ServletConfig, init: Boolean): AtmosphereServlet {
                super.configureFramework(sc, init)
                val assistant = registerAssistant(framework())
                // The Console reads every frame as <length>|<message>.
                framework().addAtmosphereHandler("/chat", chatHandler(assistant),
                    listOf(TrackMessageSizeInterceptor()))
                return this
            }
        }

        val holder = ServletHolder(servlet)
        SERVLET_INIT_PARAMS.forEach { (name, value) -> holder.setInitParameter(name, value) }
        holder.setAsyncSupported(true)
        holder.setInitOrder(1)
        // Map the servlet at the context root so BOTH DSL-registered endpoints
        // are routable: the transport DSL's /chat and the agent DSL's
        // /atmosphere/agent/kotlin-dsl-chat. The Console paths above are more
        // specific and win.
        context.addServlet(holder, "/*")

        server.handler = context
        return server
    }
}
