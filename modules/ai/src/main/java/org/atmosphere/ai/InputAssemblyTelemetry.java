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
package org.atmosphere.ai;

import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Computes the per-stage character/token breakdown of the input that
 * {@link AiPipeline} hands to the runtime, then routes the breakdown to
 * {@link AiMetrics#recordInputAssembly} so observability backends can show
 * which stage of pipeline assembly dominates the input bill.
 *
 * <p>Motivated by Bai et al., <em>How Do AI Agents Spend Your Money?</em>
 * (arXiv 2604.22750), which showed that input tokens — not output — drive
 * the cost of agentic workloads, and that models cannot reliably predict
 * their own spend before a call. Framework-level instrumentation is the
 * only place that visibility can live, so the pipeline emits it on every
 * turn.</p>
 *
 * <p>Token counts use a {@code chars / 4} heuristic suitable for ranking
 * stages relative to one another. Real billing-grade counts still come from
 * the runtime's post-call usage report (the existing
 * {@code ai.tokens.input}/{@code ai.tokens.output} stream).</p>
 */
public final class InputAssemblyTelemetry {

    private static final Logger logger = LoggerFactory.getLogger(InputAssemblyTelemetry.class);

    /** Developer-supplied system prompt (after ScopePolicy hardening). */
    public static final String STAGE_SYSTEM = "system";

    /** JSON schema text for the tool catalog injected into the model prompt. */
    public static final String STAGE_TOOL_SCHEMA = "tool_schema";

    /** Schema instructions appended for typed structured-output responses. */
    public static final String STAGE_STRUCTURED_OUTPUT_SCHEMA = "structured_output_schema";

    /** Confidence-elicitation cue appended to the system prompt. */
    public static final String STAGE_CONFIDENCE_CUE = "confidence_cue";

    /** Conversation history (memory replay) prepended to the user message. */
    public static final String STAGE_SCROLLBACK = "scrollback";

    /** The user's message itself for this turn. */
    public static final String STAGE_USER_MESSAGE = "user_message";

    private InputAssemblyTelemetry() { }

    /**
     * Estimate the token count for a piece of assembled input. Uses the
     * {@code chars / 4} BPE heuristic: returns 0 when {@code chars == 0},
     * otherwise {@code Math.max(1, chars / 4)} so a non-empty stage never
     * disappears on the wire.
     */
    public static int approximateTokens(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return Math.max(1, chars / 4);
    }

    /**
     * Compute the character count contributed by a tool catalog. Approximates
     * the JSON schema each runtime renders by summing each tool's name,
     * description, and parameter metadata. The exact serialization differs
     * per runtime adapter, but the ratio between tool-catalog cost and other
     * stages is what Bai's paper cares about, so an approximation is fine.
     */
    public static int toolSchemaChars(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (var tool : tools) {
            total += length(tool.name());
            total += length(tool.description());
            total += length(tool.returnType());
            for (ToolParameter p : tool.parameters()) {
                total += length(p.name());
                total += length(p.description());
                total += length(p.type());
            }
        }
        return total;
    }

    /**
     * Sum the {@code content} length across every history message — the
     * scrollback the runtime replays alongside the new turn.
     */
    public static int scrollbackChars(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ChatMessage m : history) {
            total += length(m.content());
        }
        return total;
    }

    /**
     * Emit the full per-stage breakdown for a single turn. Called once per
     * {@link AiPipeline#execute} invocation, immediately before the runtime
     * is dispatched. Null/empty stages are skipped so observers don't get
     * noise for stages this turn didn't use (e.g. no tools, no structured
     * output). A misbehaving {@link AiMetrics} implementation cannot abort
     * the turn — emission failures are logged at debug.
     */
    public static void emit(AiMetrics metrics, String model,
                            String systemPrompt,
                            String structuredOutputSchema,
                            String confidenceCue,
                            List<ToolDefinition> tools,
                            List<ChatMessage> scrollback,
                            String userMessage) {
        if (metrics == null || metrics == AiMetrics.NOOP) {
            return;
        }
        String safeModel = model != null ? model : "unknown";
        try {
            emitStage(metrics, safeModel, STAGE_SYSTEM, length(systemPrompt));
            emitStage(metrics, safeModel, STAGE_TOOL_SCHEMA, toolSchemaChars(tools));
            emitStage(metrics, safeModel, STAGE_STRUCTURED_OUTPUT_SCHEMA, length(structuredOutputSchema));
            emitStage(metrics, safeModel, STAGE_CONFIDENCE_CUE, length(confidenceCue));
            emitStage(metrics, safeModel, STAGE_SCROLLBACK, scrollbackChars(scrollback));
            emitStage(metrics, safeModel, STAGE_USER_MESSAGE, length(userMessage));
        } catch (RuntimeException e) {
            logger.debug("AiMetrics.recordInputAssembly threw; suppressing to keep the turn alive: {}",
                    e.toString());
        }
    }

    private static void emitStage(AiMetrics metrics, String model, String stage, int chars) {
        if (chars <= 0) {
            return;
        }
        metrics.recordInputAssembly(model, stage, approximateTokens(chars), chars);
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }
}
