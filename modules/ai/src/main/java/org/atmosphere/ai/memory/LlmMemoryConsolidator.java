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
package org.atmosphere.ai.memory;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.StreamingSession;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM-backed {@link MemoryConsolidationStrategy}. When a user's fact count
 * reaches the configured threshold, asks a model to merge duplicates, resolve
 * contradictions (keeping the most recent — facts are presented oldest-first),
 * and drop redundancy, returning a smaller high-signal set.
 *
 * <h2>Safety</h2>
 *
 * Consolidation is best-effort and conservative: on any model error, timeout,
 * empty result, or a result that would <em>grow</em> the set, the caller keeps
 * the original facts (the interceptor only applies a non-empty, no-larger
 * result). The model output is parsed as a JSON array of strings and capped at
 * {@code maxConsolidatedFacts} (Correctness Invariant #3 — the consolidated
 * set is bounded).
 */
final class LlmMemoryConsolidator implements MemoryConsolidationStrategy {

    /** Default upper bound on the consolidated fact count. */
    static final int DEFAULT_MAX_CONSOLIDATED_FACTS = 50;

    private static final String CONSOLIDATION_PROMPT = """
            You are consolidating long-term memory about a single user. Below are
            the stored facts, oldest first. Produce a cleaned set that:
            - merges duplicates and near-duplicates into one fact,
            - resolves contradictions by keeping the most recent (later) fact,
            - drops trivia and conversational filler,
            - preserves every distinct, durable fact.
            Return ONLY a JSON array of short factual statements, no prose. The
            result MUST NOT be longer than the input. Example:
            ["User's name is Alice", "Lives in Seattle (moved from Montreal)", "Has a golden retriever named Max"]

            Facts:
            %s""";

    private final int threshold;
    private final int maxConsolidatedFacts;

    LlmMemoryConsolidator(int threshold) {
        this(threshold, DEFAULT_MAX_CONSOLIDATED_FACTS);
    }

    LlmMemoryConsolidator(int threshold, int maxConsolidatedFacts) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be > 0, got " + threshold);
        }
        if (maxConsolidatedFacts <= 0) {
            throw new IllegalArgumentException(
                    "maxConsolidatedFacts must be > 0, got " + maxConsolidatedFacts);
        }
        this.threshold = threshold;
        this.maxConsolidatedFacts = maxConsolidatedFacts;
    }

    @Override
    public boolean shouldConsolidate(String userId, int currentFactCount) {
        return currentFactCount >= threshold;
    }

    @Override
    public List<String> consolidate(List<String> facts, AgentRuntime runtime) {
        if (facts == null || facts.size() < 2 || runtime == null) {
            return facts == null ? List.of() : facts;
        }

        var numbered = new StringBuilder();
        for (var i = 0; i < facts.size(); i++) {
            numbered.append(i + 1).append(". ").append(facts.get(i)).append('\n');
        }

        // Fact consolidation has no tool list, so HITL gating is a no-op; use the
        // 15-arg form with an explicit null ApprovalStrategy to stay off the
        // deprecated 14-arg shim (mirrors OnSessionCloseStrategy).
        var context = new AgentExecutionContext(
                CONSOLIDATION_PROMPT.formatted(numbered.toString()),
                "You are a memory consolidation assistant. Respond with a JSON array only.",
                null, null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(), null,
                (org.atmosphere.ai.approval.ApprovalStrategy) null);

        var text = new AtomicReference<>(new StringBuilder());
        var latch = new CountDownLatch(1);
        runtime.execute(context, new StreamingSession() {
            @Override public String sessionId() { return "memory-consolidation"; }
            @Override public void send(String chunk) { text.get().append(chunk); }
            @Override public void sendMetadata(String key, Object value) { }
            @Override public void progress(String message) { }
            @Override public void complete() { latch.countDown(); }
            @Override public void complete(String summary) {
                if (summary != null) {
                    text.set(new StringBuilder(summary));
                }
                latch.countDown();
            }
            @Override public void error(Throwable t) { latch.countDown(); }
            @Override public boolean isClosed() { return latch.getCount() == 0; }
        });

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return facts;
        }

        var parsed = OnSessionCloseStrategy.parseJsonArray(text.get().toString());
        if (parsed.isEmpty() || parsed.size() > facts.size()) {
            // Empty/garbled, or the model tried to grow the set — keep the
            // original facts rather than corrupt or inflate the store.
            return facts;
        }
        if (parsed.size() > maxConsolidatedFacts) {
            return List.copyOf(parsed.subList(0, maxConsolidatedFacts));
        }
        return parsed;
    }
}
