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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Persistable agent state captured at a logical pause point — the data
 * type behind Bonér's "agent saves state and vanishes from RAM while
 * awaiting human approval (days), then instantly resumes when signaled"
 * pattern.
 *
 * <p>The snapshot captures only the bits that survive a JVM restart:
 * conversation history, the in-flight message, identity columns, and a
 * subset of metadata limited to JSON-friendly String values. Runtime
 * references — tools, memory provider, listeners, multi-modal binary
 * parts, approval strategy, retry policy — are intentionally NOT
 * serialized; the caller reinjects them when reviving the agent (see
 * {@link AgentRuntime#resume}).</p>
 *
 * <p>Stored in a {@code CheckpointStore} via
 * {@link AgentRuntime#passivate(AgentExecutionContext, org.atmosphere.checkpoint.CheckpointStore, String)},
 * loaded back via
 * {@link AgentRuntime#resume(org.atmosphere.checkpoint.CheckpointStore, org.atmosphere.checkpoint.CheckpointId, String, AgentExecutionContext, StreamingSession)}.
 * The Sqlite store deserializes through Jackson; the in-memory store
 * preserves references — both shapes round-trip this record because the
 * record only uses Strings, Maps&lt;String,String&gt;, and the
 * already-Jackson-friendly {@link ChatMessage} record.</p>
 *
 * @param runtimeName       name of the runtime that produced the snapshot
 * @param pendingMessage    the in-flight user message that triggered the pause
 * @param systemPrompt      system prompt at pause time
 * @param model             model identifier at pause time
 * @param agentId           agent identifier (may be {@code null})
 * @param sessionId         session identifier (may be {@code null})
 * @param userId            user identifier (may be {@code null})
 * @param conversationId    conversation identifier (may be {@code null})
 * @param history           full conversation history at pause time
 * @param metadata          serializable metadata subset (only String values)
 * @param responseTypeName  fully-qualified class name of the response type, or
 *                          {@code null} for unstructured output
 * @param reason            why the agent paused (audit / observability)
 * @param pausedAt          wall-clock pause time
 */
public record AgentSnapshot(
        String runtimeName,
        String pendingMessage,
        String systemPrompt,
        String model,
        String agentId,
        String sessionId,
        String userId,
        String conversationId,
        List<ChatMessage> history,
        Map<String, String> metadata,
        String responseTypeName,
        String reason,
        Instant pausedAt
) {

    public AgentSnapshot {
        Objects.requireNonNull(runtimeName, "runtimeName");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(pausedAt, "pausedAt");
        history = history != null ? List.copyOf(history) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * Capture the persistable subset of {@code context}. Filters
     * {@code metadata} to only String-valued entries so the record is
     * JSON-clean for the Sqlite checkpoint store.
     *
     * @param runtimeName the {@link AgentRuntime#name()}
     * @param context     the execution context to snapshot
     * @param reason      a human-readable pause reason
     */
    public static AgentSnapshot from(String runtimeName,
                                     AgentExecutionContext context,
                                     String reason) {
        Objects.requireNonNull(context, "context");
        var stringMetadata = new java.util.LinkedHashMap<String, String>();
        for (var entry : context.metadata().entrySet()) {
            if (entry.getValue() instanceof String s) {
                stringMetadata.put(entry.getKey(), s);
            }
        }
        return new AgentSnapshot(
                runtimeName,
                context.message(),
                context.systemPrompt(),
                context.model(),
                context.agentId(),
                context.sessionId(),
                context.userId(),
                context.conversationId(),
                context.history(),
                stringMetadata,
                context.responseType() != null ? context.responseType().getName() : null,
                reason,
                Instant.now());
    }
}
