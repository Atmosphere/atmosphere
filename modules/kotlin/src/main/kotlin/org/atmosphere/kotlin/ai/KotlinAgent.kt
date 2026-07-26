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
package org.atmosphere.kotlin.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.atmosphere.ai.AgentRuntime
import org.atmosphere.ai.AiConversationMemory
import org.atmosphere.ai.AiPipeline
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.processor.AiEndpointHandler
import org.atmosphere.ai.tool.ToolRegistry
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * A live agent declared with the Kotlin [agent] DSL and wired into a running
 * [org.atmosphere.cpr.AtmosphereFramework].
 *
 * The agent owns exactly the two framework seams an annotated `@Agent` owns:
 *
 * * [handler] — the [AiEndpointHandler] registered at [path]. This is the same
 *   handler class `AgentProcessor` and `AiEndpointProcessor` build, so the
 *   browser/WebSocket path (guardrails, interceptors, memory, tools, metrics,
 *   frame shape) is byte-for-byte the annotation path.
 * * [pipeline] — the [AiPipeline] used for programmatic and protocol-side
 *   dispatch (the same object the A2A / AG-UI / channel surfaces run on).
 *
 * [ask] and [stream] go through [pipeline], so a Kotlin coroutine caller and an
 * HTTP caller traverse the same governance and memory stack (Correctness
 * Invariant #7, Mode Parity).
 */
class KotlinAgent internal constructor(
    val name: String,
    val path: String,
    val runtime: AgentRuntime,
    val pipeline: AiPipeline,
    val handler: AiEndpointHandler,
    val tools: ToolRegistry,
    val memory: AiConversationMemory?
) {

    /**
     * Stream the agent's answer as it is produced.
     *
     * Each emitted element is one delta exactly as the runtime produced it. The
     * flow completes when the runtime completes the session, and fails with the
     * runtime's own throwable when the session errors — no swallowed failures.
     *
     * ```kotlin
     * assistant.stream("user-42", "summarise the incident").collect { delta ->
     *     resource.writeSuspend(delta)
     * }
     * ```
     *
     * @param conversationId memory key — typically the client/user identifier
     */
    fun stream(conversationId: String, message: String): Flow<String> = channelFlow {
        val session = ChannelStreamingSession(channel)
        withContext(Dispatchers.IO) {
            try {
                pipeline.execute(conversationId, message, session)
            } finally {
                // Defensive terminal path: a runtime that returns without
                // completing must still release the collector.
                session.closeIfOpen()
            }
        }
    }.buffer(DELTA_BUFFER)

    /**
     * Collect the agent's full answer. Suspends until the runtime completes.
     */
    suspend fun ask(conversationId: String, message: String): String {
        val answer = StringBuilder()
        stream(conversationId, message).collect { answer.append(it) }
        return answer.toString()
    }

    private companion object {
        /**
         * Bounded hand-off between the blocking runtime thread and the
         * collector. When it fills, [ChannelStreamingSession] blocks the
         * producer instead of dropping tokens or growing without bound
         * (Correctness Invariant #3).
         */
        const val DELTA_BUFFER = 256
    }
}

/**
 * Bridges the framework's push-style [StreamingSession] onto a coroutine
 * channel so Kotlin callers can `collect` the answer.
 */
private class ChannelStreamingSession(
    private val sink: SendChannel<String>
) : StreamingSession {

    private val id = UUID.randomUUID().toString()

    @Volatile
    private var deltas = 0

    @Volatile
    private var closed = false

    override fun sessionId(): String = id

    override fun send(text: String?) {
        if (text.isNullOrEmpty() || closed) {
            return
        }
        deltas++
        emit(text)
    }

    override fun sendMetadata(key: String?, value: Any?) {
        // Metadata (token counts, cache hits, tool events) is observability,
        // not answer text — collectors of this flow asked for the answer.
    }

    override fun progress(message: String?) {
        // Progress notes are status, not answer text.
    }

    override fun complete() {
        closed = true
        sink.close()
    }

    override fun complete(summary: String?) {
        // complete(summary) carries the aggregated response. When the runtime
        // already streamed deltas the summary repeats them, so emit it only
        // when nothing was streamed (non-streaming runtimes take this path).
        if (deltas == 0 && !summary.isNullOrBlank()) {
            emit(summary)
        }
        closed = true
        sink.close()
    }

    override fun error(t: Throwable?) {
        closed = true
        sink.close(t ?: IllegalStateException("AI session failed without a cause"))
    }

    override fun isClosed(): Boolean = closed

    /** Close the channel if the runtime never reached a terminal callback. */
    fun closeIfOpen() {
        if (!closed) {
            closed = true
            sink.close()
        }
    }

    private fun emit(text: String) {
        val result = sink.trySend(text)
        if (result.isSuccess) {
            return
        }
        if (result.isClosed) {
            // The collector cancelled; stop pushing rather than throwing on the
            // runtime's thread.
            closed = true
            return
        }
        // Buffer full — block this (runtime-owned, blocking) thread until the
        // collector drains. Real backpressure beats both dropping tokens and an
        // unbounded queue.
        try {
            runBlocking { sink.send(text) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            closed = true
        } catch (e: Exception) {
            logger.debug("Delta dropped: streaming channel closed while sending", e)
            closed = true
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(ChannelStreamingSession::class.java)
    }
}
