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

import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.AgentRuntime
import org.atmosphere.ai.AiCapability
import org.atmosphere.ai.test.AbstractAgentRuntimeContractTest

/**
 * Kotlin contract-test subclass for [EmbabelAgentRuntime]. Brings Embabel into
 * the cross-runtime parity matrix and anchors its
 * [AbstractAgentRuntimeContractTest.expectedCapabilities] declaration so the
 * docs matrix in `tutorial/11-ai-adapters.md` can be regenerated from a
 * single pinned source (Correctness Invariant #5 — Runtime Truth).
 *
 * Execution tests skip — Embabel needs a real `AgentPlatform`, which this
 * module's unit dependencies do not carry.
 */
internal class EmbabelRuntimeContractTest : AbstractAgentRuntimeContractTest() {

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

    override fun createErrorContext(): AgentExecutionContext? = null

    override fun expectedCapabilities(): Set<AiCapability> = setOf(
        AiCapability.TEXT_STREAMING,
        AiCapability.STRUCTURED_OUTPUT,
        AiCapability.AGENT_ORCHESTRATION,
        AiCapability.SYSTEM_PROMPT,
    )

    // Embabel execution requires a real AgentPlatform, skip live tests.
    override fun textStreamingCompletesSession() {
        // Skip: requires configured AgentPlatform.
    }
}
