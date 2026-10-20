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
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.takeWhile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * The [PromptExecutor] a Koog `AIAgent` run talks to, so a cancel the caller
 * asked for ends the run instead of failing it.
 *
 * Koog's agent run session logs every exception that escapes a run, a coroutine
 * cancellation included, as `ERROR … Execution exception reported by server!`
 * and marks the run failed: inside `AIAgent.run` no cancellation is a normal
 * end. So a cancel does not cancel the run's coroutine. It cuts short the model
 * call in flight, which runs in a job of its own, answers it with an empty
 * assistant message, and answers every later call the same way at once: the
 * agent's strategy sees a reply with no tool call and finishes normally. A tool
 * running when the cancel lands runs to its end; the model call after it is
 * answered empty, so nothing more is dispatched.
 */
internal class CancellableAgentExecutor(
    private val delegate: PromptExecutor,
    private val cancelled: AtomicBoolean
) : PromptExecutor() {

    private val calls: MutableSet<Deferred<*>> = ConcurrentHashMap.newKeySet()

    /** Cut short the model calls in flight; [cancelled] answers the later ones. */
    fun cancelCalls() {
        calls.forEach { it.cancel() }
    }

    private fun ended(): Message.Assistant =
        Message.Assistant(content = "", metaInfo = ResponseMetaInfo.Empty)

    /**
     * Run [block] in a job of its own on the caller's dispatcher (same thread,
     * same thread-bound state), so cancelling it is not a cancellation of the
     * agent's run. A cancellation of the caller itself still propagates.
     */
    private suspend fun <T> call(onCancelled: () -> T, block: suspend () -> T): T {
        if (cancelled.get()) return onCancelled()
        val context = currentCoroutineContext()
        val call = CoroutineScope(context.minusKey(Job) + Job()).async { block() }
        calls.add(call)
        try {
            if (cancelled.get()) call.cancel()
            return call.await()
        } catch (ce: CancellationException) {
            if (!context.isActive()) {
                call.cancel()
                context.ensureActive()
            }
            if (cancelled.get()) return onCancelled()
            throw ce
        } finally {
            calls.remove(call)
        }
    }

    private fun kotlin.coroutines.CoroutineContext.isActive(): Boolean =
        this[Job]?.isActive ?: true

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant = call(::ended) { delegate.execute(prompt, model, tools) }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): List<Message.Assistant> =
        call({ listOf(ended()) }) { delegate.executeMultipleChoices(prompt, model, tools) }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> =
        if (cancelled.get()) emptyFlow()
        else delegate.executeStreaming(prompt, model, tools).takeWhile { !cancelled.get() }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        delegate.moderate(prompt, model)

    override suspend fun models(): List<LLModel> = delegate.models()

    override fun getStandardJsonSchemaGenerator(model: LLModel): StandardJsonSchemaGenerator =
        delegate.getStandardJsonSchemaGenerator(model)

    override fun getBasicJsonSchemaGenerator(model: LLModel): BasicJsonSchemaGenerator =
        delegate.getBasicJsonSchemaGenerator(model)

    override fun close() = delegate.close()
}
