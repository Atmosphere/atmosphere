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

import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent
import com.embabel.agent.api.event.ReplanRequestedEvent
import com.embabel.agent.core.ActionInvocation
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.library.HasContent
import com.embabel.plan.Action
import com.embabel.plan.Goal
import com.embabel.plan.Plan
import com.embabel.plan.WorldState
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.AiConfig
import org.atmosphere.ai.AiEvent
import org.atmosphere.ai.StreamingSession
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the read-only GOAP plan observation bridge: Embabel
 * [AgentProcessPlanFormulatedEvent] / [ReplanRequestedEvent] process events
 * map to [AiEvent.PlanUpdate] frames (completed history + pending plan
 * actions, goal labeled with the `GOAP` marker), and
 * [EmbabelAgentRuntime.executeDeployedAgent] genuinely registers the bridge
 * on the [ProcessOptions] listener list — the wiring that makes
 * `AiCapability.PLANNING` an honest declaration (Correctness Invariant #5).
 */
internal class EmbabelGoapPlanBridgeTest {

    @AfterEach
    fun clearAgentPlatform() {
        // Clear the static field so sibling Embabel tests that rely on
        // "no platform configured" semantics don't observe leftover state.
        try {
            val field = EmbabelAgentRuntime::class.java.getDeclaredField("agentPlatform")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {
            // best-effort — companion-object field layout may vary across
            // Kotlin compiler versions
        }
    }

    // ------------------------------------------------------------------
    // Event → PlanUpdate mapping
    // ------------------------------------------------------------------

    @Test
    fun `plan formulated event emits PlanUpdate with GOAP-labeled goal and pending steps`() {
        val session = RecordingSession()
        val bridge = EmbabelGoapPlanBridge(session)
        val process = stubProcess(history = emptyList())
        val plan = Plan(listOf(stubAction("search flights"), stubAction("book flight")),
            stubGoal("flight booked"))

        bridge.onProcessEvent(AgentProcessPlanFormulatedEvent(process, mock(WorldState::class.java), plan))

        assertEquals(1, session.planUpdates.size)
        val update = session.planUpdates.single()
        assertEquals("${EmbabelGoapPlanBridge.GOAP_MARKER}: flight booked", update.goal())
        assertEquals(
            listOf("search flights" to "pending", "book flight" to "pending"),
            update.steps().map { it["content"] to it["status"] })
    }

    @Test
    fun `executed history maps to completed steps ahead of pending plan actions`() {
        val session = RecordingSession()
        val bridge = EmbabelGoapPlanBridge(session)
        val process = stubProcess(history = listOf(
            ActionInvocation("search flights", Instant.now(), Duration.ZERO)))
        val plan = Plan(listOf(stubAction("book flight")), stubGoal("flight booked"))

        bridge.onProcessEvent(AgentProcessPlanFormulatedEvent(process, mock(WorldState::class.java), plan))

        val update = session.planUpdates.single()
        assertEquals(
            listOf("search flights" to "completed", "book flight" to "pending"),
            update.steps().map { it["content"] to it["status"] })
    }

    @Test
    fun `replan event emits completed history only with the reason on the goal label`() {
        val session = RecordingSession()
        val bridge = EmbabelGoapPlanBridge(session)
        val formulated = stubProcess(history = emptyList())
        bridge.onProcessEvent(AgentProcessPlanFormulatedEvent(
            formulated, mock(WorldState::class.java),
            Plan(listOf(stubAction("book flight")), stubGoal("flight booked"))))

        val replanning = stubProcess(history = listOf(
            ActionInvocation("search flights", Instant.now(), Duration.ZERO)))
        bridge.onProcessEvent(ReplanRequestedEvent(replanning, "price changed"))

        assertEquals(2, session.planUpdates.size)
        val update = session.planUpdates.last()
        assertEquals(
            "${EmbabelGoapPlanBridge.GOAP_MARKER}: flight booked (replanning: price changed)",
            update.goal())
        assertEquals(
            listOf("search flights" to "completed"),
            update.steps().map { it["content"] to it["status"] })
    }

    @Test
    fun `closed session receives no plan updates`() {
        val session = RecordingSession(closed = true)
        val bridge = EmbabelGoapPlanBridge(session)
        val plan = Plan(listOf(stubAction("book flight")), stubGoal("flight booked"))

        bridge.onProcessEvent(AgentProcessPlanFormulatedEvent(
            stubProcess(emptyList()), mock(WorldState::class.java), plan))

        assertTrue(session.planUpdates.isEmpty())
    }

    @Test
    fun `a failing emit does not propagate into the Embabel event multicast`() {
        val session = RecordingSession(failOnEmit = true)
        val bridge = EmbabelGoapPlanBridge(session)
        val plan = Plan(listOf(stubAction("book flight")), stubGoal("flight booked"))

        // Must not throw — plan observability is best-effort.
        bridge.onProcessEvent(AgentProcessPlanFormulatedEvent(
            stubProcess(emptyList()), mock(WorldState::class.java), plan))

        assertTrue(session.planUpdates.isEmpty())
    }

    // ------------------------------------------------------------------
    // Dispatch wiring (runtime truth for AiCapability.PLANNING)
    // ------------------------------------------------------------------

    @Test
    fun `deployed-agent dispatch registers the bridge on ProcessOptions`() {
        val platform = mockPlatform()
        EmbabelAgentRuntime.setAgentPlatform(platform)

        EmbabelAgentRuntime().execute(textContext(), RecordingSession())

        val captor = argumentCaptor<ProcessOptions>()
        verify(platform).runAgentFrom(any<Agent>(), captor.capture(), any<Map<String, Any>>())
        assertTrue(captor.firstValue.listeners.any { it is EmbabelGoapPlanBridge },
            "executeDeployedAgent must register EmbabelGoapPlanBridge under PlanningMode.AUTO")
    }

    @Test
    fun `PlanningMode BUILTIN skips the native bridge`() {
        val previous = System.getProperty(AiConfig.PLANNING_PROPERTY)
        System.setProperty(AiConfig.PLANNING_PROPERTY, "builtin")
        try {
            val platform = mockPlatform()
            EmbabelAgentRuntime.setAgentPlatform(platform)

            EmbabelAgentRuntime().execute(textContext(), RecordingSession())

            val captor = argumentCaptor<ProcessOptions>()
            verify(platform).runAgentFrom(any<Agent>(), captor.capture(), any<Map<String, Any>>())
            assertFalse(captor.firstValue.listeners.any { it is EmbabelGoapPlanBridge },
                "PlanningMode.BUILTIN must keep the native GOAP bridge off the dispatch")
        } finally {
            if (previous == null) {
                System.clearProperty(AiConfig.PLANNING_PROPERTY)
            } else {
                System.setProperty(AiConfig.PLANNING_PROPERTY, previous)
            }
        }
    }

    // ------------------------------------------------------------------
    // Stubs
    // ------------------------------------------------------------------

    private fun stubProcess(history: List<ActionInvocation>): AgentProcess {
        val process = mock(AgentProcess::class.java)
        `when`(process.id).thenReturn("proc-1")
        `when`(process.history).thenReturn(history)
        return process
    }

    private fun stubAction(actionName: String): Action = object : Action {
        override val name: String = actionName
        override val cost: (WorldState) -> Double = { 0.0 }
        override val value: (WorldState) -> Double = { 0.0 }
        override fun infoString(verbose: Boolean?, indent: Int): String = actionName
    }

    private fun stubGoal(goalName: String): Goal = object : Goal {
        override val name: String = goalName
        override val value: (WorldState) -> Double = { 0.0 }
        override fun infoString(verbose: Boolean?, indent: Int): String = goalName
    }

    private fun mockPlatform(): AgentPlatform {
        val platform = mock(AgentPlatform::class.java)
        val agent = mock(Agent::class.java)
        `when`(agent.name).thenReturn("chat-assistant")
        `when`(platform.agents()).thenReturn(listOf(agent))

        val blackboard = mock(Blackboard::class.java)
        val hasContent = mock(HasContent::class.java)
        `when`(hasContent.content).thenReturn("Hello world")
        `when`(blackboard.lastResult()).thenReturn(hasContent)

        val process = mock(AgentProcess::class.java)
        `when`(process.blackboard).thenReturn(blackboard)
        `when`(platform.runAgentFrom(
            any<Agent>(), any<ProcessOptions>(), any<Map<String, Any>>()
        )).thenReturn(process)
        return platform
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

    /** Session double capturing PlanUpdate frames (and other terminal calls). */
    private class RecordingSession(
        private val closed: Boolean = false,
        private val failOnEmit: Boolean = false
    ) : StreamingSession {
        val planUpdates = mutableListOf<AiEvent.PlanUpdate>()

        override fun sessionId(): String = "goap-bridge-test"
        override fun send(text: String) { }
        override fun sendMetadata(key: String, value: Any) { }
        override fun progress(message: String) { }
        override fun complete() { }
        override fun complete(summary: String) { }
        override fun error(t: Throwable) { }
        override fun isClosed(): Boolean = closed

        override fun emit(event: AiEvent) {
            if (failOnEmit) {
                throw IllegalStateException("forced emit failure")
            }
            if (event is AiEvent.PlanUpdate) {
                planUpdates.add(event)
            }
        }
    }
}
