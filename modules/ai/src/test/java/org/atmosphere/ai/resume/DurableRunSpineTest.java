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

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.atmosphere.ai.tool.ToolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the keystone of the durable-execution feature: the {@link DurableRunSpine}
 * is what installs the per-run scope on the real run path, and it is that scope —
 * not any per-runtime code — that flips the already-built journaled seams from
 * dormant to live. The {@code installedScopeActivatesToolMemoSeam} test proves
 * the wiring end-to-end: after {@code beginDrive}, a tool call through the
 * runtime-agnostic {@link ToolExecutionHelper} choke point records a committed
 * effect; with no spine, the identical call records nothing. The remaining tests
 * pin the lease + terminal lifecycle (Invariants&nbsp;#1/#2).
 */
class DurableRunSpineTest {

    private static final String RUN_ID = "run-keystone";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Map<String, Object> ARGS = Map.of("id", "v");

    @AfterEach
    void cleanup() {
        DurableRunScopeHolder.clear();
        DurableRunSpineHolder.reset();
    }

    private static DurableRunSpine spine(EffectJournal journal, boolean retainOnSuccess) {
        return new DurableRunSpine(journal,
                new DurableRunConfig(true, TTL, retainOnSuccess), "proc-A");
    }

    @Test
    void beginDriveInstallsScopeAndClaimsLease() {
        var journal = new InMemoryEffectJournal();
        var scope = spine(journal, false).beginDrive(RUN_ID, "alice", "/chat");

        assertTrue(scope.isPresent(), "an enabled spine installs a scope");
        assertNotNull(DurableRunScopeHolder.get(RUN_ID), "the scope is reachable by runId");
        assertFalse(scope.get().replayMode(), "first drive is not replay");
        assertFalse(journal.claimLease(RUN_ID, "proc-B", TTL),
                "the single-writer lease is held against another owner");
    }

    @Test
    void disabledSpineInstallsNoScope() {
        var journal = new InMemoryEffectJournal();
        var scope = DurableRunSpine.disabled().beginDrive(RUN_ID, "alice", "/chat");

        assertTrue(scope.isEmpty(), "the default disabled spine installs nothing");
        assertNull(DurableRunScopeHolder.get(RUN_ID), "no scope is reachable");
        assertTrue(journal.claimLease(RUN_ID, "proc-B", TTL), "no lease was taken");
    }

    @Test
    void installedScopeActivatesToolMemoSeam() {
        // With the spine driving the run, the runtime-agnostic tool choke point
        // resolves the installed scope and records the call as a committed effect.
        var journal = new InMemoryEffectJournal();
        spine(journal, false).beginDrive(RUN_ID, "alice", "/chat");
        var session = new RunSession();
        var executor = new CountingExecutor();

        var result = ToolExecutionHelper.executeWithApproval(
                "echo", tool(executor), ARGS, session, null, null, Map.of());

        assertEquals("ok", result);
        assertEquals(1, executor.calls, "the live executor ran once on the first drive");
        var key = EffectKeys.toolCall(RUN_ID, "echo", ARGS, 0);
        assertTrue(journal.lookupCommitted(RUN_ID, key).isPresent(),
                "the keystone scope made the tool-memo seam record a committed effect");
    }

    @Test
    void noScopeMeansNoEffectRecorded() {
        // The negative control: without a spine the same call takes the live
        // path and writes nothing to the journal.
        var journal = new InMemoryEffectJournal();
        var session = new RunSession();
        var executor = new CountingExecutor();

        ToolExecutionHelper.executeWithApproval(
                "echo", tool(executor), ARGS, session, null, null, Map.of());

        assertEquals(1, executor.calls);
        assertTrue(journal.fold(RUN_ID).isEmpty(), "no scope installed → no effect recorded");
    }

    @Test
    void completeDriveSuccessPrunesHistoryAndReleasesLease() {
        var journal = new InMemoryEffectJournal();
        var spine = spine(journal, false);
        var ctx = spine.beginDrive(RUN_ID, "alice", "/chat").orElseThrow();
        journal.appendPending(RUN_ID, EffectKind.TOOL_CALL,
                EffectKeys.toolCall(RUN_ID, "echo", ARGS, 0), "d");
        journal.commit(RUN_ID, EffectKeys.toolCall(RUN_ID, "echo", ARGS, 0), "ok");

        spine.completeDrive(ctx, true);

        assertEquals(0, journal.runCount(), "a successful run's history is pruned (retain=false)");
        assertNull(DurableRunScopeHolder.get(RUN_ID), "the scope is removed on the terminal path");
        assertTrue(journal.claimLease(RUN_ID, "proc-B", TTL), "the lease is released on completion");
    }

    @Test
    void completeDriveFailureRetainsHistoryAndReleasesLease() {
        var journal = new InMemoryEffectJournal();
        var spine = spine(journal, false);
        var ctx = spine.beginDrive(RUN_ID, "alice", "/chat").orElseThrow();
        journal.appendPending(RUN_ID, EffectKind.TOOL_CALL,
                EffectKeys.toolCall(RUN_ID, "echo", ARGS, 0), "d");
        journal.commit(RUN_ID, EffectKeys.toolCall(RUN_ID, "echo", ARGS, 0), "ok");

        spine.completeDrive(ctx, false);

        assertFalse(journal.fold(RUN_ID).isEmpty(), "a failed run keeps its history for resume");
        assertNull(DurableRunScopeHolder.get(RUN_ID), "the scope is still removed");
        assertTrue(journal.claimLease(RUN_ID, "proc-B", TTL), "the lease is released even on failure");
    }

    @Test
    void completeDriveReleasesLeaseEvenWhenMarkTerminalThrows() {
        // Invariants #1/#2: a journal error finalizing the run must not strand
        // the lease — a stranded lease would block every later re-drive.
        var delegate = new InMemoryEffectJournal();
        var throwing = new ThrowOnMarkTerminalJournal(delegate);
        var spine = spine(throwing, false);
        var ctx = spine.beginDrive(RUN_ID, "alice", "/chat").orElseThrow();

        spine.completeDrive(ctx, true);

        assertTrue(delegate.claimLease(RUN_ID, "proc-B", TTL),
                "the lease is released in finally despite the markTerminal failure");
        assertNull(DurableRunScopeHolder.get(RUN_ID), "the scope is removed despite the failure");
    }

    // -- helpers --

    private static ToolDefinition tool(ToolExecutor executor) {
        return new ToolDefinition("echo", "echo the input", List.of(), "string",
                executor, null, 0);
    }

    private static final class CountingExecutor implements ToolExecutor {
        private int calls;

        @Override
        public Object execute(Map<String, Object> arguments) {
            calls++;
            return "ok";
        }
    }

    /** A session that carries the run id so the seam resolves the installed scope. */
    private static final class RunSession implements StreamingSession {
        @Override
        public Optional<String> runId() {
            return Optional.of(RUN_ID);
        }

        @Override
        public void emit(AiEvent event) {
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

    /**
     * Delegates every {@link EffectJournal} method to a real in-memory journal
     * except {@link #markTerminal}, which throws — exercising completeDrive's
     * finally-release guarantee.
     */
    private static final class ThrowOnMarkTerminalJournal implements EffectJournal {
        private final EffectJournal delegate;

        private ThrowOnMarkTerminalJournal(EffectJournal delegate) {
            this.delegate = delegate;
        }

        @Override
        public long appendPending(String runId, EffectKind kind,
                                  String idempotencyKey, String requestDigest) {
            return delegate.appendPending(runId, kind, idempotencyKey, requestDigest);
        }

        @Override
        public void commit(String runId, String idempotencyKey, String resultPayload) {
            delegate.commit(runId, idempotencyKey, resultPayload);
        }

        @Override
        public void markFailed(String runId, String idempotencyKey, String reason) {
            delegate.markFailed(runId, idempotencyKey, reason);
        }

        @Override
        public Optional<EffectRecord> lookupCommitted(String runId, String idempotencyKey) {
            return delegate.lookupCommitted(runId, idempotencyKey);
        }

        @Override
        public List<EffectRecord> fold(String runId) {
            return delegate.fold(runId);
        }

        @Override
        public boolean claimLease(String runId, String owner, Duration ttl) {
            return delegate.claimLease(runId, owner, ttl);
        }

        @Override
        public void releaseLease(String runId, String owner) {
            delegate.releaseLease(runId, owner);
        }

        @Override
        public void markTerminal(String runId, EffectStatus terminal) {
            throw new IllegalStateException("boom");
        }

        @Override
        public void removeRun(String runId) {
            delegate.removeRun(runId);
        }

        @Override
        public boolean durable() {
            return delegate.durable();
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public int maxEffectsPerRun() {
            return delegate.maxEffectsPerRun();
        }
    }
}
