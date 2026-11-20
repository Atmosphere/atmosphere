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
package org.atmosphere.ai.koog

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.atmosphere.ai.AiEnvironment
import org.atmosphere.ai.test.StallingHttpServer
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Ktor client behind [AtmosphereOpenAILLMClient] is Atmosphere's: the
 * client closes it, and the auto-configuration, which built the executor,
 * closes that executor when its context closes (Correctness Invariant #1). A
 * turn's read timeout reaches each of its calls as the socket timeout, the
 * transport's backstop behind the turn's watchdog.
 */
class KoogTransportTest {

    private val template = OpenAIModels.Chat.GPT4o
    private val model = LLModel(LLMProvider.OpenAI, "gpt-4o-mini", template.capabilities,
        template.contextLength, template.maxOutputTokens)

    private fun stream(executor: PromptExecutor): List<StreamFrame> = runBlocking {
        executor.executeStreaming(prompt("ownership") { user("Hello") }, model).toList()
    }

    @Test
    fun closingTheClientClosesTheTransportItCreated() {
        StallingHttpServer(StallingHttpServer.Mode.answer(StallingHttpServer.Dialect.OPENAI)).use { server ->
            val client = AtmosphereOpenAILLMClient("k", OpenAIClientSettings(
                baseUrl = server.baseUrl() + "/v1", chatCompletionsPath = "chat/completions"))
            val executor = ai.koog.prompt.executor.llms.MultiLLMPromptExecutor(client)
            assertTrue(stream(executor).any { it is StreamFrame.TextDelta }, "the open client streams")
            val engine = assertNotNull(client.transportJob())
            assertFalse(engine.isCompleted, "the client's engine is open while the client is")
            client.close()
            // An engine's close completes its job once its connections are gone.
            runBlocking { kotlinx.coroutines.withTimeoutOrNull(10_000) { engine.join() } }
            assertTrue(engine.isCompleted, "closing the client closes the engine it created")
            assertFailsWith<Exception>("a closed client's transport takes no call") { stream(executor) }
        }
    }

    @Test
    fun theAutoConfigurationClosesTheExecutorItBuilt() {
        StallingHttpServer(StallingHttpServer.Mode.answer(StallingHttpServer.Dialect.OPENAI)).use { server ->
            val environment = AiEnvironment.create { null }
            try {
                val provider = mock<ObjectProvider<AiEnvironment>> { on { getIfAvailable() } doReturn environment }
                val configuration = AtmosphereKoogAutoConfiguration()
                configuration.koogAgentRuntime("gpt-4o-mini", "", server.baseUrl() + "/v1", "local", provider)
                val executor = assertNotNull(
                    environment.client(KoogAgentRuntime::class.java, PromptExecutor::class.java, null))
                assertTrue(stream(executor).any { it is StreamFrame.TextDelta }, "the built executor streams")
                configuration.destroy()
                assertFailsWith<Exception>("the context's close closed the executor it built") { stream(executor) }
            } finally {
                environment.close()
            }
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(60)
    fun aTurnsReadTimeoutEndsASilentCallAndClosesItsConnection() {
        StallingHttpServer(StallingHttpServer.Mode.stallAfterChunks(1, StallingHttpServer.Dialect.OPENAI)).use { server ->
            AtmosphereOpenAILLMClient("k", OpenAIClientSettings(
                baseUrl = server.baseUrl() + "/v1", chatCompletionsPath = "chat/completions")).use { client ->
                val started = System.nanoTime()
                assertFailsWith<Exception>("a call silent past its read timeout fails") {
                    runBlocking(KoogTurnTransport(null, java.time.Duration.ofMillis(300))) {
                        client.executeStreaming(prompt("timeout") { user("Hello") }, model).toList()
                    }
                }
                val elapsed = java.time.Duration.ofNanos(System.nanoTime() - started)
                // Koog's own socket timeout is far longer than this bound.
                assertTrue(elapsed < java.time.Duration.ofSeconds(30), "the read timeout ended the call: $elapsed")
                assertTrue(server.clientClosed(java.time.Duration.ofSeconds(3)), "the timed-out call's connection closed")
            }
        }
    }
}
