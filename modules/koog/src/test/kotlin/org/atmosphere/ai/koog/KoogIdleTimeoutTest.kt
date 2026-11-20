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
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.test.StreamIdleAssertions
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The stream idle bound on Koog: a direct executor stream that sends one frame
 * and then nothing ends the turn with one timeout in both dispatch modes, and
 * the coroutine collecting it is cancelled. The executor is bound to the turn's
 * own application. The stub stands for the provider; that cancelling the
 * coroutine closes the connection of the client Atmosphere builds is proven on
 * a socket by `KoogRuntimeContractTest`'s stalled-stream fixture.
 */
class KoogIdleTimeoutTest {

    @Test
    fun aSilentStreamEndsTheTurnOnceAndItsCoroutineIsCancelled() {
        val cancellations = AtomicInteger()
        StreamIdleAssertions.assertSilentModelEndsTheTurnOnce(
            { environment ->
                KoogAgentRuntime.setPromptExecutor(environment, SilentExecutor(cancellations))
                KoogAgentRuntime()
            },
            AgentExecutionContext(
                "Hello", "You are helpful", "gpt-4o",
                null, "session-1", "user-1", null,
                listOf(), null, null, listOf(), mapOf(),
                listOf(), null, null
            ),
            "coroutine cancel",
            { cancellations.get() }
        )
    }

    /** Streams one frame, then sends nothing until its coroutine is cancelled. */
    private class SilentExecutor(private val cancellations: AtomicInteger) : PromptExecutor() {
        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> = flow {
            emit(StreamFrame.TextDelta("tick"))
            try {
                kotlinx.coroutines.delay(60_000)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancellations.incrementAndGet()
                throw e
            }
        }

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Message.Assistant = Message.Assistant(content = "", metaInfo = ResponseMetaInfo.Empty)

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            ModerationResult(false, emptyMap<ModerationCategory, ModerationCategoryResult>())

        override fun close() {}
    }
}
