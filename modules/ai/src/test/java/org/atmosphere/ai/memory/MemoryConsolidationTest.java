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
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryConsolidationTest {

    /** Minimal AgentRuntime that replies with one canned response. */
    private static AgentRuntime stubRuntime(String response) {
        return new AgentRuntime() {
            @Override public String name() { return "stub"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return -1; }
            @Override public void configure(AiConfig.LlmSettings settings) { }
            @Override public Set<AiCapability> capabilities() { return Set.of(); }
            @Override public void execute(AgentExecutionContext ctx, StreamingSession session) {
                session.send(response);
                session.complete();
            }
        };
    }

    // ── Strategy gating ──

    @Test
    void disabledNeverTriggersAndReturnsFactsUntouched() {
        var s = MemoryConsolidationStrategy.disabled();
        assertFalse(s.shouldConsolidate("u", 1000));
        var facts = List.of("a", "b");
        assertEquals(facts, s.consolidate(facts, stubRuntime("[\"x\"]")));
    }

    @Test
    void whenExceedingTriggersAtThreshold() {
        var s = MemoryConsolidationStrategy.whenExceeding(5);
        assertFalse(s.shouldConsolidate("u", 4));
        assertTrue(s.shouldConsolidate("u", 5));
        assertTrue(s.shouldConsolidate("u", 9));
    }

    @Test
    void whenExceedingRejectsNonPositive() {
        assertThrows(IllegalArgumentException.class, () -> MemoryConsolidationStrategy.whenExceeding(0));
        assertThrows(IllegalArgumentException.class, () -> MemoryConsolidationStrategy.whenExceeding(3, 0));
    }

    // ── LLM consolidation ──

    @Test
    void consolidateMergesViaModelOutput() {
        var s = MemoryConsolidationStrategy.whenExceeding(2);
        var facts = List.of(
                "Has a dog named Max",
                "Owns a golden retriever named Max",
                "Lives in Montreal",
                "Moved to Seattle");
        var merged = s.consolidate(facts,
                stubRuntime("[\"Has a golden retriever named Max\", \"Lives in Seattle (moved from Montreal)\"]"));
        assertEquals(List.of("Has a golden retriever named Max", "Lives in Seattle (moved from Montreal)"), merged);
    }

    @Test
    void consolidateKeepsOriginalWhenModelWouldGrowTheSet() {
        var s = MemoryConsolidationStrategy.whenExceeding(2);
        var facts = List.of("a", "b");
        // Model returns MORE than the input — must be rejected (no growth).
        var result = s.consolidate(facts, stubRuntime("[\"a\", \"b\", \"c\", \"d\"]"));
        assertEquals(facts, result);
    }

    @Test
    void consolidateKeepsOriginalOnGarbledOutput() {
        var s = MemoryConsolidationStrategy.whenExceeding(2);
        var facts = List.of("a", "b", "c");
        assertEquals(facts, s.consolidate(facts, stubRuntime("not json at all")));
    }

    @Test
    void consolidateCapsResult() {
        var s = MemoryConsolidationStrategy.whenExceeding(2, 2);
        var facts = List.of("a", "b", "c", "d");
        var result = s.consolidate(facts, stubRuntime("[\"a\", \"b\", \"c\"]"));
        assertEquals(2, result.size(), "result is capped at maxConsolidatedFacts");
    }

    @Test
    void consolidateSkipsWhenFewerThanTwoFacts() {
        var s = MemoryConsolidationStrategy.whenExceeding(1);
        var one = List.of("only");
        // No model call needed; nothing to merge.
        assertEquals(one, s.consolidate(one, stubRuntime("[\"x\"]")));
    }

    // ── LongTermMemory SPI additions ──

    @Test
    void inMemoryReplaceFactsAndFactCount() {
        var mem = new InMemoryLongTermMemory(100);
        mem.saveFacts("u", List.of("a", "b", "c"));
        assertEquals(3, mem.factCount("u"));

        mem.replaceFacts("u", List.of("merged-1", "merged-2"));
        assertEquals(2, mem.factCount("u"));
        assertEquals(List.of("merged-1", "merged-2"), mem.getFacts("u", 100));

        mem.replaceFacts("u", List.of());
        assertEquals(0, mem.factCount("u"), "empty replacement clears the user");
    }

    @Test
    void replaceFactsHonoursMaxFactsBound() {
        var mem = new InMemoryLongTermMemory(2);
        mem.replaceFacts("u", List.of("a", "b", "c", "d"));
        assertEquals(2, mem.factCount("u"), "replacement is bounded by maxFacts (oldest dropped)");
        assertEquals(List.of("c", "d"), mem.getFacts("u", 100));
    }

    // ── Interceptor wiring ──

    @Test
    void interceptorConsolidatesAfterExtractionWhenThresholdHit() {
        var mem = new InMemoryLongTermMemory(100);
        mem.saveFacts("alice", List.of("old-1", "old-2"));

        // Stub extraction returns two new facts → total 4 ≥ threshold 3.
        MemoryExtractionStrategy extract = new MemoryExtractionStrategy() {
            @Override public boolean shouldExtract(String c, String m, int n) { return false; }
            @Override public List<String> extractFacts(String text, AgentRuntime r) {
                return List.of("new-1", "new-2");
            }
        };
        MemoryConsolidationStrategy consolidate = new MemoryConsolidationStrategy() {
            @Override public boolean shouldConsolidate(String userId, int count) { return count >= 3; }
            @Override public List<String> consolidate(List<String> facts, AgentRuntime r) {
                return List.of("consolidated");
            }
        };
        var interceptor = new LongTermMemoryInterceptor(
                mem, extract, stubRuntime("[]"), 20, consolidate);

        interceptor.onDisconnect("alice", "conv-1",
                List.of(new org.atmosphere.ai.llm.ChatMessage("user", "hi")));

        assertEquals(List.of("consolidated"), mem.getFacts("alice", 100),
                "consolidation replaced the fact set after extraction crossed the threshold");
    }

    @Test
    void interceptorDoesNotConsolidateByDefault() {
        var mem = new InMemoryLongTermMemory(100);
        MemoryExtractionStrategy extract = new MemoryExtractionStrategy() {
            @Override public boolean shouldExtract(String c, String m, int n) { return false; }
            @Override public List<String> extractFacts(String text, AgentRuntime r) {
                return List.of("f1", "f2", "f3", "f4", "f5");
            }
        };
        // Default constructor → consolidation disabled.
        var interceptor = new LongTermMemoryInterceptor(mem, extract, stubRuntime("[]"));
        interceptor.onDisconnect("bob", "conv-2",
                List.of(new org.atmosphere.ai.llm.ChatMessage("user", "hi")));

        assertEquals(5, mem.factCount("bob"), "no consolidation runs unless opted in");
    }
}
