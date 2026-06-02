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

import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.StreamingSession
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pins Embabel's cooperative [org.atmosphere.ai.AiCapability.CANCELLATION]:
 * [EmbabelAgentRuntime.executeWithHandle] must run the blocking dispatch on a
 * worker thread, return a live handle, and on [org.atmosphere.ai.ExecutionHandle.cancel]
 * interrupt the in-flight dispatch + settle `whenDone()` (Correctness Invariant
 * #2). Guards against a regression that drops back to the no-op
 * `ExecutionHandle.completed()` default (which runs `execute` synchronously and
 * cannot be cancelled).
 *
 * The dispatch is driven against a mocked [AgentPlatform] whose
 * [AgentPlatform.runAgentFrom] blocks until interrupted, standing in for a
 * long-running deployed-agent planner.
 */
internal class EmbabelAgentRuntimeCancelTest {

    @AfterEach
    fun clearAgentPlatform() {
        // Reset the static platform field so sibling Embabel tests don't observe
        // leftover state — same teardown the contract test uses.
        try {
            val field = EmbabelAgentRuntime::class.java.getDeclaredField("agentPlatform")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {
            // best-effort
        }
    }

    @Test
    fun cancelInterruptsInflightDispatchAndSettlesHandle() {
        val started = CountDownLatch(1)
        // Never counted down — runAgentFrom parks here until the cancel
        // interrupt unwinds the await.
        val block = CountDownLatch(1)
        val interrupted = AtomicBoolean()

        val agent = mock(Agent::class.java)
        `when`(agent.name).thenReturn("chat-assistant")
        val platform = mock(AgentPlatform::class.java)
        `when`(platform.agents()).thenReturn(listOf(agent))
        `when`(
            platform.runAgentFrom(
                org.mockito.kotlin.any<Agent>(),
                org.mockito.kotlin.any<com.embabel.agent.core.ProcessOptions>(),
                org.mockito.kotlin.any<Map<String, Any>>()
            )
        ).thenAnswer {
            started.countDown()
            try {
                block.await()
            } catch (ie: InterruptedException) {
                interrupted.set(true)
                Thread.currentThread().interrupt()
                throw ie
            }
            null
        }
        EmbabelAgentRuntime.setAgentPlatform(platform)

        val session = mock(StreamingSession::class.java)
        `when`(session.isClosed).thenReturn(false)

        val runtime = EmbabelAgentRuntime()
        val handle = runtime.executeWithHandle(textContext(), session)

        assertTrue(started.await(5, TimeUnit.SECONDS), "dispatch must start on the worker thread")
        assertFalse(handle.isDone, "handle must stay live while the dispatch blocks")

        handle.cancel()

        handle.whenDone().get(5, TimeUnit.SECONDS)
        assertTrue(interrupted.get(), "cancel() must interrupt the in-flight dispatch worker")
        assertTrue(handle.isDone, "whenDone() must settle after cancel")

        // CAS-guarded idempotency: a second cancel must not re-close the session.
        handle.cancel()
        verify(session, times(1)).complete()
    }

    private fun textContext(): AgentExecutionContext =
        AgentExecutionContext(
            "Hello", "You are helpful", "gpt-4o-mini",
            null, "session-1", "user-1", "conv-1",
            emptyList<org.atmosphere.ai.tool.ToolDefinition>(), null, null,
            emptyList<org.atmosphere.ai.ContextProvider>(),
            emptyMap<String, Any>(),
            emptyList<org.atmosphere.ai.llm.ChatMessage>(),
            null, null
        )
}
