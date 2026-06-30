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
package org.atmosphere.ai.tool;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.resume.DurableRunContext;
import org.atmosphere.ai.resume.DurableRunScopeHolder;
import org.atmosphere.ai.resume.EffectKeys;
import org.atmosphere.ai.resume.EffectKind;
import org.atmosphere.ai.resume.InMemoryEffectJournal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the durable-run tool-memo seam in {@link ToolExecutionHelper}. The seam is
 * the single cross-runtime tool choke point and is runtime-agnostic — it keys off
 * {@code session.runId()} alone — so exercising
 * {@code executeWithApproval} directly is exactly how any of the framework
 * runtimes (Spring AI, LangChain4j, ADK, …) reaches it: a recorded hit replays
 * for all of them without per-runtime code.
 */
class ToolMemoReplayTest {

    private static final String RUN_ID = "run-1";
    private static final Map<String, Object> ARGS = Map.of("id", 7);

    @AfterEach
    void cleanup() {
        DurableRunScopeHolder.clear();
    }

    private static DurableRunContext install(InMemoryEffectJournal journal, boolean replay) {
        return install(journal, replay, "owner");
    }

    private static DurableRunContext install(InMemoryEffectJournal journal, boolean replay, String userId) {
        var ctx = new DurableRunContext(RUN_ID, journal, replay, "owner", userId);
        DurableRunScopeHolder.install(RUN_ID, ctx);
        return ctx;
    }

    private static ToolDefinition tool(ToolExecutor executor) {
        return new ToolDefinition("echo", "echo the input", List.of(), "string",
                executor, null, 0);
    }

    private static String call(CapturingSession session) {
        return ToolExecutionHelper.executeWithApproval(
                "echo", tool(session.executor), ARGS, session, null, null, Map.of());
    }

    @Test
    void missRunsExecutorAndRecordsCommitted() {
        var journal = new InMemoryEffectJournal();
        install(journal, false);
        var session = new CapturingSession(new CountingExecutor("ok"));

        var result = call(session);

        assertEquals("ok", result);
        assertEquals(1, session.executor.calls.get(), "MISS runs the executor exactly once");
        assertEquals(List.of("ToolStart", "ToolResult"), session.frames(),
                "both frames are emitted on the live path");
        var key = EffectKeys.toolCall(RUN_ID, "echo", ARGS, 0);
        assertEquals("ok", journal.lookupCommitted(RUN_ID, key).orElseThrow().resultPayload(),
                "the terminal outcome is committed for replay");
    }

    @Test
    void replayHitSkipsExecutorEmitsBothFramesReturnsRecorded() {
        var journal = new InMemoryEffectJournal();
        // First drive records the effect.
        install(journal, false);
        var first = new CapturingSession(new CountingExecutor("ok"));
        call(first);
        assertEquals(1, first.executor.calls.get());

        // Replay: a FRESH context (fresh occurrence cursor) over the SAME journal.
        install(journal, true);
        var replay = new CapturingSession(new CountingExecutor("SHOULD-NOT-RUN"));
        var result = call(replay);

        assertEquals("ok", result, "the recorded outcome is returned, not the re-run value");
        assertEquals(0, replay.executor.calls.get(), "HIT skips the executor entirely");
        assertEquals(List.of("ToolStart", "ToolResult"), replay.frames(),
                "both frames are re-emitted on replay so the wire sees an identical round");
    }

    @Test
    void recordedOutcomeIsNotInheritedByADifferentPrincipal() {
        // Approval durability with cross-principal safety (Inv #6): a tool
        // outcome recorded for one principal must not replay for another. The
        // run principal is bound into the effect digest, so a re-drive under a
        // different principal misses the digest and re-executes live (re-gating)
        // rather than inheriting the recorded — possibly approved — result.
        var journal = new InMemoryEffectJournal();
        install(journal, false, "alice");
        var alice = new CapturingSession(new CountingExecutor("alice-approved"));
        assertEquals("alice-approved", call(alice));
        assertEquals(1, alice.executor.calls.get());

        // A fresh drive of the SAME run id under a different principal.
        install(journal, true, "bob");
        var bob = new CapturingSession(new CountingExecutor("bob-fresh"));
        var result = call(bob);

        assertEquals("bob-fresh", result, "bob does not inherit alice's recorded outcome");
        assertEquals(1, bob.executor.calls.get(), "bob's call re-executes live, it does not replay");
    }

    @Test
    void samePrincipalReplayStillHits() {
        // The control for the cross-principal test: an identical principal on
        // the re-drive still gets the deterministic replay hit.
        var journal = new InMemoryEffectJournal();
        install(journal, false, "alice");
        call(new CapturingSession(new CountingExecutor("alice-approved")));

        install(journal, true, "alice");
        var replay = new CapturingSession(new CountingExecutor("SHOULD-NOT-RUN"));
        assertEquals("alice-approved", call(replay), "the same principal replays the recorded outcome");
        assertEquals(0, replay.executor.calls.get(), "the executor is skipped on a same-principal replay");
    }

    @Test
    void repeatedIdenticalCallsGetDistinctOrdinalsAndBothExecute() {
        var journal = new InMemoryEffectJournal();
        install(journal, false);
        var session = new CapturingSession(new CountingExecutor("ok"));

        call(session);
        call(session); // same tool + same args, second occurrence

        assertEquals(2, session.executor.calls.get(),
                "identical repeated calls are distinct effects (ordinals 0,1), both execute");
        assertTrue(journal.lookupCommitted(RUN_ID, EffectKeys.toolCall(RUN_ID, "echo", ARGS, 0)).isPresent());
        assertTrue(journal.lookupCommitted(RUN_ID, EffectKeys.toolCall(RUN_ID, "echo", ARGS, 1)).isPresent());
    }

