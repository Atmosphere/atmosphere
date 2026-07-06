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

import ai.koog.agents.core.agent.context.AIAgentContext
import ai.koog.agents.core.agent.context.AIAgentPlannerContext
import ai.koog.agents.core.agent.execution.AgentExecutionInfo
import ai.koog.agents.core.feature.handler.planner.PlanCompletionEvaluationCompletedContext
import ai.koog.agents.core.feature.handler.planner.PlanCreationCompletedContext
import ai.koog.agents.core.feature.handler.planner.StepExecutionCompletedContext
import ai.koog.agents.core.feature.handler.planner.StepExecutionStartingContext
import ai.koog.agents.core.planner.AIAgentPlanner
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.AiConfig
import org.atmosphere.ai.AiEvent
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.ContextProvider
import org.atmosphere.ai.plan.PlanningTools
import org.atmosphere.ai.tool.ToolDefinition
import org.atmosphere.ai.tool.ToolExecutor
import org.atmosphere.ai.tool.ToolScopes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the Koog planner dispatch and the read-only plan observation bridge:
 *
 *  - [KoogAgentRuntime.executeInternal] routes a request carrying a
 *    [KoogPlanner]-attached [AIAgentPlanner] through a real GA-core
 *    [ai.koog.agents.core.planner.PlannerAIAgent] (no beta artifact, no
 *    LLM call — the stub planner never touches the executor);
 *  - [KoogPlanBridge] mirrors the plan lifecycle events the planner loop
 *    fires (plan-creation / step-execution / completion-evaluation) into
 *    [AiEvent.PlanUpdate] frames with the statuses a console renders;
 *  - the observation gate ([KoogAgentRuntime.shouldObservePlan]) keeps the
 *    native mirror off under PlanningMode.BUILTIN and when the built-in
 *    `write_todos` floor already owns the request's plan surface.
 */
internal class KoogPlanBridgeTest {

