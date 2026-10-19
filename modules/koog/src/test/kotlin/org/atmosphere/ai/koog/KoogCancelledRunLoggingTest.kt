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
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.AiEnvironment
import org.atmosphere.ai.CollectingSession
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A run its caller abandons is not a failure. The startup-team sweep logged
 * `ERROR KoogAgentRuntime : Koog streaming failed` with a
 * `java.lang.InterruptedException` at `BlockingCoroutine.joinBlocking`: the
 * coordinator's LLM judge bounds its call (`AgentRuntime.generate(context,
 * timeout)`, 15 s), and on the timeout the handle's `cancel()` cancels the Job
 * and interrupts the worker. While the model call blocks off the `runBlocking`
 * thread, `runBlocking` answers the interrupt, once that call returns, with an
 * `InterruptedException`, not a `CancellationException`, so the run reached the
 * generic failure branch
 * and reported a cancel the caller asked for as a provider failure, stack and
 * all.
 */
class KoogCancelledRunLoggingTest {

    private val release = CountDownLatch(1)
    private val engaged = CountDownLatch(1)
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var runtimeLogger: Logger
    private var previousLevel: Level? = null
    private lateinit var environment: AiEnvironment
    private lateinit var runtime: KoogAgentRuntime

    @BeforeEach
    fun setUp() {
        runtimeLogger = LoggerFactory.getLogger(KoogAgentRuntime::class.java) as Logger
        previousLevel = runtimeLogger.level
        runtimeLogger.level = Level.DEBUG
        appender = ListAppender<ILoggingEvent>().also { it.start() }
        runtimeLogger.addAppender(appender)
        environment = AiEnvironment.create { null }
        KoogAgentRuntime.setPromptExecutor(environment, StalledModelExecutor(engaged, release))
        runtime = KoogAgentRuntime().also { it.attach(environment) }
    }

    @AfterEach
    fun tearDown() {
        release.countDown()
        runtimeLogger.detachAppender(appender)
        runtimeLogger.level = previousLevel
        environment.close()
    }

    private fun context(): AgentExecutionContext = AgentExecutionContext(
        "Evaluate this agent response.", "You are a judge", null,
        null, "session-1", "user-1", "conv-1",
        emptyList<org.atmosphere.ai.tool.ToolDefinition>(), null, null,
        emptyList<org.atmosphere.ai.ContextProvider>(),
        emptyMap<String, Any>(),
        emptyList<org.atmosphere.ai.llm.ChatMessage>(),
        null, null
    ).withRetryPolicy(org.atmosphere.ai.RetryPolicy.NONE)

    /** The runtime's report on the abandoned run, once its worker has handled it. */
    private fun awaitOutcome(): List<ILoggingEvent> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val outcome = appender.list.filter {
                it.level == Level.ERROR || it.formattedMessage.contains("cancelled")
            }
            if (outcome.isNotEmpty()) return outcome
            Thread.sleep(20)
        }
        return emptyList()
    }

    @Test
    fun `a bounded generate that times out is not logged as a failure`() {
        val text = runtime.generate(context(), Duration.ofMillis(300))

        assertEquals("", text, "a timed-out generate answers the documented empty result")
        // The provider call returns later, as the model finishes the answer
        // nobody reads; runBlocking throws the interrupt only then.
        release.countDown()
        val outcome = awaitOutcome()
        assertTrue(outcome.isNotEmpty(), "the worker reported how the run ended")
        assertEquals(emptyList(), outcome.filter { it.level == Level.ERROR }.map { it.formattedMessage },
            "a cancel the caller asked for is not a provider failure")
    }

    @Test
    fun `cancelling the handle completes the session instead of failing it`() {
        val session = CollectingSession("cancel-1")
        val handle = runtime.executeWithHandle(context(), session)
        assertTrue(engaged.await(5, TimeUnit.SECONDS), "the model call is in flight")

        handle.cancel()
        release.countDown()

        assertTrue(session.await(Duration.ofSeconds(5)), "the session reached a terminal state")
        assertFalse(session.failed(), "a cancelled run ends the session, it does not fail it: "
            + session.failure())
        assertEquals(emptyList(), awaitOutcome().filter { it.level == Level.ERROR }.map { it.formattedMessage },
            "a cancel the caller asked for is not a provider failure")
    }

    /**
     * A model call that blocks off the `runBlocking` thread and ignores
     * cancellation, as a provider HTTP read does: the `runBlocking` thread is
     * parked in `joinBlocking` when the cancel lands.
     */
    private class StalledModelExecutor(
        private val engaged: CountDownLatch,
        private val release: CountDownLatch
    ) : PromptExecutor() {
        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> = flow {
            withContext(Dispatchers.IO) {
                engaged.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            emit(StreamFrame.TextDelta("late"))
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
