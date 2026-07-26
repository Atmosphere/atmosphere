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
package org.atmosphere.kotlin.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.AgentRuntime
import org.atmosphere.ai.AgentRuntimeResolver
import org.atmosphere.ai.AiCapability
import org.atmosphere.ai.AiConfig
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.llm.DemoAgentRuntime
import org.atmosphere.ai.processor.AiEndpointHandler
import org.atmosphere.cpr.AtmosphereFramework
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Delivery tests for the Kotlin `agent { }` DSL.
 *
 * The DSL is only worth shipping if it is a front-end onto the real AI layer
 * rather than a second, look-alike stack. These tests pin exactly that:
 * a DSL agent lands in the framework's own handler registry as the same
 * [AiEndpointHandler] the `@Agent` / `@AiEndpoint` annotation processors
 * register, its tools land in the framework's [org.atmosphere.ai.tool.ToolRegistry],
 * and a message round-trips through a real [AgentRuntime] — with the coroutine
 * flow surfacing the runtime's individual deltas.
 */
class AgentDslTest {

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

    // ── declaration ──

    @Test
    fun `agent builder captures the declaration`() {
        val spec = agent("support") {
            systemPrompt = "You are a support assistant."
            model = "test-model"
            maxHistory = 8
            tool("order_status", "Look up an order") {
                param("orderId", "The order identifier")
                execute { args -> "shipped:${args["orderId"]}" }
            }
        }

        assertEquals("support", spec.name)
        assertEquals("/atmosphere/agent/support", spec.path)
        assertEquals("You are a support assistant.", spec.systemPrompt)
        assertEquals("test-model", spec.model)
        assertEquals(8, spec.maxHistory)
        assertTrue(spec.memoryEnabled)
        assertEquals(listOf("order_status"), spec.tools.map { it.name() })
        assertEquals(listOf("orderId"), spec.tools[0].parameters().map { it.name() })
    }

    @Test
    fun `a tool without a body is rejected at declaration time`() {
        val failure = assertFailsWith<IllegalStateException> {
            agent("broken") {
                tool("noop", "Does nothing at all") {
                    param("x", "unused")
                }
            }
        }
        assertContains(failure.message ?: "", "execute")
    }

    @Test
    fun `duplicate tool names are rejected at declaration time`() {
        assertFailsWith<IllegalArgumentException> {
            agent("dupes") {
                tool("same", "First") { execute { "a" } }
                tool("same", "Second") { execute { "b" } }
            }
        }
    }

    // ── registration lands in the framework's own registries ──

    @Test
    fun `registration installs an AiEndpointHandler in the framework handler registry`() {
        val declared = agent("kdsl-registry") {
            systemPrompt = "hello"
            runtime = ScriptedRuntime("ok")
        }
        val live = framework.registerAgent(declared)

        // The same registry, the same path shape, and the same handler class
        // the @Agent annotation processor registers.
        val wrapper = framework.atmosphereHandlers["/atmosphere/agent/kdsl-registry"]
        assertNotNull(wrapper, "the DSL agent must be registered in the framework handler registry")
        assertTrue(
            wrapper.atmosphereHandler() is AiEndpointHandler,
            "a DSL agent must ride the framework's AiEndpointHandler, not a bespoke handler"
        )
        assertEquals(live.handler, wrapper.atmosphereHandler())
        assertEquals("/atmosphere/agent/kdsl-registry", live.path)
    }

    @Test
    fun `declared tools land in the agent tool registry`() {
        val live = framework.registerAgent("kdsl-tools") {
            runtime = ScriptedRuntime("ok")
            tool("word_count", "Count the words in a sentence") {
                param("text", "The sentence to measure")
                execute { args -> (args["text"] as String).split(" ").size }
            }
        }

        assertEquals(listOf("word_count"), live.tools.allTools().map { it.name() })
        val result = live.tools.execute("word_count", mapOf("text" to "one two three"))
        assertTrue(result.success(), "the lambda body must be the registered executor")
        assertEquals("3", result.result())
    }

