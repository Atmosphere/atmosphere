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
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.governance.memory.GovernanceFact;
import org.atmosphere.ai.governance.memory.GovernanceMemorySink;
import org.atmosphere.ai.governance.memory.GovernanceProvenanceMemory;
import org.atmosphere.ai.memory.InMemoryLongTermMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    /**
     * The prompting resource as the {@code @AiEndpoint} handler leaves it: its request
     * stamped with the turn's delivery scope ({@code null} = never stamped).
     */
    private static AtmosphereResource prompter(Boolean roomBroadcast) {
        var resource = mock(AtmosphereResource.class);
        var req = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(req);
        when(req.getAttribute(StreamingSessions.ROOM_BROADCAST_ATTRIBUTE)).thenReturn(roomBroadcast);
        return resource;
    }

    /** A per-client turn: the reply reaches only the prompter. */
    private static AtmosphereResource unicast() {
        return prompter(Boolean.FALSE);
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

    @Test
    void preProcessRecordsExactlyTheInjectedLinesOnTheRequest() {
        record("prefer", "least-privilege",
                Map.of("conversation_id", "conv-1",
                        GovernanceDecisionLog.PREFERRED_KEY, "request a scoped credential"));
        record("deny", "dropping the table is never allowed", convScope("conv-1"));

        var out = new GovernanceFeedbackInterceptor().preProcess(request("conv-1"), null);

        var recorded = out.metadata().get(GovernanceFeedbackInterceptor.LINES_METADATA_KEY);
        assertTrue(recorded instanceof List<?>, "injected lines recorded on the request: " + recorded);
        var lines = (List<?>) recorded;
        assertEquals(2, lines.size(), "one recorded line per injected line: " + lines);
        for (var line : lines) {
            assertTrue(out.systemPrompt().contains("\n- " + line),
                    "every recorded line is a line of the injected block: " + line);
        }
    }

    @Test
    void beforeCompletionSignalsTheInjectedCountAndLines() {
        record("prefer", "least-privilege",
                Map.of("conversation_id", "conv-1",
                        GovernanceDecisionLog.PREFERRED_KEY, "request a scoped credential"));
        var interceptor = new GovernanceFeedbackInterceptor();
        var out = interceptor.preProcess(request("conv-1"), null);
        var session = new RecordingSession();

        interceptor.beforeCompletion(out, session, unicast());

        assertEquals(List.of(GovernanceFeedbackInterceptor.INJECTED_METADATA_KEY,
                        GovernanceFeedbackInterceptor.LINES_METADATA_KEY),
                List.copyOf(session.metadata.keySet()), "count frame, then lines frame");
        assertEquals(1, session.metadata.get(GovernanceFeedbackInterceptor.INJECTED_METADATA_KEY));
        assertEquals(List.of("Prefer: request a scoped credential (least-privilege)"),
                session.metadata.get(GovernanceFeedbackInterceptor.LINES_METADATA_KEY));
    }

    @Test
    void noInjectionMeansNoSignal() {
        // A decision for ANOTHER conversation: nothing is injected into conv-1, so the
        // client must not be told otherwise.
        record("prefer", "scoped is preferred",
                Map.of("conversation_id", "conv-OTHER",
                        GovernanceDecisionLog.PREFERRED_KEY, "scoped credential"));
        var interceptor = new GovernanceFeedbackInterceptor();
        var out = interceptor.preProcess(request("conv-1"), null);
        var session = new RecordingSession();

        interceptor.beforeCompletion(out, session, unicast());

        assertNull(out.metadata().get(GovernanceFeedbackInterceptor.LINES_METADATA_KEY));
        assertTrue(session.metadata.isEmpty(), "no frame without injection: " + session.metadata);
    }

    @Test
    void signalIsBoundedByMaxItemsAndPerLineCap() {
        var oversized = new ArrayList<String>();
        for (int i = 0; i < 10; i++) {
            oversized.add(i + "x".repeat(2_000));
        }
        // The prompt carries every recorded line, as an injection would have left it.
        var prompt = new StringBuilder(BASE_PROMPT);
        oversized.forEach(line -> prompt.append("\n- ").append(line));
        var forged = new AiRequest("m", prompt.toString(), null, null, null, null, "conv-1",
                Map.of(GovernanceFeedbackInterceptor.LINES_METADATA_KEY, oversized), List.of());
        var interceptor = new GovernanceFeedbackInterceptor(3, 100);
        var session = new RecordingSession();

        interceptor.beforeCompletion(forged, session, unicast());

        assertEquals(3, session.metadata.get(GovernanceFeedbackInterceptor.INJECTED_METADATA_KEY));
        var lines = (List<?>) session.metadata.get(GovernanceFeedbackInterceptor.LINES_METADATA_KEY);
        assertEquals(3, lines.size(), "lines capped at maxItems");
        for (var line : lines) {
            assertEquals(GovernanceFeedbackInterceptor.MAX_SIGNAL_LINE_CHARS,
                    ((String) line).length(), "each line capped: " + ((String) line).length());
        }
    }

    @Test
    void aFailingSessionNeverBreaksTheTurn() {
        record("deny", "least-privilege violation", convScope("conv-1"));
        var interceptor = new GovernanceFeedbackInterceptor();
        var out = interceptor.preProcess(request("conv-1"), null);
        var session = new RecordingSession() {
            @Override
            public void sendMetadata(String key, Object value) {
                throw new IllegalStateException("wire down");
            }
        };

        assertDoesNotThrow(() -> interceptor.beforeCompletion(out, session, unicast()));
    }

    @Test
    void roomBroadcastTurnInjectsButNeverSignals() {
        // On @AiEndpoint(broadcastReply = true) the reply fans out to every subscriber, so the
        // prompter's governance lines must not ride it — not even their count.
        record("deny", "least-privilege violation", convScope("conv-1"));
        var interceptor = new GovernanceFeedbackInterceptor();
        var out = interceptor.preProcess(request("conv-1"), null);
        var session = new RecordingSession();

        interceptor.beforeCompletion(out, session, prompter(Boolean.TRUE));

        assertTrue(out.systemPrompt().contains("least-privilege violation"),
                "the guidance is still injected into the prompter's turn");
        assertTrue(session.metadata.isEmpty(), "no frame on a room-wide reply: " + session.metadata);
    }

    @Test
    void unconfirmedDeliveryScopeFailsClosed() {
        record("deny", "least-privilege violation", convScope("conv-1"));
        var interceptor = new GovernanceFeedbackInterceptor();
        var out = interceptor.preProcess(request("conv-1"), null);

        var unstamped = new RecordingSession();
        interceptor.beforeCompletion(out, unstamped, prompter(null));
        var noResource = new RecordingSession();
        interceptor.beforeCompletion(out, noResource, null);

        assertTrue(unstamped.metadata.isEmpty(), "no stamp is not per-client: " + unstamped.metadata);
        assertTrue(noResource.metadata.isEmpty(), "no resource is not per-client: " + noResource.metadata);
    }

    @Test
    void aLaterInterceptorThatReplacesTheSystemPromptSuppressesTheSignal() {
        record("prefer", "least-privilege",
                Map.of("conversation_id", "conv-1",
                        GovernanceDecisionLog.PREFERRED_KEY, "request a scoped credential"));
        var interceptor = new GovernanceFeedbackInterceptor();
        // FIFO chain: feedback first, then a persona switch that replaces the prompt
        // (withSystemPrompt keeps the metadata, so the recorded lines survive it).
        var injected = interceptor.preProcess(request("conv-1"), null);
        var replaced = injected.withSystemPrompt("You are the billing persona.");
        var session = new RecordingSession();

        interceptor.beforeCompletion(replaced, session, unicast());

        assertTrue(replaced.metadata().containsKey(GovernanceFeedbackInterceptor.LINES_METADATA_KEY),
                "the recorded lines outlive the replacement: " + replaced.metadata());
        assertTrue(session.metadata.isEmpty(),
                "the guidance never reached the model, so nothing is reported: " + session.metadata);
    }

    @Test
    void aLaterInterceptorThatAppendsKeepsTheSignal() {
        record("prefer", "least-privilege",
                Map.of("conversation_id", "conv-1",
                        GovernanceDecisionLog.PREFERRED_KEY, "request a scoped credential"));
        var interceptor = new GovernanceFeedbackInterceptor();
        var injected = interceptor.preProcess(request("conv-1"), null);
        var appended = injected.withSystemPrompt(injected.systemPrompt() + "\n\nAnswer in French.");
        var session = new RecordingSession();

        interceptor.beforeCompletion(appended, session, unicast());

        assertEquals(1, session.metadata.get(GovernanceFeedbackInterceptor.INJECTED_METADATA_KEY));
    }

    @Test
    void injectionKeepsExistingMetadataAndToleratesNullMetadata() {
        record("deny", "least-privilege violation", convScope("conv-1"));
        var interceptor = new GovernanceFeedbackInterceptor();

        var withMeta = new AiRequest("m", BASE_PROMPT, null, "user-1", null, null, "conv-1",
                Map.of("ai.budget", 42), List.of());
        var out = interceptor.preProcess(withMeta, null);
        assertEquals(42, out.metadata().get("ai.budget"), "existing metadata preserved");
        assertTrue(out.metadata().containsKey(GovernanceFeedbackInterceptor.LINES_METADATA_KEY));

        var nullMeta = new AiRequest("m", BASE_PROMPT, null, "user-1", null, null, "conv-1",
                null, List.of());
        var out2 = interceptor.preProcess(nullMeta, null);
        assertTrue(out2.systemPrompt().contains("least-privilege violation"),
                "null metadata must not cost the turn its guidance: " + out2.systemPrompt());
        assertTrue(out2.metadata().containsKey(GovernanceFeedbackInterceptor.LINES_METADATA_KEY));

        var nullValue = new HashMap<String, Object>();
        nullValue.put("nullable", null);
        var out3 = interceptor.preProcess(new AiRequest("m", BASE_PROMPT, null, "user-1", null,
                null, "conv-1", nullValue, List.of()), null);
        assertTrue(out3.systemPrompt().contains("least-privilege violation"),
                "a null metadata value must not cost the turn its guidance");
    }

    /** Records metadata frames in arrival order. */
    private static class RecordingSession implements StreamingSession {
        final Map<String, Object> metadata = new LinkedHashMap<>();

        @Override public String sessionId() { return "rec"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { metadata.put(key, value); }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
    }

    private record FakePolicy(String name) implements GovernancePolicy {
        @Override public String source() { return "code:test"; }
        @Override public String version() { return "test"; }
        @Override public PolicyDecision evaluate(PolicyContext context) {
            return PolicyDecision.admit();
        }
    }
}
