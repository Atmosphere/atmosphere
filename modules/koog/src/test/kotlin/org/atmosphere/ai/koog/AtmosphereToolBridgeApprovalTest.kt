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

import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import kotlinx.coroutines.runBlocking
import org.atmosphere.ai.AiEvent
import org.atmosphere.ai.Content
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.approval.ApprovalStrategy
import org.atmosphere.ai.approval.PendingApproval
import org.atmosphere.ai.tool.ToolDefinition
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 0 guarantee that [AtmosphereToolBridge] routes Koog tool invocations
 * through the unified `ToolExecutionHelper.executeWithApproval` call site
 * when a tool is marked with `@RequiresApproval`.
 */
class AtmosphereToolBridgeApprovalTest {

    private class RecordingStrategy(
        private val outcome: ApprovalStrategy.ApprovalOutcome
    ) : ApprovalStrategy {
        val last: AtomicReference<PendingApproval?> = AtomicReference()
        override fun awaitApproval(approval: PendingApproval, session: StreamingSession): ApprovalStrategy.ApprovalOutcome {
            last.set(approval)
            return outcome
        }
    }

    private class NoopSession : StreamingSession {
        override fun sessionId(): String = "koog-test"
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

    private fun sensitive(counter: AtomicInteger): ToolDefinition =
        ToolDefinition.builder("delete_account", "Permanently delete")
            .parameter("userId", "user id", "string")
            .executor { args ->
                counter.incrementAndGet()
                "deleted:${args["userId"]}"
            }
            .requiresApproval("Approve delete?", 60)
            .build()

    private fun userArgs(): JSONObject = JSONObject(linkedMapOf("userId" to JSONPrimitive("u1")))

    @Test
    fun `approved koog tool runs the delegate`() = runBlocking {
        val counter = AtomicInteger()
        val strategy = RecordingStrategy(ApprovalStrategy.ApprovalOutcome.APPROVED)
        val registry = AtmosphereToolBridge.buildRegistry(listOf(sensitive(counter)), NoopSession(), strategy, emptyList())

        val tool = registry.tools.first()
        @Suppress("UNCHECKED_CAST")
        val typedTool = tool as ai.koog.agents.core.tools.Tool<JSONObject, String>
        val result = typedTool.execute(userArgs())

        assertEquals("deleted:u1", result)
        assertEquals(1, counter.get())
        assertNotNull(strategy.last.get())
        assertEquals("delete_account", strategy.last.get()!!.toolName())
    }

    @Test
    fun `denied koog tool skips the delegate`() = runBlocking {
        val counter = AtomicInteger()
        val strategy = RecordingStrategy(ApprovalStrategy.ApprovalOutcome.DENIED)
        val registry = AtmosphereToolBridge.buildRegistry(listOf(sensitive(counter)), NoopSession(), strategy, emptyList())

        val tool = registry.tools.first()
        @Suppress("UNCHECKED_CAST")
        val typedTool = tool as ai.koog.agents.core.tools.Tool<JSONObject, String>
        val result = typedTool.execute(userArgs())

        assertTrue(result.contains("cancelled"))
        assertEquals(0, counter.get())
    }

    @Test
    fun `null strategy falls back to direct execution`() = runBlocking {
        val counter = AtomicInteger()
        val tool = ToolDefinition.builder("echo", "echo")
            .parameter("value", "value", "string")
            .executor { args ->
                counter.incrementAndGet()
                "echo:${args["value"]}"
            }
            .build()
        val registry = AtmosphereToolBridge.buildRegistry(listOf(tool), NoopSession(), null, emptyList())

        @Suppress("UNCHECKED_CAST")
        val echoTool = registry.tools.first() as ai.koog.agents.core.tools.Tool<JSONObject, String>
        val result = echoTool.execute(JSONObject(linkedMapOf("value" to JSONPrimitive("hi"))))

        assertEquals("echo:hi", result)
        assertEquals(1, counter.get())
    }
}
