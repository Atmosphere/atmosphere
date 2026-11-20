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
import org.atmosphere.ai.test.StreamIdleAssertions
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.atomic.AtomicInteger

/**
 * The stream idle bound on Embabel's deployed-agent path: an
 * `AgentPlatform.runAgentFrom` that sends nothing (the deployed agent reports
 * nothing until it returns, so the bound covers the whole run) ends the turn
 * with one timeout in both dispatch modes, and the worker running it is
 * interrupted. The platform is bound to the turn's own application. The stub
 * honours the interrupt; a real deployed agent has no cancel primitive and runs
 * on (see the module README).
 */
internal class EmbabelIdleTimeoutTest {

    @Test
    fun aSilentDeployedAgentEndsTheTurnOnceAndItsWorkerIsInterrupted() {
        val interrupts = AtomicInteger()
        StreamIdleAssertions.assertSilentModelEndsTheTurnOnce(
            { environment ->
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
                    try {
                        Thread.sleep(60_000)
                    } catch (ie: InterruptedException) {
                        interrupts.incrementAndGet()
                        Thread.currentThread().interrupt()
                        throw ie
                    }
                    null
                }
                EmbabelAgentRuntime.setAgentPlatform(environment, platform)
                EmbabelAgentRuntime()
            },
            AgentExecutionContext(
                "Hello", "You are helpful", "gpt-4o-mini",
                null, "session-1", "user-1", null,
                emptyList<org.atmosphere.ai.tool.ToolDefinition>(), null, null,
                emptyList<org.atmosphere.ai.ContextProvider>(),
                emptyMap<String, Any>(),
                emptyList<org.atmosphere.ai.llm.ChatMessage>(),
                null, null
            ),
            "worker interrupt",
            { interrupts.get() }
        )
    }
}
