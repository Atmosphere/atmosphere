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
package org.atmosphere.ai.embabel

import com.embabel.agent.api.tool.Tool
import org.atmosphere.ai.Content
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.tool.ToolDefinition
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end regression test for [EmbabelToolBridge] — the file that
 * closes the Embabel TOOL_CALLING / VISION / MULTI_MODAL honesty gap.
 *
 * <p>Contracts exercised:</p>
 * <ol>
 *   <li>An empty tool list produces an empty Embabel Tool list.</li>
 *   <li>A populated tool list produces a corresponding list of Embabel
 *       Tools with the expected name / description / parameter shape
 *       (InputSchema + ParameterType).</li>
 *   <li>Invoking the bridged Tool's Handler with a JSON args string
 *       routes through the original Atmosphere executor and returns the
 *       text result wrapped in `Tool.Result.text(...)`.</li>
 *   <li>`toEmbabelImages` translates Atmosphere [Content.Image] parts
 *       into Embabel [com.embabel.agent.api.common.AgentImage] instances
 *       preserving mime type and bytes, while dropping non-image parts
 *       (audio / file / text).</li>
 * </ol>
 */
class EmbabelToolBridgeTest {

    private fun nullSession(): StreamingSession = object : StreamingSession {
        override fun sessionId() = "test"
        override fun send(text: String) {}
        override fun sendMetadata(key: String, value: Any?) {}
        override fun progress(message: String) {}
        override fun complete() {}
        override fun complete(summary: String) {}
        override fun error(t: Throwable) {}
        override fun isClosed(): Boolean = false
    }

    @Test
    fun `empty tool list produces empty Embabel tool list`() {
        val tools = EmbabelToolBridge.toEmbabelTools(
            emptyList(), nullSession(), null, null
        )
        assertTrue(tools.isEmpty(), "zero-tool input must produce zero-tool output")
    }

    @Test
    fun `tool definitions map to Embabel Tools with correct schema`() {
        val weather = ToolDefinition.builder("get_weather", "Get current weather for a city")
            .parameter("city", "The city name", "string")
            .parameter("units", "Temperature units (celsius or fahrenheit)", "string")
            .executor { _ -> "sunny" }
            .build()
        val calc = ToolDefinition.builder("add", "Add two integers")
            .parameter("a", "First number", "integer")
            .parameter("b", "Second number", "integer")
            .executor { _ -> "42" }
            .build()

        val bridged = EmbabelToolBridge.toEmbabelTools(
            listOf(weather, calc), nullSession(), null, null
        )

        assertEquals(2, bridged.size)

        val weatherTool = bridged[0]
        assertEquals("get_weather", weatherTool.definition.name)
        assertEquals("Get current weather for a city", weatherTool.definition.description)
        val weatherParams: List<Tool.Parameter> = weatherTool.definition.inputSchema.parameters
        assertEquals(2, weatherParams.size)
        assertEquals("city", weatherParams[0].name)
        assertEquals(Tool.ParameterType.STRING, weatherParams[0].type)
        assertEquals("units", weatherParams[1].name)

        val addTool = bridged[1]
        assertEquals("add", addTool.definition.name)
        val addParams: List<Tool.Parameter> = addTool.definition.inputSchema.parameters
        assertEquals(2, addParams.size)
        assertEquals(Tool.ParameterType.INTEGER, addParams[0].type)
        assertEquals(Tool.ParameterType.INTEGER, addParams[1].type)
    }

