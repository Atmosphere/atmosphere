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

import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the shape of `tool_calls[].function.arguments` that leaves the Koog
 * runtime on the OpenAI wire.
 *
 * The OpenAI chat-completions format requires `arguments` to be a JSON **string
 * containing an object**. Koog 1.0.0's `AbstractOpenAILLMClient` re-encodes the
 * already-serialised `MessagePart.Tool.Call.args` through `String.serializer()`
 * when it rebuilds the request, so the second turn after any tool call ships a
 * JSON string containing a JSON *string*. Ollama's `/v1` surface answers
 * `400 {"error":{"message":"invalid tool call arguments"}}` and the whole agent
 * loop dies — observed on `samples/spring-boot-multi-agent-startup-team`.
 *
 * [AtmosphereOpenAILLMClient] repairs the encoding at the single serialisation
 * seam both dispatch modes route through.
 */
class OpenAiToolCallArgumentsTest {

    private companion object {
        /** Exactly what a model emits, and exactly what must reach the wire. */
        const val TOOL_ARGS = """{"city":"Paris","units":"metric"}"""

        val MODEL: LLModel = OpenAIModels.Chat.GPT4o

        fun settings() = OpenAIClientSettings(
            baseUrl = "http://localhost:1",
            chatCompletionsPath = "chat/completions"
        )

        /** system + user + assistant(tool_call) — the history of a second turn. */
        fun promptWithToolCall(): Prompt = Prompt(
            listOf(
                Message.System("You are a test agent.", RequestMetaInfo.Empty),
                Message.User("What is the weather in Paris?", RequestMetaInfo.Empty),
                Message.Assistant(
                    MessagePart.Tool.Call(id = "call_1", tool = "get_weather", args = TOOL_ARGS),
                    ResponseMetaInfo.Empty
                )
            ),
            "tool-call-wire-test"
        )

        /** The raw `arguments` literal the request body carries, un-decoded. */
        fun argumentsFieldOf(requestBody: String): String {
            val root = Json.parseToJsonElement(requestBody) as JsonObject
            val messages = root["messages"] as JsonArray
            val assistant = messages
                .map { it as JsonObject }
                .single { it["tool_calls"] != null }
            val call = (assistant["tool_calls"] as JsonArray).single() as JsonObject
            val function = call["function"] as JsonObject
            return (function["arguments"] as JsonPrimitive).content
        }
    }

    /**
     * Exposes the two protected seams so the test drives Koog's REAL prompt →
     * request-body conversion, not a hand-rolled stand-in.
     */
    private class RepairedProbe : AtmosphereOpenAILLMClient("test-key", settings()) {
        fun serialize(prompt: Prompt): String = serializeProviderChatRequest(
            convertPromptToMessages(prompt, MODEL), MODEL, emptyList(), null, prompt.params, false
        )
    }

    /** The same seams on stock Koog, with no Atmosphere repair in the path. */
    private class StockKoogProbe : OpenAILLMClient(
        "test-key", settings(), HttpClientFactoryResolver.resolve()
    ) {
        fun serialize(prompt: Prompt): String = serializeProviderChatRequest(
            convertPromptToMessages(prompt, MODEL), MODEL, emptyList(), null, prompt.params, false
        )
    }

    @Test
    fun `tool call arguments reach the wire as a JSON object`() {
        val body = RepairedProbe().serialize(promptWithToolCall())

        val arguments = argumentsFieldOf(body)
        val decoded = Json.parseToJsonElement(arguments)
        assertTrue(
            decoded is JsonObject,
            "arguments must decode to a JSON object (OpenAI wire format); " +
                "got ${decoded::class.simpleName} from <$arguments>"
        )
        assertEquals("Paris", (decoded["city"] as JsonPrimitive).content)
        assertEquals("metric", (decoded["units"] as JsonPrimitive).content)
        // Byte-level: the literal must be the model's own argument text.
        assertEquals(TOOL_ARGS, arguments)
    }

    /**
     * Pins the upstream defect this adapter exists to work around. If a Koog
     * bump makes this fail, `AtmosphereOpenAILLMClient` has become redundant —
     * verify against a strict server and delete it rather than loosening the
     * assertion.
     */
    @Test
    fun `stock Koog double-encodes tool call arguments`() {
        val arguments = argumentsFieldOf(StockKoogProbe().serialize(promptWithToolCall()))

        val decoded = Json.parseToJsonElement(arguments)
        assertTrue(
            decoded is JsonPrimitive && decoded.isString,
            "expected Koog 1.0.0's known double-encoding; got <$arguments>"
        )
        assertEquals(TOOL_ARGS, decoded.content)
    }

    @Test
    fun `a well formed request body is returned untouched`() {
        val body = """
            {"model":"m","messages":[{"role":"assistant","tool_calls":[
            {"id":"c1","type":"function","function":{"name":"t","arguments":"{\"a\":1}"}}]}]}
        """.trimIndent()

        assertTrue(
            body === OpenAiToolCallArguments.normalizeChatRequest(body),
            "a correctly encoded body must not even be re-serialised"
        )
    }

    @Test
    fun `repair is idempotent`() {
        val once = OpenAiToolCallArguments.normalizeChatRequest(
            RepairedProbe().serialize(promptWithToolCall())
        )
        assertEquals(once, OpenAiToolCallArguments.normalizeChatRequest(once))
    }

    @Test
    fun `a non JSON body is passed through`() {
        val garbage = "not json at all"
        assertTrue(garbage === OpenAiToolCallArguments.normalizeChatRequest(garbage))
    }

    @Test
    fun `only a string wrapping an object is unwrapped`() {
        assertEquals("""{"a":1}""", OpenAiToolCallArguments.unwrapDoubleEncoded(""""{\"a\":1}""""))
        // Already correct.
        assertNull(OpenAiToolCallArguments.unwrapDoubleEncoded("""{"a":1}"""))
        // A quoted string that is not an object stays put — repairing it would
        // be a guess, and the provider's own error is the honest signal.
        assertNull(OpenAiToolCallArguments.unwrapDoubleEncoded(""""hello""""))
        assertNull(OpenAiToolCallArguments.unwrapDoubleEncoded(""""[1,2]""""))
    }
}
