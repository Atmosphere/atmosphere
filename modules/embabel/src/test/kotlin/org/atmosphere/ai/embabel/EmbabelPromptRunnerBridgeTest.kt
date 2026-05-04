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

import com.embabel.agent.api.common.PromptRunner
import org.atmosphere.ai.AgentExecutionContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.function.UnaryOperator

/**
 * Verifies the per-request Embabel [PromptRunner] customizer sidecar
 * ([EmbabelPromptRunner]). The customizer fires only on the
 * Atmosphere-native dispatch path (`Ai.withDefaultLlm()`); the deployed-
 * `@Agent` path bypasses [PromptRunner]. Helper-level semantics: missing
 * slot returns `null`, non-`UnaryOperator` slot throws loudly, attach
 * replaces, attach preserves unrelated metadata, and roundtripping returns
 * the same customizer instance.
 */
internal class EmbabelPromptRunnerBridgeTest {

    @Test
    fun `from returns null when no slot`() {
        val ctx = baseContext(emptyMap())
        assertNull(
            EmbabelPromptRunner.from(ctx),
            "missing slot must yield null so the runtime dispatches with the " +
                "default pre-configured PromptRunner",
        )
    }

    @Test
    fun `from returns null when context is null`() {
        assertNull(
            EmbabelPromptRunner.from(null),
            "null context must yield null rather than NPE",
        )
    }

    @Test
    fun `from rejects non-UnaryOperator slot`() {
        val ctx = baseContext(mapOf(EmbabelPromptRunner.METADATA_KEY to "not a customizer"))
        val iae = assertThrows(IllegalArgumentException::class.java) {
            EmbabelPromptRunner.from(ctx)
        }
        assertTrue(iae.message!!.contains(EmbabelPromptRunner.METADATA_KEY))
    }

    @Test
    fun `attach stores customizer under canonical key`() {
        val customizer = UnaryOperator<PromptRunner> { it }
        val ctx = EmbabelPromptRunner.attach(baseContext(emptyMap()), customizer)
        assertSame(
            customizer,
            ctx.metadata()[EmbabelPromptRunner.METADATA_KEY],
            "attach must store under the canonical key the runtime reads from",
        )
        assertSame(
            customizer,
            EmbabelPromptRunner.from(ctx),
            "round-trip from(attach(ctx, c)) must return c",
        )
    }

    @Test
    fun `attach replaces previous customizer`() {
        val first = UnaryOperator<PromptRunner> { it }
        val second = UnaryOperator<PromptRunner> { it }
        val withFirst = EmbabelPromptRunner.attach(baseContext(emptyMap()), first)
        val withSecond = EmbabelPromptRunner.attach(withFirst, second)
        assertSame(
            second,
            EmbabelPromptRunner.from(withSecond),
            "the bridge is exclusive — one customizer per request; callers " +
                "compose multiple customizers themselves before attaching",
        )
        assertNotSame(withFirst, withSecond)
    }

    @Test
    fun `attach preserves other metadata entries`() {
        val ctx = baseContext(mapOf("other.key" to "preserved"))
        val with = EmbabelPromptRunner.attach(ctx, UnaryOperator<PromptRunner> { it })
        assertEquals(
            "preserved",
            with.metadata()["other.key"],
            "attach must not clobber unrelated metadata entries",
        )
    }

    private fun baseContext(metadata: Map<String, Any>): AgentExecutionContext = AgentExecutionContext(
        "Hello", "You are helpful", "gpt-4o-mini",
        null, "session-1", "alice", "conv-1",
        emptyList<org.atmosphere.ai.tool.ToolDefinition>(), null, null,
        emptyList<org.atmosphere.ai.ContextProvider>(),
        metadata,
        emptyList<org.atmosphere.ai.llm.ChatMessage>(),
        null, null,
    )
}