    @Test
    fun `handler invocation routes through Atmosphere executor and returns Text result`() {
        val invoked = AtomicBoolean(false)
        val capturedArgs = AtomicReference<Map<String, Any?>>()
        val tool = ToolDefinition.builder("get_weather", "Get weather")
            .parameter("city", "City name", "string")
            .executor { args ->
                invoked.set(true)
                @Suppress("UNCHECKED_CAST")
                capturedArgs.set(args.toMap() as Map<String, Any?>)
                "sunny, 22C in ${args["city"]}"
            }
            .build()

        val bridged = EmbabelToolBridge.toEmbabelTool(
            tool, nullSession(), null, null
        )

        val result = bridged.call("""{"city":"Montreal"}""")

        assertTrue(invoked.get(), "Atmosphere executor must have been invoked")
        assertNotNull(capturedArgs.get())
        assertEquals("Montreal", capturedArgs.get()["city"])

        assertTrue(result is Tool.Result.Text, "handler must return Tool.Result.Text")
        assertEquals("sunny, 22C in Montreal", (result as Tool.Result.Text).content)
    }

    @Test
    fun `handler returns Text result wrapping ToolExecutionHelper's error JSON when executor throws`() {
        // ToolExecutionHelper.executeWithApproval catches executor
        // exceptions and returns a formatted JSON error string rather
        // than re-throwing — so the Embabel handler sees a String and
        // wraps it in Tool.Result.text(...). The important contract is
        // that the bridge does NOT propagate the RuntimeException into
        // Embabel's tool-loop (which would abort the agent); it hands
        // back a Text result the LLM can read and recover from.
        val tool = ToolDefinition.builder("boom", "Throws")
            .parameter("x", "x", "string")
            .executor { _ -> throw RuntimeException("kaboom") }
            .build()

        val bridged = EmbabelToolBridge.toEmbabelTool(
            tool, nullSession(), null, null
        )

        val result = bridged.call("""{"x":"y"}""")
        assertTrue(
            result is Tool.Result.Text,
            "handler must not propagate exceptions; ToolExecutionHelper returns an error JSON " +
                "that the bridge wraps in Tool.Result.text(...). Got: $result"
        )
        val text = (result as Tool.Result.Text).content
        assertTrue(
            text.contains("kaboom") || text.contains("error"),
            "error JSON should reference the failure — got: $text"
        )
    }

    @Test
    fun `malformed args JSON surfaces a validation error without throwing`() {
        // ToolExecutionHelper.executeWithApproval validates args BEFORE
        // calling the executor. A malformed JSON parses to an empty map,
        // which fails the schema's "required: x" constraint — the helper
        // short-circuits with a validation-error JSON. The key contract:
        // the bridge does not THROW on malformed args, it returns a Text
        // result the LLM can read.
        val tool = ToolDefinition.builder("noop", "Noop tool")
            .parameter("x", "x", "string")
            .executor { _ -> "should-not-be-called-on-validation-failure" }
            .build()

        val bridged = EmbabelToolBridge.toEmbabelTool(
            tool, nullSession(), null, null
        )

        val result = bridged.call("not-json-at-all")

        assertTrue(
            result is Tool.Result.Text,
            "malformed args must degrade to a Text result, not throw. Got: $result"
        )
    }

    @Test
    fun `image parts map to AgentImage preserving mime type and bytes`() {
        val pngBytes = byteArrayOf(1, 2, 3, 4, 5)
        val jpgBytes = byteArrayOf(6, 7, 8)
        val parts = listOf<Content>(
            Content.Image(pngBytes, "image/png"),
            Content.Image(jpgBytes, "image/jpeg"),
            Content.Text("plain text — ignored"),
            Content.Audio(byteArrayOf(9), "audio/wav") // ignored on image path
        )

        val images = EmbabelToolBridge.toEmbabelImages(parts)

        assertEquals(2, images.size, "only Image parts should translate to AgentImage")
        assertEquals("image/png", images[0].mimeType)
        assertTrue(images[0].data.contentEquals(pngBytes))
        assertEquals("image/jpeg", images[1].mimeType)
        assertTrue(images[1].data.contentEquals(jpgBytes))
    }

    @Test
    fun `empty parts list produces empty image list on zero-image fast path`() {
        assertTrue(EmbabelToolBridge.toEmbabelImages(emptyList()).isEmpty())
    }
}
