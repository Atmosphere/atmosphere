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

import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.AgentRuntime
import org.atmosphere.ai.test.AbstractAgentRuntimeContractTest

/**
 * Kotlin contract-test subclass for [KoogAgentRuntime]. Brings Koog into the
 * cross-runtime parity matrix so the assertions
 * `runtimeWithSystemPromptAlsoDeclaresStructuredOutput` and
 * `hitlPendingApprovalEmitsProtocolEvent` actually run against it instead of
 * silently skipping.
 *
 * Execution tests override to skip — Koog needs a real [ai.koog.agents.core.agent.AIAgent]
 * with a live LLM client, which this module's unit tests do not carry.
 */
internal class KoogRuntimeContractTest : AbstractAgentRuntimeContractTest() {

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

    override fun createErrorContext(): AgentExecutionContext? = null

    // Koog execution requires a configured AIAgent with a live PromptExecutor.
    override fun textStreamingCompletesSession() {
        // Skip: requires real AIAgent instance.
    }
}
