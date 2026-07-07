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
package org.atmosphere.ai.governance;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.governance.memory.GovernanceFact;
import org.atmosphere.ai.governance.memory.GovernanceMemorySink;
import org.atmosphere.ai.governance.memory.GovernanceProvenanceMemory;
import org.atmosphere.ai.memory.InMemoryLongTermMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceFeedbackInterceptorTest {

    private static final String BASE_PROMPT = "You are a bank access assistant.";

    @BeforeEach
    void install() {
        GovernanceDecisionLog.install(500);
    }

    @AfterEach
    void cleanup() {
        GovernanceDecisionLog.reset();
    }

    /** A request carrying a conversation id and a base system prompt. */
    private static AiRequest request(String conversationId) {
        return new AiRequest("resolve the ticket", BASE_PROMPT, "gpt-4o",
                "user-1", "sess-1", "agent-1", conversationId, Map.of(), List.of());
    }

    private static void record(String decision, String reason, Map<String, Object> snapshot) {
        GovernanceDecisionLog.installed().record(
                GovernanceDecisionLog.entryWithSnapshot(
                        new FakePolicy("p"), snapshot, decision, reason, 0.5));
    }

    private static Map<String, Object> convScope(String conversationId) {
        return Map.of("conversation_id", conversationId);
    }

    @Test
    void injectsPreferGuidanceScopedToConversation() {
        record("prefer", "standing admin grants violate least-privilege",
                Map.of("conversation_id", "conv-1",
                        GovernanceDecisionLog.PREFERRED_KEY, "request a scoped, time-boxed credential"));

        var out = new GovernanceFeedbackInterceptor().preProcess(request("conv-1"), null);

        var prompt = out.systemPrompt();
        assertTrue(prompt.startsWith(BASE_PROMPT), "base prompt is preserved");
        assertTrue(prompt.contains("Governance guidance"), "guidance header present: " + prompt);
        assertTrue(prompt.contains("request a scoped, time-boxed credential"),
                "preferred alternative injected: " + prompt);
        assertTrue(prompt.contains("standing admin grants violate least-privilege"),
                "reason injected: " + prompt);
    }

    @Test
    void injectsDenyGuidance() {
        record("deny", "destructive action: dropping the table is never allowed",
                convScope("conv-1"));

        var out = new GovernanceFeedbackInterceptor().preProcess(request("conv-1"), null);

        assertTrue(out.systemPrompt().contains("was denied"), out.systemPrompt());
        assertTrue(out.systemPrompt().contains("dropping the table is never allowed"),
                out.systemPrompt());
    }

    @Test
    void doesNotLeakGuidanceAcrossConversations() {
        record("prefer", "scoped is preferred",
                Map.of("conversation_id", "conv-OTHER",
                        GovernanceDecisionLog.PREFERRED_KEY, "scoped credential"));

        var req = request("conv-1");
        var out = new GovernanceFeedbackInterceptor().preProcess(req, null);

        // No matching decision for conv-1 — the request is returned untouched.
        assertSame(req, out, "must not inject another conversation's guidance");
    }

    @Test
    void anonymousRequestGetsNoInjection() {
        record("deny", "some reason", convScope("conv-1"));

        var anon = new AiRequest("hello", BASE_PROMPT, "gpt-4o",
                null, null, null, null, Map.of(), List.of());
        var out = new GovernanceFeedbackInterceptor().preProcess(anon, null);

        assertSame(anon, out, "an unscopable turn must inject nothing");
    }

    @Test
    void excludesDryRunShadowEntries() {
        record("dry-run:prefer", "would advise",
                Map.of("conversation_id", "conv-1",
                        GovernanceDecisionLog.PREFERRED_KEY, "scoped credential"));

        var req = request("conv-1");
        var out = new GovernanceFeedbackInterceptor().preProcess(req, null);

        assertSame(req, out, "dry-run shadow advisories must not steer the agent");
    }

    @Test
    void admitAndTransformAreNotInjected() {
        record("admit", "", convScope("conv-1"));
        record("transform", "request rewritten", convScope("conv-1"));

        var req = request("conv-1");
        var out = new GovernanceFeedbackInterceptor().preProcess(req, null);

        assertSame(req, out, "only deny/prefer are feedback-eligible");
    }

    @Test
    void deduplicatesRepeatedGuidance() {
        for (int i = 0; i < 4; i++) {
            record("deny", "least-privilege violation", convScope("conv-1"));
        }

        var out = new GovernanceFeedbackInterceptor().preProcess(request("conv-1"), null);

        var prompt = out.systemPrompt();
        var first = prompt.indexOf("least-privilege violation");
        var last = prompt.lastIndexOf("least-privilege violation");
        assertTrue(first >= 0, "guidance present");
        assertEquals(first, last, "identical guidance must appear exactly once");
    }

    @Test
    void boundsToMaxItems() {
        for (int i = 0; i < 10; i++) {
            record("deny", "distinct reason " + i, convScope("conv-1"));
        }

        var out = new GovernanceFeedbackInterceptor(3, 100).preProcess(request("conv-1"), null);

        var lines = out.systemPrompt().lines()
                .filter(l -> l.startsWith("- "))
                .count();
        assertEquals(3, lines, "injected lines capped at maxItems");
    }

    @Test
    void noEntriesReturnsRequestUnchanged() {
        var req = request("conv-1");
        assertSame(req, new GovernanceFeedbackInterceptor().preProcess(req, null));
    }

    @Test
    void rejectsNonPositiveBounds() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new GovernanceFeedbackInterceptor(0, 100));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new GovernanceFeedbackInterceptor(5, 0));
    }

    @Test
    void scopesByUserWhenNoConversationOrSession() {
        record("prefer", "prefer scoped",
                Map.of("user_id", "user-1",
                        GovernanceDecisionLog.PREFERRED_KEY, "scoped credential"));

        var userOnly = new AiRequest("do it", BASE_PROMPT, "gpt-4o",
                "user-1", null, null, null, Map.of(), List.of());
        var out = new GovernanceFeedbackInterceptor().preProcess(userOnly, null);

        assertTrue(out.systemPrompt().contains("scoped credential"),
                "falls back to user_id scoping: " + out.systemPrompt());
        assertFalse(out.systemPrompt().equals(BASE_PROMPT));
    }

    @Test
    void mergesDurableGuidanceFromProvenanceStore() {
        var now = Instant.ofEpochSecond(1_000_000L);
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var backing = new InMemoryLongTermMemory();
        var store = new GovernanceProvenanceMemory(backing, 0.0, clock);
        // A durable lesson persisted for user-1 on a PRIOR session (still valid).
        backing.saveFact(GovernanceMemorySink.namespaceKey("user-1"),
                GovernanceFact.encode("least-privilege", 1.0, now.plusSeconds(3600),
                        "Prefer: request a scoped credential"));

        // A fresh conversation (no ephemeral entries) still recalls the durable guidance.
        var out = new GovernanceFeedbackInterceptor(5, 100, store)
                .preProcess(request("conv-NEW"), null);

        assertTrue(out.systemPrompt().contains("request a scoped credential"),
                "durable guidance recalled across sessions: " + out.systemPrompt());
    }

    @Test
    void durableExpiredGuidanceIsNotInjected() {
        var now = Instant.ofEpochSecond(1_000_000L);
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var backing = new InMemoryLongTermMemory();
        var store = new GovernanceProvenanceMemory(backing, 0.0, clock);
        backing.saveFact(GovernanceMemorySink.namespaceKey("user-1"),
                GovernanceFact.encode("p", 1.0, now.minusSeconds(1), "Prefer: stale advice"));

        var req = request("conv-NEW");
        var out = new GovernanceFeedbackInterceptor(5, 100, store).preProcess(req, null);

        assertSame(req, out, "the provenance gate must drop the expired lesson before injection");
    }

    @Test
    void ephemeralAndDurableAreDeduplicated() {
        record("prefer", "least-privilege",
                Map.of("conversation_id", "conv-1",
                        GovernanceDecisionLog.PREFERRED_KEY, "request a scoped credential"));
        var now = Instant.ofEpochSecond(1_000_000L);
        var backing = new InMemoryLongTermMemory();
        var store = new GovernanceProvenanceMemory(backing, 0.0, Clock.fixed(now, ZoneOffset.UTC));
        // Same rendered line, persisted durably — must not appear twice.
        backing.saveFact(GovernanceMemorySink.namespaceKey("user-1"),
                GovernanceFact.encode("least-privilege", 1.0, now.plusSeconds(3600),
                        "Prefer: request a scoped credential (least-privilege)"));

        var out = new GovernanceFeedbackInterceptor(5, 100, store)
                .preProcess(request("conv-1"), null);

        var prompt = out.systemPrompt();
        var first = prompt.indexOf("Prefer: request a scoped credential (least-privilege)");
        var last = prompt.lastIndexOf("Prefer: request a scoped credential (least-privilege)");
        assertTrue(first >= 0, "guidance present");
        assertEquals(first, last, "identical ephemeral + durable guidance appears once");
    }

    private record FakePolicy(String name) implements GovernancePolicy {
        @Override public String source() { return "code:test"; }
        @Override public String version() { return "test"; }
        @Override public PolicyDecision evaluate(PolicyContext context) {
            return PolicyDecision.admit();
        }
    }
}
