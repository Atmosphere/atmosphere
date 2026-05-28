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

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationCategory
import ai.koog.prompt.dsl.ModerationCategoryResult
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.AgentRuntime
import org.atmosphere.ai.AiCapability
import org.atmosphere.ai.test.AbstractAgentRuntimeContractTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * Kotlin contract-test subclass for [KoogAgentRuntime]. Brings Koog into the
 * cross-runtime parity matrix so the assertions
 * `runtimeWithSystemPromptAlsoDeclaresStructuredOutput` and
 * `hitlPendingApprovalEmitsProtocolEvent` actually run against it instead of
 * silently skipping.
 *
 * Execution tests run against a hand-rolled fake [PromptExecutor] that either
 * streams a canned text frame for the happy path, or throws when the outgoing
 * [Prompt] carries [AbstractAgentRuntimeContractTest.CONTRACT_ERROR_SENTINEL].
 * The throw routes through [KoogAgentRuntime.executeWithExecutor]'s catch
 * block to `session.error(...)` — the same terminal path a real LLM
 * connection failure would take. No live AIAgent or PromptExecutor needed.
 */
internal class KoogRuntimeContractTest : AbstractAgentRuntimeContractTest() {

    @BeforeEach
    fun installFakePromptExecutor() {
        KoogAgentRuntime.setPromptExecutor(ContractFakeExecutor())
    }

    @AfterEach
    fun clearPromptExecutor() {
        // Clear the static field so sibling Koog tests that rely on
        // "no executor configured" semantics (e.g. KoogAgentRuntimeTest's
        // "execute without executor throws IllegalStateException" assertion)
        // don't observe leftover state from this contract test's @BeforeEach.
        try {
            val field = KoogAgentRuntime::class.java.getDeclaredField("promptExecutor")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {
            // best-effort — companion-object field layout may vary across
            // Kotlin compiler versions
        }
    }

    override fun createRuntime(): AgentRuntime = KoogAgentRuntime()

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
        AiCapability.TOOL_CALLING,
        AiCapability.STRUCTURED_OUTPUT,
        AiCapability.AGENT_ORCHESTRATION,
        AiCapability.CONVERSATION_MEMORY,
        AiCapability.SYSTEM_PROMPT,
        AiCapability.TOOL_APPROVAL,
        AiCapability.TOKEN_USAGE,
        AiCapability.VISION,
        AiCapability.AUDIO,
        AiCapability.MULTI_MODAL,
        AiCapability.PROMPT_CACHING,
        AiCapability.CANCELLATION,
        AiCapability.PER_REQUEST_RETRY,
        AiCapability.BUDGET_ENFORCEMENT,
        AiCapability.CONFIDENCE_SCORES,
        AiCapability.PASSIVATION,
    )

    /**
     * Provide a context carrying a tiny PNG so the base contract's
     * `runtimeWithVisionCapabilityAcceptsImagePart` assertion exercises the
     * Koog dispatch path. The fake [PromptExecutor] installed by
     * [installFakePromptExecutor] now satisfies the prerequisite, so the
     * assertion drives `runtime.execute(...)` through message assembly to
     * the streaming layer — proving the dispatch path does not throw
     * `UnsupportedOperationException` on a multi-modal part.
     */
    override fun createImageContext(): AgentExecutionContext {
        val parts = java.util.List.of<org.atmosphere.ai.Content>(
            org.atmosphere.ai.Content.Image(TINY_PNG, "image/png")
        )
        return AgentExecutionContext(
            "Describe this image.", "You are helpful", "gpt-4o-mini",
            null, "session-1", "user-1", "conv-1",
            emptyList<org.atmosphere.ai.tool.ToolDefinition>(), null, null,
            emptyList<org.atmosphere.ai.ContextProvider>(),
            emptyMap<String, Any>(),
            emptyList<org.atmosphere.ai.llm.ChatMessage>(),
            null, null, emptyList<org.atmosphere.ai.AgentLifecycleListener>(),
            parts,
            org.atmosphere.ai.approval.ToolApprovalPolicy.annotated()
        )
    }

    /**
     * Hand-rolled fake that lets the Koog contract test exercise both terminal
     * paths without a live LLM client:
     *
     *  1. Happy path — [executeStreaming] returns a Flow emitting a TextDelta
     *     and an End frame so [KoogAgentRuntime.executeWithExecutor] forwards
     *     "Hello world" through `session.send(...)` and calls `session.complete()`.
     *  2. Error path — when the outgoing [Prompt] carries the contract error
     *     sentinel as any message content, [executeStreaming] returns a Flow
     *     that throws on subscription. The runtime's `catch (e: Exception)`
     *     arm calls `session.error(e)` — the same terminal path a real
     *     connection failure would take, which is exactly what the contract
     *     asserts.
     *
     * Nested inside the contract test so it can read the protected
     * `CONTRACT_ERROR_SENTINEL` constant inherited from the Java base class.
     */
    private inner class ContractFakeExecutor : PromptExecutor() {
        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> {
            if (carriesErrorSentinel(prompt)) {
                return flow {
                    throw RuntimeException("forced contract error")
                }
            }
            return flowOf(
                StreamFrame.TextDelta("Hello world"),
                StreamFrame.End()
            )
        }

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Message.Assistant = Message.Assistant(
            content = "",
            metaInfo = ResponseMetaInfo.Empty
        )

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            ModerationResult(false, emptyMap<ModerationCategory, ModerationCategoryResult>())

        override fun close() {}

        private fun carriesErrorSentinel(prompt: Prompt): Boolean {
            for (msg in prompt.messages) {
                if (msg.textContent().contains(CONTRACT_ERROR_SENTINEL)) {
                    return true
                }
            }
            return false
        }
    }
}
