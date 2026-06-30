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
package org.atmosphere.ai.resume;

import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.llm.ChatMessage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One immutable entry in a run's append-only effect history — the Temporal/DBOS
 * event-history record that makes an agent run deterministically replayable.
 *
 * <p>The journal folds these in {@code seq} order to reconstruct what a run did.
 * {@code seq} (not {@code recordedAt}) is the authoritative ordering key: two
 * effects can share an {@link Instant}, so wall-clock time is never trusted for
 * ordering.</p>
 *
 * <p>All payloads are {@link String}s the journal (de)serializes against
 * <em>known</em> record types — {@link RunSeed} for {@link EffectKind#RUN_INPUT},
 * {@link RecordedRound} for {@link EffectKind#LLM_ROUND}, and a plain result
 * string for {@link EffectKind#TOOL_CALL}/{@link EffectKind#APPROVAL} — so the
 * generic-{@code Object} deserialization caveat of the checkpoint store never
 * applies here.</p>
 *
 * @param runId         the run this effect belongs to (the journal partition key)
 * @param seq           per-run monotonic sequence; the ordered-fold key
 * @param kind          what kind of effect this is
 * @param idempotencyKey content-or-position-derived key; unique per (runId, key)
 * @param status        two-phase lifecycle status
 * @param requestDigest sha-256 of the effect inputs; a replay-divergence tripwire
 * @param resultPayload the recorded result (null while {@code PENDING})
 * @param recordedAt    wall-clock capture time (audit only, never an ordering key)
 * @since 4.0
 */
public record EffectRecord(
        String runId,
        long seq,
        EffectKind kind,
        String idempotencyKey,
        EffectStatus status,
        String requestDigest,
        String resultPayload,
        Instant recordedAt) {

    public EffectRecord {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }

    /**
     * The recorded input seed for a run ({@link EffectKind#RUN_INPUT} payload),
     * captured at the runtime dispatch boundary. A crash-resume re-drives the
     * runtime directly from this seed — it never re-invokes the {@code @Prompt}
     * method body — so deterministic replay holds for everything routed through
     * the journaled seams.
     *
     * @param model        the model the run dispatched against
     * @param messages     the full chat history at dispatch (system + user + prior)
     * @param toolsDigest  a stable digest of the available tool set
     * @param responseType the declared response type's name, or {@code null}
     * @param conversationId the conversation the run belongs to, or {@code null}
     * @param userId       the run's principal; a resume request from a different
     *                     principal is refused before any effect is replayed (Inv #6)
     * @param endpointPath the {@code @AiEndpoint} path the run was driven against,
     *                     so a resume can resolve the live tool set / runtime
     */
    public record RunSeed(
            String model,
            List<ChatMessage> messages,
            String toolsDigest,
            String responseType,
            String conversationId,
            String userId,
            String endpointPath) {

        public RunSeed {
            messages = messages != null ? List.copyOf(messages) : List.of();
        }
    }

    /**
     * The recorded output of one LLM round ({@link EffectKind#LLM_ROUND}
     * payload). On replay the BuiltIn loop re-emits {@code assistantText} and
     * rebuilds its tool-call accumulators from {@code toolCalls} with no
     * provider HTTP call.
     *
     * @param assistantText the assistant's text for the round (may be {@code null})
     * @param toolCalls     the tool calls the model emitted this round
     * @param usage         the token usage reported for the round (may be {@code null})
     */
    public record RecordedRound(
            String assistantText,
            List<ChatMessage.ToolCall> toolCalls,
            TokenUsage usage) {

        public RecordedRound {
            toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
        }
    }
}
