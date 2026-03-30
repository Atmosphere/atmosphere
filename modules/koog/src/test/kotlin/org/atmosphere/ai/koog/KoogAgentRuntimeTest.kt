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

import org.atmosphere.ai.AiCapability
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KoogAgentRuntimeTest {

    @Test
    fun `name returns koog`() {
        val runtime = KoogAgentRuntime()
        assertEquals("koog", runtime.name())
    }

    @Test
    fun `isAvailable returns true when koog is on classpath`() {
        val runtime = KoogAgentRuntime()
        assertTrue(runtime.isAvailable(), "Koog classes should be on the test classpath")
    }

    @Test
    fun `priority is 100`() {
        val runtime = KoogAgentRuntime()
        assertEquals(100, runtime.priority())
    }

    @Test
    fun `capabilities include all expected values`() {
        val runtime = KoogAgentRuntime()
        val caps = runtime.capabilities()
        assertTrue(caps.contains(AiCapability.TEXT_STREAMING))
        assertTrue(caps.contains(AiCapability.TOOL_CALLING))
        assertTrue(caps.contains(AiCapability.STRUCTURED_OUTPUT))
        assertTrue(caps.contains(AiCapability.AGENT_ORCHESTRATION))
        assertTrue(caps.contains(AiCapability.CONVERSATION_MEMORY))
        assertTrue(caps.contains(AiCapability.SYSTEM_PROMPT))
    }

    @Test
    fun `execute without executor throws IllegalStateException`() {
        val runtime = KoogAgentRuntime()
        try {
            runtime.execute(
                org.atmosphere.ai.AgentExecutionContext(
                    "hello", null, null, null, null, null, null,
                    emptyList(), null, null, emptyList(), emptyMap(),
                    emptyList(), null
                ),
                org.mockito.Mockito.mock(org.atmosphere.ai.StreamingSession::class.java)
            )
            kotlin.test.fail("Should have thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("PromptExecutor not configured"))
        }
    }
}
