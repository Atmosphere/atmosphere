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

import com.embabel.agent.api.tool.callback.AfterLlmCallContext
import com.embabel.agent.api.tool.callback.BeforeLlmCallContext
import com.embabel.agent.api.tool.callback.ToolLoopInspector
import com.embabel.agent.api.tool.callback.ToolLoopTransformer
import com.embabel.chat.AssistantMessage
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.Message
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.llm.ToolLoopPolicy
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Native [ToolLoopPolicy] enforcement inside Embabel's tool loop, built on
 * the Embabel 0.5.0 tool-callback API.
 * [EmbabelAgentRuntime.executeAtmosphereNative] creates one bridge per
 * dispatch when the request metadata carries a policy and registers it via
 * both `PromptRunner.withToolLoopInspectors` and
 * `PromptRunner.withToolLoopTransformers`.
 *
 * **Why two callback roles.** Embabel's [ToolLoopInspector] is observe-only:
 * every hook returns `void` and the loop's `notify*` helpers
 * (`ToolLoopCallbackSupport`) swallow inspector exceptions with a WARN, so
 * an inspector cannot abort the loop. The genuine stop mechanism is the
 * [ToolLoopTransformer]: `DefaultToolLoop` decides loop continuation on the
 * *transformed* response of each LLM call (`hasToolCalls` — the message must
 * be an [AssistantMessageWithToolCalls] with a non-empty tool-call list), so
 * returning a plain [AssistantMessage] with the tool calls stripped ends the
 * loop after the current call. `ParallelToolLoop` extends `DefaultToolLoop`
 * and inherits the same seam, so both loop types are covered.
 *
 *  - [transformAfterLlmCall] (transformer) is the enforcement seam: once
 *    the cap-th LLM response still requests tool calls, the calls are
 *    stripped so the loop dispatches no further rounds.
 *  - [beforeLlmCall] (inspector) is the defensive mirror of the wire-layer
 *    `ToolLoopGuard.onModelStart`: if the loop somehow dispatches past the
 *    cap anyway, a `FAIL` policy still fails the session with the same
 *    [ToolLoopPolicy.ToolLoopExhaustedException] frame the guard emits.
 *
 * **Counting model.** Matches the wire guard's documented Embabel counting:
 * the cap applies to total LLM calls per dispatch — strictly stronger than
 * the rounds-only interpretation, which is the safer direction for a cap.
 * Each seam keeps its own counter so neither depends on the other being
 * registered or on Embabel's notify-before-apply call order.
 *
 * **Breach reporting** mirrors the wire-layer `ToolLoopGuard` exactly:
 * [ToolLoopPolicy.OnMaxIterations.FAIL] fires
 * `session.error(ToolLoopExhaustedException(cap))` once, so callers observe
 * the identical error frame on either enforcement layer — the log line notes
 * the native path. [ToolLoopPolicy.OnMaxIterations.COMPLETE_WITHOUT_TOOLS]
 * — a no-op for the wire guard, which cannot reach inside a framework's
 * loop — is honored natively here: the loop completes with the model's last
 * text and the session stays open.
 *
 * **The replacement message is guaranteed non-blank.** A blank final message
 * would trip Embabel's `EmptyResponsePolicy`, whose feedback action re-enters
 * the loop with another LLM call — the exact overrun the policy caps.
 * Fallback order: the stripped response's own text, then the last non-blank
 * assistant text in the loop history, then a fixed cap notice.
 *
 * Dispatch-scoped: one bridge per request, it dies with the request's
 * [StreamingSession] — no registration outlives the dispatch (Correctness
 * Invariant #1). Counters are atomic because `ParallelToolLoop` runs tool
 * executions concurrently.
 */
internal class EmbabelToolLoopBridge(
    private val policy: ToolLoopPolicy,
    private val session: StreamingSession
) : ToolLoopInspector, ToolLoopTransformer {

    companion object {
        private val logger = LoggerFactory.getLogger(EmbabelToolLoopBridge::class.java)
    }

    /** LLM dispatches observed on the inspector seam ([beforeLlmCall]). */
    private val dispatchCount = AtomicInteger()

    /** LLM responses observed on the transformer seam ([transformAfterLlmCall]). */
    private val responseCount = AtomicInteger()

    /** Close-once latch for the breach frame (Correctness Invariant #2). */
    private val tripped = AtomicBoolean()

    /**
     * Inspector seam — pure observation by API contract (Embabel swallows
     * inspector exceptions). Mirrors `ToolLoopGuard.onModelStart` natively:
     * dispatching past the cap means the transformer seam did not stop the
     * loop, so surface the breach on the session exactly like the wire guard.
     */
    override fun beforeLlmCall(context: BeforeLlmCallContext) {
        val count = dispatchCount.incrementAndGet()
        if (count <= policy.maxIterations()) {
            return
        }
        breach(count, "beforeLlmCall backstop")
    }

    /**
     * Transformer seam — the genuine native stop. Embabel's loop continues
     * only when the message returned here still carries tool calls, so at
     * the cap the calls are stripped and the loop completes with the
     * replacement text. Stripping stays idempotent past the cap (a feedback
     * re-entry keeps being refused) while the breach frame fires once.
     */
    override fun transformAfterLlmCall(context: AfterLlmCallContext): Message {
        val calls = responseCount.incrementAndGet()
        val response = context.response
        if (response !is AssistantMessageWithToolCalls || response.toolCalls.isEmpty()) {
            return response
        }
        if (calls < policy.maxIterations()) {
            return response
        }
        breach(calls, "afterLlmCall transformer")
        return AssistantMessage(replacementText(response, context))
    }

    private fun breach(observedLlmCalls: Int, seam: String) {
        if (!tripped.compareAndSet(false, true)) {
            return
        }
        when (policy.onMaxIterations()) {
            ToolLoopPolicy.OnMaxIterations.FAIL -> {
                logger.info(
                    "ToolLoopPolicy.FAIL tripped natively inside the Embabel tool loop ({}) " +
                        "after {} LLM call(s) (cap={}) — stopping the loop and aborting the " +
                        "session via session.error(...)",
                    seam, observedLlmCalls, policy.maxIterations()
                )
                if (!session.isClosed) {
                    session.error(ToolLoopPolicy.ToolLoopExhaustedException(policy.maxIterations()))
                }
            }
            ToolLoopPolicy.OnMaxIterations.COMPLETE_WITHOUT_TOOLS -> logger.info(
                "ToolLoopPolicy.COMPLETE_WITHOUT_TOOLS reached natively inside the Embabel " +
                    "tool loop ({}) after {} LLM call(s) (cap={}) — completing with the " +
                    "model's last text",
                seam, observedLlmCalls, policy.maxIterations()
            )
        }
    }

    private fun replacementText(
        response: AssistantMessageWithToolCalls,
        context: AfterLlmCallContext
    ): String {
        if (response.content.isNotBlank()) {
            return response.content
        }
        val lastAssistantText = context.history
            .asReversed()
            .firstOrNull { it is AssistantMessage && it.content.isNotBlank() }
            ?.content
        if (lastAssistantText != null) {
            return lastAssistantText
        }
        return "Tool loop stopped: the configured cap of ${policy.maxIterations()} " +
            "iteration(s) was reached before the model finished calling tools."
    }
}
