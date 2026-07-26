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
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Blackboard
import com.embabel.agent.domain.library.HasContent
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.AgentRuntime
import org.atmosphere.ai.AiCapability
import org.atmosphere.ai.test.AbstractAgentRuntimeContractTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Kotlin contract-test subclass for [EmbabelAgentRuntime]. Brings Embabel into
 * the cross-runtime parity matrix and anchors its
 * [AbstractAgentRuntimeContractTest.expectedCapabilities] declaration so the
 * docs matrix in `tutorial/11-ai-adapters.md` can be regenerated from a
 * single pinned source (Correctness Invariant #5 — Runtime Truth).
 *
 * Execution tests run against a mocked [AgentPlatform] whose
 * [AgentPlatform.runAgentFrom] either:
 *
 *  1. Returns a stubbed [AgentProcess] whose blackboard surfaces a
 *     [HasContent] with "Hello world" for the happy path, so
 *     [EmbabelAgentRuntime.executeDeployedAgent] forwards it to
 *     `session.send(...)` and calls `session.complete()`.
 *  2. Throws when the `userMessage` input carries
 *     [AbstractAgentRuntimeContractTest.CONTRACT_ERROR_SENTINEL], routing
 *     through the runtime's `try/catch (e: Exception)` arm to
 *     `session.error(e)` — the same terminal path a real model-side
 *     failure would take.
 *
 * No live Spring `AgentPlatform` is required.
 */
internal class EmbabelRuntimeContractTest : AbstractAgentRuntimeContractTest() {

    @BeforeEach
    fun installMockAgentPlatform() {
        EmbabelAgentRuntime.setAgentPlatform(buildMockPlatform())
    }

    @AfterEach
    fun clearAgentPlatform() {
        // Clear the static field so sibling Embabel tests that rely on
        // "no platform configured" semantics (e.g. EmbabelGatewayAdmissionTest)
        // don't observe leftover state from this contract test's @BeforeEach.
        try {
            val field = EmbabelAgentRuntime::class.java.getDeclaredField("agentPlatform")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {
            // best-effort — companion-object field layout may vary across
            // Kotlin compiler versions
        }
    }

    override fun createRuntime(): AgentRuntime = EmbabelAgentRuntime()

    override fun createTextContext(): AgentExecutionContext =
        AgentExecutionContext(
            "Hello", "You are helpful", "gpt-4o-mini",
            null, "session-1", "user-1", "conv-1",
            emptyList<org.atmosphere.ai.tool.ToolDefinition>(), null, null,
            emptyList<org.atmosphere.ai.ContextProvider>(),
            emptyMap<String, Any>(),
            emptyList<org.atmosphere.ai.llm.ChatMessage>(),
            null, null
        )

    override fun createToolCallContext(): AgentExecutionContext? = null

    override fun createErrorContext(): AgentExecutionContext =
        AgentExecutionContext(
            CONTRACT_ERROR_SENTINEL, "You are helpful", "gpt-4o-mini",
            null, "session-1", "user-1", "conv-1",
            emptyList<org.atmosphere.ai.tool.ToolDefinition>(), null, null,
            emptyList<org.atmosphere.ai.ContextProvider>(),
            emptyMap<String, Any>(),
            emptyList<org.atmosphere.ai.llm.ChatMessage>(),
            null, null
        )

    override fun expectedCapabilities(): Set<AiCapability> = setOf(
        AiCapability.TEXT_STREAMING,
        AiCapability.STRUCTURED_OUTPUT,
        AiCapability.AGENT_ORCHESTRATION,
        AiCapability.SYSTEM_PROMPT,
        AiCapability.CONVERSATION_MEMORY,
        AiCapability.TOKEN_USAGE,
        AiCapability.PER_REQUEST_RETRY,
        AiCapability.TOOL_CALLING,
        AiCapability.TOOL_APPROVAL,
        AiCapability.VISION,
        AiCapability.MULTI_MODAL,
        AiCapability.BUDGET_ENFORCEMENT,
        AiCapability.CONFIDENCE_SCORES,
        AiCapability.PASSIVATION,
        AiCapability.CANCELLATION,
    )

    /**
     * Explicit cede: Embabel's `AgentPlatform` owns model selection and
     * sampling on each application-defined agent; the framework knobs are
     * not threaded through `runAgentFrom`. See `modules/ai/README.md`
     * § Generation parameters.
     */
    override fun expectedGenerationHonoring(): Set<GenerationParamsSupport> = emptySet()

    /**
     * Both entries point at dedicated tests in this module that assert more
     * than the TCK's hooks could:
     *  - `VISION` — [EmbabelToolBridgeTest] pins `toEmbabelImages`, the
     *    translation of Atmosphere [org.atmosphere.ai.Content.Image] parts into
     *    Embabel `AgentImage` instances.
     *  - `CANCELLATION` — [EmbabelAgentRuntimeCancelTest] drives the handle
     *    against a mocked `AgentPlatform` and asserts the running
     *    `AgentProcess` is killed.
     */
    override fun capabilitiesCoveredOutsideTck(): Map<AiCapability, String> = mapOf(
        AiCapability.VISION to "EmbabelToolBridgeTest",
        AiCapability.CANCELLATION to "EmbabelAgentRuntimeCancelTest"
    )

    /**
     * Exercise `runtimeAcceptsCustomRetryPolicyOnContext`. Embabel implements
     * [org.atmosphere.ai.AgentRuntime] directly and re-implements the outer
     * retry wrapper privately, so the dispatch must accept a non-default
     * [org.atmosphere.ai.RetryPolicy] on the context.
     */
    override fun createRetryContext(): AgentExecutionContext =
        AgentExecutionContext(
            "Hello, no retries.", "You are helpful", "gpt-4o-mini",
            null, "session-1", "user-1", "conv-1",
            emptyList<org.atmosphere.ai.tool.ToolDefinition>(), null, null,
            emptyList<org.atmosphere.ai.ContextProvider>(),
            emptyMap<String, Any>(),
            emptyList<org.atmosphere.ai.llm.ChatMessage>(),
            null, null
        ).withRetryPolicy(org.atmosphere.ai.RetryPolicy.NONE)

    /**
     * Build a mocked [AgentPlatform] that:
     *  1. Reports a single deployed [Agent] named `"chat-assistant"` (the
     *     runtime's default agent name) so [EmbabelAgentRuntime.execute]
     *     takes the deployed-agent dispatch path.
     *  2. Routes [AgentPlatform.runAgentFrom] either through the happy path
     *     (stubbed [AgentProcess] whose blackboard surfaces a [HasContent]
     *     with "Hello world") or the error path (throws when the
     *     `userMessage` input carries [CONTRACT_ERROR_SENTINEL]).
     */
    private fun buildMockPlatform(): AgentPlatform {
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
            org.mockito.kotlin.any<Agent>(),
            org.mockito.kotlin.any<com.embabel.agent.core.ProcessOptions>(),
            org.mockito.kotlin.any<Map<String, Any>>()
        )).thenAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            val input = inv.getArgument<Map<String, Any>>(2)
            val userMessage = input["userMessage"]?.toString() ?: ""
            if (userMessage.contains(CONTRACT_ERROR_SENTINEL)) {
                throw RuntimeException("forced contract error")
            }
            process
        }
        return platform
    }
}
