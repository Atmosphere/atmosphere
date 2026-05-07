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
package org.atmosphere.ai.lineage;

import org.atmosphere.ai.TokenUsage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One lineage record per {@code @Prompt} invocation. Ties together the
 * user-facing prompt, the tool calls invoked by the LLM, the RAG sources
 * retrieved, and the resulting cost / token usage so a regulated-industry
 * auditor can answer the question "what produced this AI output?" without
 * crossing five log streams.
 *
 * <p>Persisted by {@link LineageRecorder}. Default in-memory implementation
 * ships in {@link InMemoryLineageRecorder}; production deployments register
 * a sink that writes to Kafka / Postgres / S3.</p>
 *
 * @param requestId       unique per prompt (UUID); ties together every
 *                        {@code AiEvent} emitted during the call
 * @param userId          the resolved user id (may be empty when unauthenticated)
 * @param agentId         the agent / endpoint identifier
 * @param conversationId  the conversation id (defaults to session uuid)
 * @param sessionId       the streaming session id
 * @param timestamp       prompt-start instant (UTC)
 * @param userPrompt      the user's prompt text — truncated to
 *                        {@link #MAX_PROMPT_CHARS} on overflow so audit
 *                        sinks don't pay storage for unbounded payloads
 * @param toolCalls       ordered list of tool invocations in this prompt
 * @param ragSources      list of source identifiers retrieved by RAG
 *                        context providers (empty when no RAG is wired)
 * @param tokens          token usage if the runtime reported it
 * @param cost            attributed cost in USD (if a {@code CostAccountant}
 *                        was wired)
 * @param terminalReason  {@code OK} / {@code CANCELLED} / {@code ERROR} /
 *                        {@code DENIED} — mirrors
 *                        {@link org.atmosphere.ai.ExecutionHandle.TerminalReason}
 *                        with the addition of {@code DENIED} for guardrail /
 *                        scope / authorization rejections
 */
public record LineageEntry(
        String requestId,
        String userId,
        String agentId,
        String conversationId,
        String sessionId,
        Instant timestamp,
        String userPrompt,
        List<ToolCall> toolCalls,
        List<String> ragSources,
        Optional<TokenUsage> tokens,
        Optional<BigDecimal> cost,
        String terminalReason,
        Map<String, String> attributes) {

    /**
     * Maximum length of the {@link #userPrompt} field. Anything longer is
     * truncated with an ellipsis to bound storage costs.
     */
    public static final int MAX_PROMPT_CHARS = 4096;

    public LineageEntry {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be null or blank");
        }
        userId = userId == null ? "" : userId;
        agentId = agentId == null ? "" : agentId;
        conversationId = conversationId == null ? "" : conversationId;
        sessionId = sessionId == null ? "" : sessionId;
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp must not be null");
        }
        userPrompt = truncate(userPrompt == null ? "" : userPrompt);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        ragSources = ragSources == null ? List.of() : List.copyOf(ragSources);
        tokens = tokens == null ? Optional.empty() : tokens;
        cost = cost == null ? Optional.empty() : cost;
        terminalReason = terminalReason == null ? "OK" : terminalReason;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    private static String truncate(String s) {
        if (s.length() <= MAX_PROMPT_CHARS) {
            return s;
        }
        return s.substring(0, MAX_PROMPT_CHARS - 1) + "…";
    }

    /**
     * One tool invocation captured during the prompt's lifetime. Built from
     * the {@link org.atmosphere.ai.AiEvent.ToolStart} /
     * {@link org.atmosphere.ai.AiEvent.ToolResult} events emitted by the
     * shared {@code ToolExecutionHelper.executeWithApproval} dispatch seam.
     */
    public record ToolCall(
            String name,
            Instant invokedAt,
            String resultSummary) {
        public ToolCall {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("tool name must not be null or blank");
            }
            if (invokedAt == null) {
                throw new IllegalArgumentException("invokedAt must not be null");
            }
            resultSummary = resultSummary == null ? "" : truncateSummary(resultSummary);
        }

        private static String truncateSummary(String s) {
            // Tool results can include the full tool output. Bound them to a
            // reasonable size for the audit row — operators can still join
            // back to the per-event log for the full payload.
            int max = 1024;
            if (s.length() <= max) {
                return s;
            }
            return s.substring(0, max - 1) + "…";
        }
    }
}