    @AfterEach
    fun clearPromptExecutor() {
        // Clear the static field so sibling Koog tests that rely on
        // "no executor configured" semantics don't observe leftover state.
        try {
            val field = KoogAgentRuntime::class.java.getDeclaredField("promptExecutor")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {
            // best-effort — companion-object field layout may vary across
            // Kotlin compiler versions
        }
    }

    // ------------------------------------------------------------------
    // End-to-end: planner dispatch -> PlanUpdate frames (runtime truth)
    // ------------------------------------------------------------------

    @Test
    fun `planner dispatch runs a real PlannerAIAgent and mirrors every plan lifecycle event`() {
        KoogAgentRuntime.setPromptExecutor(RejectingExecutor())
        val session = RecordingSession()
        val context = KoogPlanner.attach(baseContext(), RemainingWorkPlanner())

        KoogAgentRuntime().execute(context, session)

        // Two loop iterations x (plan-created, step-starting, step-completed)
        // plus the terminal completion-evaluated frame.
        val observed = session.planUpdates.map { update ->
            update.steps().map { it["content"] to it["status"] }
        }
        assertEquals(
            listOf(
                listOf("collect requirements" to "pending", "draft answer" to "pending"),
                listOf("collect requirements" to "in_progress", "draft answer" to "pending"),
                listOf("collect requirements" to "completed", "draft answer" to "pending"),
                listOf("collect requirements" to "completed", "draft answer" to "pending"),
                listOf("collect requirements" to "completed", "draft answer" to "in_progress"),
                listOf("collect requirements" to "completed", "draft answer" to "completed"),
                listOf("collect requirements" to "completed", "draft answer" to "completed"),
            ),
            observed,
            "the plan lifecycle must mirror plan->step->evaluate across both iterations"
        )
        val update = session.planUpdates.first()
        assertEquals("${KoogPlanBridge.KOOG_MARKER}: Hello", update.goal(),
            "the goal label carries the marker plus the user message")
        assertEquals(ToolScopes.DEFAULT_SCOPE, update.conversationId())
        assertEquals(ToolScopes.DEFAULT_SCOPE, update.agentId())
        assertEquals(listOf("planner-output: collect requirements | draft answer"),
            session.textCompletes,
            "the planner's String output must surface as a terminal TextComplete")
        assertTrue(session.completed, "the session must complete on the success path")
        assertFalse(session.errored, "the LLM-free planner run must not error")
    }

    @Test
    fun `PlanningMode BUILTIN keeps the native mirror off but still runs the planner`() {
        val previous = System.getProperty(AiConfig.PLANNING_PROPERTY)
        System.setProperty(AiConfig.PLANNING_PROPERTY, "builtin")
        try {
            KoogAgentRuntime.setPromptExecutor(RejectingExecutor())
            val session = RecordingSession()

            KoogAgentRuntime().execute(
                KoogPlanner.attach(baseContext(), RemainingWorkPlanner()), session)

            assertTrue(session.planUpdates.isEmpty(),
                "BUILTIN mode must keep the native plan mirror off the dispatch")
            assertEquals(listOf("planner-output: collect requirements | draft answer"),
                session.textCompletes,
                "the planner itself still executes — only the observation is gated")
            assertTrue(session.completed)
        } finally {
            if (previous == null) {
                System.clearProperty(AiConfig.PLANNING_PROPERTY)
            } else {
                System.setProperty(AiConfig.PLANNING_PROPERTY, previous)
            }
        }
    }

    @Test
    fun `a registered write_todos floor owns the plan surface so the mirror stays off`() {
        KoogAgentRuntime.setPromptExecutor(RejectingExecutor())
        val session = RecordingSession()
        val floorTool = ToolDefinition.builder(PlanningTools.WRITE_TODOS, "portable plan floor")
            .executor(ToolExecutor { _ -> "ok" })
            .build()
        val context = KoogPlanner.attach(baseContext(tools = listOf(floorTool)), RemainingWorkPlanner())

        KoogAgentRuntime().execute(context, session)

        assertTrue(session.planUpdates.isEmpty(),
            "no duplicate plan surfaces: the floor's write_todos owns the console plan")
        // The planner branch must also win over the tool-loop branch — the
        // rejecting executor proves no LLM dispatch happened.
        assertEquals(listOf("planner-output: collect requirements | draft answer"),
            session.textCompletes)
        assertTrue(session.completed)
        assertFalse(session.errored)
    }

    // ------------------------------------------------------------------
    // Bridge mapping edges (framework-boundary handling)
    // ------------------------------------------------------------------

    @Test
    fun `closed session receives no plan updates`() {
        val session = RecordingSession(closed = true)
        val bridge = KoogPlanBridge(session, "goal", "conv", "agent")

        bridge.onPlanCreated(planCreated(listOf("step-1"), stepIndex = 1))

        assertTrue(session.planUpdates.isEmpty())
    }

    @Test
    fun `a failing emit does not propagate into the planner loop`() {
        val session = RecordingSession(failOnEmit = true)
        val bridge = KoogPlanBridge(session, "goal", "conv", "agent")

        // Must not throw — plan observability is best-effort.
        bridge.onPlanCreated(planCreated(listOf("step-1"), stepIndex = 1))

        assertTrue(session.planUpdates.isEmpty())
    }

    @Test
    fun `mirrored plan persists to the scoped AgentPlanStore under the floor's key`() {
        val session = RecordingSession()
        val plans = mutableMapOf<String, org.atmosphere.ai.plan.AgentPlan>()
        val store = object : org.atmosphere.ai.plan.AgentPlanStore {
            override fun get(agentId: String, conversationId: String) =
                java.util.Optional.ofNullable(plans["$agentId/$conversationId"])
            override fun put(agentId: String, conversationId: String,
                             plan: org.atmosphere.ai.plan.AgentPlan) {
                plans["$agentId/$conversationId"] = plan
            }
        }
        val bridge = KoogPlanBridge(session, "goal", "conv", "agent", store)

        bridge.onPlanCreated(planCreated(OpaquePlan("verify the invoice"), stepIndex = 1))

        val stored = store.get("agent", "conv").orElseThrow()
        assertEquals(1, stored.steps().size)
    }

    @Test
    fun `a failing store never propagates and the live frame still emits`() {
        val session = RecordingSession()
        val store = object : org.atmosphere.ai.plan.AgentPlanStore {
            override fun get(agentId: String, conversationId: String) =
                java.util.Optional.empty<org.atmosphere.ai.plan.AgentPlan>()
            override fun put(agentId: String, conversationId: String,
                             plan: org.atmosphere.ai.plan.AgentPlan) =
                throw IllegalStateException("store down")
        }
        val bridge = KoogPlanBridge(session, "goal", "conv", "agent", store)

        bridge.onPlanCreated(planCreated(OpaquePlan("step"), stepIndex = 1))

        assertEquals(1, session.planUpdates.size)
    }

    @Test
    fun `a non-iterable plan decomposes to a single toString step`() {
        val session = RecordingSession()
        val bridge = KoogPlanBridge(session, "goal", "conv", "agent")

        bridge.onPlanCreated(planCreated(OpaquePlan("verify the invoice"), stepIndex = 1))

        assertEquals(
            listOf("verify the invoice" to "pending"),
            session.planUpdates.single().steps().map { it["content"] to it["status"] })
    }

    @Test
    fun `oversized step labels are bounded at the framework boundary`() {
        val session = RecordingSession()
        val bridge = KoogPlanBridge(session, "goal", "conv", "agent")
        val huge = "x".repeat(KoogPlanBridge.MAX_STEP_LABEL * 2)

        bridge.onPlanCreated(planCreated(listOf(huge, "  ", "ok"), stepIndex = 1))

        val steps = session.planUpdates.single().steps()
        assertEquals(2, steps.size, "blank labels are dropped")
        assertEquals(KoogPlanBridge.MAX_STEP_LABEL, (steps[0]["content"] as String).length)
        assertEquals("ok", steps[1]["content"])
    }

    @Test
    fun `a re-listed step re-opens instead of appearing twice`() {
        val session = RecordingSession()
        val bridge = KoogPlanBridge(session, "goal", "conv", "agent")

        bridge.onPlanCreated(planCreated(listOf("step-a", "step-b"), stepIndex = 1))
        bridge.onStepStarting(stepStarting(listOf("step-a", "step-b"), stepIndex = 1))
        bridge.onStepCompleted(stepCompleted(listOf("step-a", "step-b"), stepIndex = 1))
        // The planner re-lists step-a on replan: framework truth wins, the
        // step is open again — never COMPLETED and PENDING at once.
        bridge.onPlanCreated(planCreated(listOf("step-a", "step-b"), stepIndex = 2))

        assertEquals(
            listOf("step-a" to "pending", "step-b" to "pending"),
            session.planUpdates.last().steps().map { it["content"] to it["status"] })
    }

    @Test
    fun `terminal evaluation abandons the never-executed remainder`() {
        val session = RecordingSession()
        val bridge = KoogPlanBridge(session, "goal", "conv", "agent")

        bridge.onPlanCreated(planCreated(listOf("step-a", "step-b"), stepIndex = 1))
        bridge.onStepStarting(stepStarting(listOf("step-a", "step-b"), stepIndex = 1))
        bridge.onStepCompleted(stepCompleted(listOf("step-a", "step-b"), stepIndex = 1))
        // Planner declares the plan complete with step-b never executed —
        // claiming COMPLETED for it would report work that never happened.
        bridge.onCompletionEvaluated(completionEvaluated(listOf("step-b"), stepIndex = 1,
            isCompleted = true))

        assertEquals(
            listOf("step-a" to "completed", "step-b" to "abandoned"),
            session.planUpdates.last().steps().map { it["content"] to it["status"] })
    }

    @Test
    fun `non-terminal evaluation emits nothing`() {
        val session = RecordingSession()
        val bridge = KoogPlanBridge(session, "goal", "conv", "agent")

        bridge.onCompletionEvaluated(completionEvaluated(listOf("step-a"), stepIndex = 1,
            isCompleted = false))

        assertTrue(session.planUpdates.isEmpty(),
            "the follow-up plan-creation frame carries the fresh remainder — an " +
                "intermediate no-change frame would only be noise")
    }

    // ------------------------------------------------------------------
    // Observation gate + goal label helpers
    // ------------------------------------------------------------------

    @Test
    fun `shouldObservePlan is on by default and off under BUILTIN or an active floor`() {
        assertTrue(KoogAgentRuntime.shouldObservePlan(baseContext()))

        val floorTool = ToolDefinition.builder(PlanningTools.WRITE_TODOS, "portable plan floor")
            .executor(ToolExecutor { _ -> "ok" })
            .build()
        assertFalse(KoogAgentRuntime.shouldObservePlan(baseContext(tools = listOf(floorTool))))

        val previous = System.getProperty(AiConfig.PLANNING_PROPERTY)
        System.setProperty(AiConfig.PLANNING_PROPERTY, "builtin")
        try {
            assertFalse(KoogAgentRuntime.shouldObservePlan(baseContext()))
        } finally {
            if (previous == null) {
                System.clearProperty(AiConfig.PLANNING_PROPERTY)
            } else {
                System.setProperty(AiConfig.PLANNING_PROPERTY, previous)
            }
        }
    }

    @Test
    fun `planGoal carries the marker and bounds the message`() {
        assertEquals(KoogPlanBridge.KOOG_MARKER, KoogAgentRuntime.planGoal("  "))
        assertEquals(KoogPlanBridge.KOOG_MARKER, KoogAgentRuntime.planGoal(null))
        assertEquals("${KoogPlanBridge.KOOG_MARKER}: Hello", KoogAgentRuntime.planGoal("Hello"))
        val goal = KoogAgentRuntime.planGoal("y".repeat(500))
        assertEquals("${KoogPlanBridge.KOOG_MARKER}: ".length + 120, goal.length)
        assertTrue(goal.endsWith("..."))
    }

    // ------------------------------------------------------------------
    // KoogPlanner seam semantics (mirrors KoogStrategyBridgeTest)
    // ------------------------------------------------------------------

    @Test
    fun `from returns null when no slot or context`() {
        assertNull(KoogPlanner.from(baseContext()),
            "missing slot must yield null so the runtime takes the default paths")
        assertNull(KoogPlanner.from(null),
            "null context must not NPE — null is a valid 'no planner' signal")
    }

    @Test
    fun `from rejects a wrong-typed slot`() {
        val ctx = baseContext(metadata = mapOf(KoogPlanner.METADATA_KEY to "not a planner"))
        val iae = kotlin.test.assertFailsWith<IllegalArgumentException> {
            KoogPlanner.from(ctx)
        }
        assertTrue(iae.message.orEmpty().contains(KoogPlanner.METADATA_KEY),
            "type errors must point at the canonical key so the misconfig is debuggable")
    }

    @Test
    fun `attach and from round-trip the exact planner instance`() {
        val planner = RemainingWorkPlanner()
        val ctx = KoogPlanner.attach(baseContext(), planner)

        val resolved = KoogPlanner.from(ctx)
        assertNotNull(resolved)
        assertSame(planner, resolved,
            "the resolved planner must be the exact instance the caller attached")
    }

    @Test
    fun `attach replaces the previous planner and preserves unrelated metadata`() {
        val first = RemainingWorkPlanner()
        val second = RemainingWorkPlanner()
        val ctx1 = KoogPlanner.attach(
            baseContext(metadata = mapOf("other.key" to "other-value")), first)
        val ctx2 = KoogPlanner.attach(ctx1, second)

        assertSame(second, KoogPlanner.from(ctx2),
            "attach must replace, not merge — one planner per request")
        assertEquals("other-value", ctx2.metadata()["other.key"],
            "attach must not drop unrelated metadata entries")
    }

    // ------------------------------------------------------------------
    // Stubs
    // ------------------------------------------------------------------

    /**
     * Deterministic remaining-work planner: the plan is the not-yet-executed
     * suffix of two fixed steps, each [executeStep] performs the head, and
     * the plan completes when both ran. Never touches the LLM — proving the
     * GA-core planner loop drives the bridge without the beta planner
     * artifact or a live model.
     */
    private class RemainingWorkPlanner :
        AIAgentPlanner<String, String, MutableList<String>, List<String>>() {

        private val allSteps = listOf("collect requirements", "draft answer")

        override fun initializeState(input: String): MutableList<String> = mutableListOf()

        override fun provideOutput(state: MutableList<String>): String =
            "planner-output: " + state.joinToString(" | ")

        override suspend fun buildPlan(
            context: AIAgentPlannerContext, state: MutableList<String>, plan: List<String>?
        ): List<String> = allSteps.drop(state.size)

        override suspend fun executeStep(
            context: AIAgentPlannerContext, state: MutableList<String>, plan: List<String>
        ): MutableList<String> {
            state.add(plan.first())
            return state
        }

        override suspend fun isPlanCompleted(
            context: AIAgentPlannerContext, state: MutableList<String>, plan: List<String>
        ): Boolean = state.size >= allSteps.size
    }

    /** Fails the test if any dispatch path reaches the LLM. */
    private class RejectingExecutor : PromptExecutor() {
        override fun executeStreaming(
            prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>
        ): Flow<StreamFrame> = flow {
            throw IllegalStateException("planner dispatch must not call the LLM")
        }

        override suspend fun execute(
            prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>
        ): Message.Assistant =
            throw IllegalStateException("planner dispatch must not call the LLM")

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            throw IllegalStateException("planner dispatch must not call the LLM")

        override fun close() {}
    }

    /** Non-iterable caller plan shape for the single-step decomposition edge. */
    private class OpaquePlan(private val label: String) {
        override fun toString(): String = label
    }

    private fun planCreated(updatedPlan: Any, stepIndex: Int) =
        PlanCreationCompletedContext("event-$stepIndex", executionInfo(),
            mock(AIAgentContext::class.java), Any(), null, null, null, stepIndex, updatedPlan)

    private fun stepStarting(plan: Any, stepIndex: Int) =
        StepExecutionStartingContext("event-$stepIndex", executionInfo(),
            mock(AIAgentContext::class.java), Any(), null, plan, null, stepIndex)

    private fun stepCompleted(plan: Any, stepIndex: Int) =
        StepExecutionCompletedContext("event-$stepIndex", executionInfo(),
            mock(AIAgentContext::class.java), Any(), null, plan, null, stepIndex)

    private fun completionEvaluated(plan: Any, stepIndex: Int, isCompleted: Boolean) =
        PlanCompletionEvaluationCompletedContext("event-$stepIndex", executionInfo(),
            mock(AIAgentContext::class.java), Any(), null, plan, null, stepIndex, isCompleted)

    private fun executionInfo() = AgentExecutionInfo(null, "koog-plan-bridge-test")

    private fun baseContext(
        metadata: Map<String, Any> = emptyMap(),
        tools: List<ToolDefinition> = emptyList()
    ): AgentExecutionContext =
        AgentExecutionContext(
            "Hello", "You are helpful", "gpt-4o-mini",
            null, "session-1", "user-1", "conv-1",
            tools, null, null,
            emptyList<ContextProvider>(),
            metadata,
            emptyList<org.atmosphere.ai.llm.ChatMessage>(),
            null, null
        )

    /** Session double capturing PlanUpdate / TextComplete frames + terminal calls. */
    private class RecordingSession(
        private val closed: Boolean = false,
        private val failOnEmit: Boolean = false
    ) : StreamingSession {
        val planUpdates = mutableListOf<AiEvent.PlanUpdate>()
        val textCompletes = mutableListOf<String>()
        var completed = false
            private set
        var errored = false
            private set

        override fun sessionId(): String = "koog-plan-bridge-test"
        override fun send(text: String) {}
        override fun sendMetadata(key: String, value: Any) {}
        override fun progress(message: String) {}
        override fun complete() {
            completed = true
        }

        override fun complete(summary: String) {
            completed = true
        }

        override fun error(t: Throwable) {
            errored = true
        }

        override fun isClosed(): Boolean = closed

        override fun emit(event: AiEvent) {
            if (failOnEmit) {
                throw IllegalStateException("forced emit failure")
            }
            when (event) {
                is AiEvent.PlanUpdate -> planUpdates.add(event)
                is AiEvent.TextComplete -> textCompletes.add(event.fullText)
                else -> {}
            }
        }
    }
}