    @Test
    void toolErrorOutcomeIsMemoizedAndReplayedDeterministically() {
        var journal = new InMemoryEffectJournal();
        install(journal, false);
        var first = new CapturingSession(CountingExecutor.throwing());
        var firstResult = call(first);
        assertTrue(firstResult.contains("error"), "a tool error is encoded as JSON, not thrown");
        assertEquals(1, first.executor.calls.get());

        install(journal, true);
        var replay = new CapturingSession(CountingExecutor.throwing());
        var replayResult = call(replay);

        assertEquals(firstResult, replayResult, "the same error outcome replays deterministically");
        assertEquals(0, replay.executor.calls.get(), "the failing tool is not re-run on replay");
    }

    @Test
    void pendingWithoutCommitReRunsOnReplay() {
        var journal = new InMemoryEffectJournal();
        var key = EffectKeys.toolCall(RUN_ID, "echo", ARGS, 0);
        var digest = EffectKeys.sha256Hex("echo", EffectKeys.canonicalJson(ARGS));
        // Simulate a crash after append but before commit: a PENDING effect.
        journal.appendPending(RUN_ID, EffectKind.TOOL_CALL, key, digest);

        install(journal, true);
        var session = new CapturingSession(new CountingExecutor("fresh"));
        var result = call(session);

        assertEquals("fresh", result);
        assertEquals(1, session.executor.calls.get(),
                "a PENDING (uncommitted) effect is not a hit — it re-runs (at-least-once)");
        assertEquals("fresh", journal.lookupCommitted(RUN_ID, key).orElseThrow().resultPayload());
    }

    @Test
    void noDurableScopeTakesLivePathWithNoMemo() {
        // No scope installed: the session carries no run id.
        var session = new CapturingSession(new CountingExecutor("ok"));
        var result = call(session);

        assertEquals("ok", result);
        assertEquals(1, session.executor.calls.get());
        assertEquals(List.of("ToolStart", "ToolResult"), session.frames());
    }

    @Test
    void digestDivergenceTripwireExecutesLiveNotStale() {
        var journal = new InMemoryEffectJournal();
        var key = EffectKeys.toolCall(RUN_ID, "echo", ARGS, 0);
        // Commit a result under the same key but a DIFFERENT request digest.
        journal.appendPending(RUN_ID, EffectKind.TOOL_CALL, key, "WRONG-DIGEST");
        journal.commit(RUN_ID, key, "STALE-RESULT");

        install(journal, true);
        var session = new CapturingSession(new CountingExecutor("fresh"));
        var result = call(session);

        assertEquals("fresh", result, "a digest mismatch must not replay the stale result");
        assertEquals(1, session.executor.calls.get(), "the tripwire forces live execution");
    }

    @Test
    void toolStartFrameCarriesArguments() {
        var journal = new InMemoryEffectJournal();
        install(journal, false);
        var session = new CapturingSession(new CountingExecutor("ok"));
        call(session);

        var start = session.events.stream()
                .filter(e -> e instanceof AiEvent.ToolStart)
                .map(e -> (AiEvent.ToolStart) e)
                .findFirst().orElseThrow();
        assertInstanceOf(Map.class, start.arguments());
        assertEquals(7, start.arguments().get("id"));
        assertFalse(session.frames().isEmpty());
    }

    /**
     * A {@link ToolExecutor} that counts invocations and either returns a fixed
     * string or throws (the live path encodes a throw as error JSON).
     */
    private static final class CountingExecutor implements ToolExecutor {
        private final AtomicInteger calls = new AtomicInteger();
        private final String result;
        private final boolean throwError;

        private CountingExecutor(String result) {
            this(result, false);
        }

        private CountingExecutor(String result, boolean throwError) {
            this.result = result;
            this.throwError = throwError;
        }

        private static CountingExecutor throwing() {
            return new CountingExecutor(null, true);
        }

        @Override
        public Object execute(Map<String, Object> arguments) {
            calls.incrementAndGet();
            if (throwError) {
                throw new IllegalStateException("boom");
            }
            return result;
        }
    }

    /** A {@link StreamingSession} that carries a run id and captures emitted events. */
    private static final class CapturingSession implements StreamingSession {
        private final List<AiEvent> events = new ArrayList<>();
        private final CountingExecutor executor;

        private CapturingSession(CountingExecutor executor) {
            this.executor = executor;
        }

        private List<String> frames() {
            var out = new ArrayList<String>();
            for (var e : events) {
                if (e instanceof AiEvent.ToolStart) {
                    out.add("ToolStart");
                } else if (e instanceof AiEvent.ToolResult) {
                    out.add("ToolResult");
                }
            }
            return out;
        }

        @Override
        public void emit(AiEvent event) {
            events.add(event);
        }

        @Override
        public Optional<String> runId() {
            return Optional.of(RUN_ID);
        }

        @Override
        public String sessionId() {
            return "session";
        }

        @Override
        public void send(String text) {
        }

        @Override
        public void sendMetadata(String key, Object value) {
        }

        @Override
        public void progress(String message) {
        }

        @Override
        public void complete() {
        }

        @Override
        public void complete(String summary) {
        }

        @Override
        public void error(Throwable t) {
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }
}
