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
import org.atmosphere.ai.AgentLifecycleListener
import org.atmosphere.ai.AiConfig
import org.atmosphere.ai.AiEnvironment
import org.atmosphere.ai.CollectingSession
import org.atmosphere.ai.TokenUsage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The executor streaming path (a turn with no tools) reports its model call to
 * the turn's lifecycle listeners as the agent path reports each of its calls:
 * one onModelStart, then one onModelEnd on success, or one onModelError on a
 * provider failure and on the idle bound's timeout; and the turn itself once,
 * onCompletion or onError (Correctness Invariant #7).
 */
class KoogStreamingLifecycleTest {

    @Test
    fun aStreamThatEndsReportsStartThenEnd() {
        val listener = run(Behaviour.ANSWER, "0")
        assertEquals(1, listener.modelStarts.get(), "one onModelStart")
        assertEquals(1, listener.modelEnds.size, "one onModelEnd: ${listener.modelEnds}")
        assertEquals(7L, listener.modelEnds.single()?.total(), "the end frame's usage reaches onModelEnd")
        assertEquals(emptyList<Throwable>(), listener.modelErrors, "no onModelError on success")
        assertEquals(1, listener.completions.get(), "the turn completes once")
        assertEquals(emptyList<Throwable>(), listener.errors, "no onError on success")
    }

    @Test
    fun aProviderFailureReportsOneModelError() {
        val listener = run(Behaviour.FAIL, "0")
        assertEquals(1, listener.modelStarts.get(), "one onModelStart")
        assertEquals(1, listener.modelErrors.size, "one onModelError: ${listener.modelErrors}")
        assertTrue(listener.modelErrors.single().message!!.contains("provider down"),
            "onModelError carries the provider's failure: ${listener.modelErrors}")
        assertEquals(0, listener.modelEnds.size, "no onModelEnd for a failed call")
        assertEquals(1, listener.errors.size, "the turn reports onError once: ${listener.errors}")
        assertEquals(0, listener.completions.get(), "a failed turn does not complete")
    }

    @Test
    fun theIdleTimeoutReportsOneModelError() {
        val listener = run(Behaviour.STALL, "200ms")
        assertEquals(1, listener.modelStarts.get(), "one onModelStart")
        awaitCondition { listener.modelErrors.isNotEmpty() }
        assertEquals(1, listener.modelErrors.size, "one onModelError: ${listener.modelErrors}")
        assertInstanceOf(TimeoutException::class.java, listener.modelErrors.single(),
            "the model error is the idle bound's timeout")
        assertEquals(0, listener.modelEnds.size, "no onModelEnd for a timed-out call")
        assertEquals(1, listener.errors.size, "the turn reports onError once: ${listener.errors}")
        assertInstanceOf(TimeoutException::class.java, listener.errors.single())
        assertEquals(0, listener.completions.get(), "a timed-out turn does not complete")
    }

    private fun run(behaviour: Behaviour, bound: String): RecordingListener {
        val listener = RecordingListener()
        val environment = AiEnvironment.create(mapOf(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY to bound)::get)
        try {
            KoogAgentRuntime.setPromptExecutor(environment, ScriptedExecutor(behaviour))
            val runtime = KoogAgentRuntime()
            runtime.attach(environment)
            val context = AgentExecutionContext(
                "Hello", "You are helpful", "gpt-4o",
                null, "session-1", "user-1", null,
                listOf(), null, null, listOf(), mapOf(),
                listOf(), null, null
            ).withListeners(listOf<AgentLifecycleListener>(listener))
            val handle = runtime.executeWithHandle(context, ErrorTrackingSession(CollectingSession("koog-lifecycle")))
            try {
                handle.whenDone().get(15, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.ExecutionException) {
                // A timeout or a failure settles the handle exceptionally.
            }
            // The worker reports the model call as it unwinds, which a cancel's
            // backstop may settle the handle before.
            awaitCondition { listener.modelEnds.isNotEmpty() || listener.modelErrors.isNotEmpty() }
        } finally {
            environment.close()
        }
        return listener
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
    }

    private enum class Behaviour { ANSWER, FAIL, STALL }

    /** A session that reports whether an error reached it, as a real one does. */
    private class ErrorTrackingSession(private val inner: CollectingSession) :
        org.atmosphere.ai.StreamingSession by inner {
        @Volatile private var errored = false

        override fun error(t: Throwable) {
            errored = true
            inner.error(t)
        }

        override fun hasErrored(): Boolean = errored
    }

    private class RecordingListener : AgentLifecycleListener {
        val modelStarts = AtomicInteger()
        val modelEnds = CopyOnWriteArrayList<TokenUsage?>()
        val modelErrors = CopyOnWriteArrayList<Throwable>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val completions = AtomicInteger()

        override fun onModelStart(model: String, messageCount: Int, toolCount: Int) {
            modelStarts.incrementAndGet()
        }

        override fun onModelEnd(model: String, usage: TokenUsage?, durationMs: Long) {
            modelEnds.add(usage)
        }

        override fun onModelError(model: String, error: Throwable) {
            modelErrors.add(error)
        }

        override fun onError(context: AgentExecutionContext, error: Throwable) {
            errors.add(error)
        }

        override fun onCompletion(context: AgentExecutionContext) {
            completions.incrementAndGet()
        }
    }

    private class ScriptedExecutor(private val behaviour: Behaviour) : PromptExecutor() {
        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> = flow {
            emit(StreamFrame.TextDelta("tick"))
            when (behaviour) {
                Behaviour.ANSWER -> emit(StreamFrame.End(null, ResponseMetaInfo.create(
                    ai.koog.utils.time.KoogClock.System, totalTokensCount = 7, inputTokensCount = 3, outputTokensCount = 4)))
                Behaviour.FAIL -> throw IllegalStateException("provider down")
                Behaviour.STALL -> kotlinx.coroutines.delay(60_000)
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
