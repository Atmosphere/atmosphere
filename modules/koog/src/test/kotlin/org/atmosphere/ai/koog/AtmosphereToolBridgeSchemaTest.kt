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

import ai.koog.agents.core.tools.ToolParameterType
import org.atmosphere.ai.AiEvent
import org.atmosphere.ai.Content
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.tool.ToolDefinition
import org.atmosphere.ai.tool.ToolParameter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins that the structural facets of an Atmosphere tool parameter survive the
 * translation into Koog's type model. Before this, enums, arrays and nested
 * objects all collapsed to [ToolParameterType.String] — a Koog agent was told
 * a list was a string and that an enum had no allowed values, so it had to
 * guess both the shape and the legal values.
 */
class AtmosphereToolBridgeSchemaTest {

    private class NoopSession : StreamingSession {
        override fun sessionId(): String = "koog-schema-test"
        override fun send(text: String) {}
        override fun sendMetadata(key: String, value: Any?) {}
        override fun sendContent(content: Content) {}
        override fun progress(message: String) {}
        override fun complete() {}
        override fun complete(summary: String) {}
        override fun error(t: Throwable) {}
        override fun isClosed(): Boolean = false
        override fun emit(event: AiEvent) {}
    }

    private fun descriptorFor(vararg params: ToolParameter) =
        AtmosphereToolBridge.buildRegistry(
            listOf(
                ToolDefinition.builder("shaped", "Tool with shaped parameters")
                    .apply { params.forEach { parameter(it) } }
                    .executor { "ok" }
                    .build()
            ),
            NoopSession(), null, emptyList()
        ).tools.first().descriptor

    @Test
    fun `enum parameter carries its allowed values`() {
        val descriptor = descriptorFor(
            ToolParameter.ofEnum("unit", "Temperature unit", true, listOf("CELSIUS", "FAHRENHEIT"))
        )
        val param = (descriptor.requiredParameters + descriptor.optionalParameters)
            .first { it.name == "unit" }
        val type = assertIs<ToolParameterType.Enum>(param.type)
        assertEquals(listOf("CELSIUS", "FAHRENHEIT"), type.entries.toList())
    }

    @Test
    fun `array parameter carries its element type`() {
        val descriptor = descriptorFor(
            ToolParameter.ofArray("tags", "Tags", false,
                ToolParameter("item", "", "string", true))
        )
        val param = (descriptor.requiredParameters + descriptor.optionalParameters)
            .first { it.name == "tags" }
        val type = assertIs<ToolParameterType.List>(param.type)
        assertTrue(type.itemsType is ToolParameterType.String)
    }

    @Test
    fun `object parameter carries its nested properties`() {
        val descriptor = descriptorFor(
            ToolParameter.ofObject("origin", "Origin", true,
                listOf(ToolParameter("city", "City", "string", true)))
        )
        val param = (descriptor.requiredParameters + descriptor.optionalParameters)
            .first { it.name == "origin" }
        val type = assertIs<ToolParameterType.Object>(param.type)
        assertEquals(listOf("city"), type.properties.map { it.name })
        assertEquals(listOf("city"), type.requiredProperties)
    }

    @Test
    fun `flat parameters keep their primitive mapping`() {
        val descriptor = descriptorFor(
            ToolParameter("count", "How many", "integer", true)
        )
        val param = (descriptor.requiredParameters + descriptor.optionalParameters)
            .first { it.name == "count" }
        assertTrue(param.type is ToolParameterType.Integer)
    }
}