    @Test
    fun `memory can be switched off`() {
        val live = framework.registerAgent("kdsl-nomemory") {
            runtime = ScriptedRuntime("ok")
            memory = false
        }
        assertNull(live.memory)
    }

    @Test
    fun `the resolver picks the runtime when the DSL does not bind one`() {
        val live = framework.registerAgent("kdsl-resolved") { systemPrompt = "hi" }
        assertEquals(
            AgentRuntimeResolver.resolve().name(), live.runtime.name(),
            "an unbound DSL agent must use the same runtime resolution as the annotation path"
        )
    }

    // ── round trip ──

    @Test
    fun `a DSL agent round-trips a message through the demo runtime`() {
        // The framework's own offline fallback runtime — the one samples run on
        // without an API key.
        DemoAgentRuntime.setResponseStrategy { ctx -> "echo: ${ctx.message()}" }
        val live = framework.registerAgent("kdsl-demo") {
            systemPrompt = "You are terse."
            runtime = DemoAgentRuntime()
        }

        val answer = runBlocking { live.ask("client-1", "ping") }

        assertEquals("echo: ping", answer)
    }

    @Test
    fun `the conversation is remembered across turns`() {
        DemoAgentRuntime.setResponseStrategy { ctx -> "seen ${ctx.history().size} prior message(s)" }
        val live = framework.registerAgent("kdsl-memory") {
            runtime = DemoAgentRuntime()
            maxHistory = 10
        }

        runBlocking {
            assertEquals("seen 0 prior message(s)", live.ask("client-1", "first"))
            // Memory is the framework's InMemoryConversationMemory, fed by the
            // pipeline — the second turn sees the first exchange.
            assertTrue(live.ask("client-1", "second").startsWith("seen "))
        }
        assertNotNull(live.memory)
        assertTrue(live.memory!!.getHistory("client-1").isNotEmpty())
    }

    @Test
    fun `coroutine streaming collects the runtime deltas`() {
        val live = framework.registerAgent("kdsl-stream") {
            runtime = ScriptedRuntime("alpha ", "beta ", "gamma")
        }

        val deltas = runBlocking { live.stream("client-1", "go").toList() }

        assertEquals(listOf("alpha ", "beta ", "gamma"), deltas)
        assertEquals("alpha beta gamma", deltas.joinToString(""))
    }

    @Test
    fun `a runtime failure surfaces to the collector`() {
        val live = framework.registerAgent("kdsl-error") {
            runtime = FailingRuntime()
        }

        val failure = assertFailsWith<IllegalStateException> {
            runBlocking { live.ask("client-1", "go") }
        }
        assertContains(failure.message ?: "", "runtime exploded")
    }

    // ── fixtures ──

    /** Streams a fixed sequence of deltas, one `send` per element. */
    private class ScriptedRuntime(private vararg val deltas: String) : AgentRuntime {
        override fun name() = "scripted"
        override fun isAvailable() = true
        override fun priority() = 0
        override fun configure(settings: AiConfig.LlmSettings?) = Unit
        override fun capabilities() = setOf(AiCapability.TEXT_STREAMING, AiCapability.SYSTEM_PROMPT)
        override fun execute(context: AgentExecutionContext, session: StreamingSession) {
            deltas.forEach { session.send(it) }
            session.complete()
        }
    }

    /** Fails the session the way a provider error does. */
    private class FailingRuntime : AgentRuntime {
        override fun name() = "failing"
        override fun isAvailable() = true
        override fun priority() = 0
        override fun configure(settings: AiConfig.LlmSettings?) = Unit
        override fun capabilities() = setOf(AiCapability.TEXT_STREAMING)
        override fun execute(context: AgentExecutionContext, session: StreamingSession) {
            session.error(IllegalStateException("runtime exploded"))
        }
    }
}
