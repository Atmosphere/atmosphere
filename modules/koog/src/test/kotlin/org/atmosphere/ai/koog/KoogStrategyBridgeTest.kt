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

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.ext.agent.chatAgentStrategy
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.tool.ToolDefinition
import org.atmosphere.ai.ContextProvider
import org.atmosphere.ai.llm.ChatMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies the [KoogStrategy] bridge: helper semantics ([KoogStrategy.from] /
 * [KoogStrategy.attach]) and end-to-end propagation through
 * [AgentExecutionContext.metadata]. Runtime-level dispatch — that
 * [KoogAgentRuntime.executeWithAgent] reads [KoogStrategy.from] and routes
 * through the strategy-aware [ai.koog.agents.core.agent.AIAgent] factory
 * overload — is wired but exercised end-to-end only against a live Koog
 * [ai.koog.prompt.executor.model.PromptExecutor]; the multi-runtime sample
 * in `samples/spring-boot-multi-agent-startup-team` is the regression
 * backstop for that path.
 */
internal class KoogStrategyBridgeTest {

    @Test
    fun fromReturnsNullWhenNoSlot() {
        assertNull(
            KoogStrategy.from(baseContext(emptyMap())),
            "missing slot must yield null so the runtime takes the default chatAgentStrategy() path"
        )
    }

    @Test
    fun fromReturnsNullWhenContextIsNull() {
        assertNull(
            KoogStrategy.from(null),
            "null context must not NPE — null is a valid 'no bridge' signal"
        )
    }

    @Test
    fun fromRejectsWrongType() {
        val ctx = baseContext(mapOf(KoogStrategy.METADATA_KEY to "not a strategy"))
        val iae = assertThrows(IllegalArgumentException::class.java) {
            KoogStrategy.from(ctx)
        }
        assertEquals(
            true, iae.message?.contains(KoogStrategy.METADATA_KEY),
            "type errors must point at the canonical key so the misconfig is debuggable"
        )
    }

    @Test
    fun attachAndFromRoundTripsBuiltInStrategy() {
        val strategy: AIAgentGraphStrategy<String, String> = chatAgentStrategy()
        val ctx = KoogStrategy.attach(baseContext(emptyMap()), strategy)

        val resolved = KoogStrategy.from(ctx)
        assertNotNull(resolved, "the attached strategy must be readable by from()")
        assertSame(
            strategy, resolved,
            "the resolved strategy must be the exact instance the caller attached — " +
                "no defensive copy, no wrapping (the runtime hands it directly to AIAgent(...))"
        )
    }

    @Test
    fun attachReplacesPreviousStrategy() {
        val first: AIAgentGraphStrategy<String, String> = chatAgentStrategy()
        val second: AIAgentGraphStrategy<String, String> = chatAgentStrategy()

        val ctx1 = KoogStrategy.attach(baseContext(emptyMap()), first)
        val ctx2 = KoogStrategy.attach(ctx1, second)

        assertSame(
            second, KoogStrategy.from(ctx2),
            "attach must replace, not merge — the bridge is exclusive (one strategy per request)"
        )
    }

    @Test
    fun attachPreservesExistingMetadata() {
        val pre = mapOf<String, Any>("other.key" to "other-value")
        val strategy: AIAgentGraphStrategy<String, String> = chatAgentStrategy()
        val ctx = KoogStrategy.attach(baseContext(pre), strategy)

        assertEquals(
            "other-value", ctx.metadata()["other.key"],
            "attach must not drop unrelated metadata entries (CacheHint, listeners, etc.)"
        )
        assertSame(strategy, KoogStrategy.from(ctx))
    }

    private fun baseContext(metadata: Map<String, Any>): AgentExecutionContext =
        AgentExecutionContext(
            "Hello", "You are helpful", "gpt-4o-mini",
            null, "session-1", "user-1", "conv-1",
            emptyList<ToolDefinition>(), null, null,
            emptyList<ContextProvider>(),
            metadata,
            emptyList<ChatMessage>(),
            null, null,
        )
}
