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
import org.atmosphere.ai.tool.ToolDefinition
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
 * Cancelling a run that has tools, as the admin Sessions tab does on a
 * tool-calling agent (`POST /api/admin/sessions/{uuid}/cancel-inflight`):
 * the run goes through Koog's `AIAgent`, whose run session logs every exception
 * escaping the run, a coroutine cancellation included, as
 * `ERROR … Execution exception reported by server!`. A cancel the operator asked
 * for must end the run without that ERROR, and end the session, not fail it.
 */
class KoogCancelledToolRunLoggingTest {

    private val release = CountDownLatch(1)
    private val engaged = CountDownLatch(1)
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var loggers: List<Logger>
    private lateinit var environment: AiEnvironment
    private lateinit var runtime: KoogAgentRuntime

    @BeforeEach
    fun setUp() {
        appender = ListAppender<ILoggingEvent>().also { it.start() }
        // The runtime's own logger, and Koog's: the agent's run session logs on it.
        loggers = listOf(
            LoggerFactory.getLogger(KoogAgentRuntime::class.java) as Logger,
            LoggerFactory.getLogger("ai.koog") as Logger
        )
        loggers.forEach { it.addAppender(appender) }
        environment = AiEnvironment.create { null }
        KoogAgentRuntime.setPromptExecutor(environment, StalledModelExecutor(engaged, release))
        runtime = KoogAgentRuntime().also { it.attach(environment) }
    }

    @AfterEach
    fun tearDown() {
        release.countDown()
        loggers.forEach { it.detachAppender(appender) }
        environment.close()
    }

    private fun context(): AgentExecutionContext {
        val tool = ToolDefinition.builder("echo", "Echo a value")
            .parameter("value", "the value", "string")
            .executor { args -> "echo:${args["value"]}" }
            .build()
        return AgentExecutionContext(
            "Evaluate this startup idea.", "You are a CEO", null,
            null, "session-1", "user-1", "conv-1",
            listOf(tool), null, null,
            emptyList<org.atmosphere.ai.ContextProvider>(),
            emptyMap<String, Any>(),
            emptyList<org.atmosphere.ai.llm.ChatMessage>(),
            null, null
        ).withRetryPolicy(org.atmosphere.ai.RetryPolicy.NONE)
    }

    @Test
    fun `cancelling a tool-calling run ends it without a Koog ERROR`() {
        val session = CollectingSession("cancel-tools-1")
        val handle = runtime.executeWithHandle(context(), session)
        assertTrue(engaged.await(10, TimeUnit.SECONDS), "the model call is in flight")

        handle.cancel()
        release.countDown()

        assertTrue(session.await(Duration.ofSeconds(10)), "the session reached a terminal state")
        assertFalse(session.failed(), "a cancelled run ends the session, it does not fail it: "
            + session.failure())
        // Koog logs on the worker after the session ends: give it the time to.
        Thread.sleep(500)
        assertEquals(emptyList(),
            appender.list.filter { it.level.isGreaterOrEqual(Level.ERROR) }
                .map { "${it.loggerName}: ${it.formattedMessage}" },
            "a cancel the operator asked for is not a failure")
    }

    /**
     * A model call that blocks off the `runBlocking` thread and ignores
     * cancellation, as a provider HTTP read does.
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
        ): Message.Assistant {
            withContext(Dispatchers.IO) {
                engaged.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            return Message.Assistant(content = "late", metaInfo = ResponseMetaInfo.Empty)
        }

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            ModerationResult(false, emptyMap<ModerationCategory, ModerationCategoryResult>())

        override fun close() {}
    }
}
