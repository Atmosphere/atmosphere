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

import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for the disconnect-driven cancellation path
 * ({@link AiStreamingSession#cancelInflight} and the
 * {@link AiStreamingSession#removeAllForResource} fan-out).
 *
 * <p>Closes Correctness Invariant #2 (Terminal Path Completeness): every
 * disconnect must abort the in-flight upstream LLM call and unblock parked
 * virtual threads waiting on {@code @RequiresApproval} responses, instead of
 * leaving them parked until the per-approval timeout.</p>
 */
public class AiStreamingSessionCancelInflightTest {

    private StreamingSession delegate;
    private AtmosphereResource resource;

    @BeforeEach
    public void setUp() {
        delegate = mock(StreamingSession.class);
        resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
    }

    @Test
    public void cancelInflight_completesPendingApprovalFuturesAsDenied() throws Exception {
        when(resource.uuid()).thenReturn("uuid-disco-1");
        var session = new AiStreamingSession(delegate, new NoopRuntime(),
                "", null, List.of(), resource);
        var f1 = session.approvalRegistry().register(pending("apr_a", 600));
        var f2 = session.approvalRegistry().register(pending("apr_b", 600));

        session.cancelInflight();

        assertTrue(f1.isDone(), "approval future must complete on disconnect");
        assertTrue(f2.isDone(), "approval future must complete on disconnect");
        assertFalse(f1.get(), "disconnect-cancelled approval must resolve as denied");
        assertFalse(f2.get(), "disconnect-cancelled approval must resolve as denied");
    }

    @Test
    public void cancelInflight_isIdempotent() {
        when(resource.uuid()).thenReturn("uuid-idem");
        var session = new AiStreamingSession(delegate, new NoopRuntime(),
                "", null, List.of(), resource);

        // No handle, no approvals — must not throw.
        session.cancelInflight();
        session.cancelInflight();
    }

    @Test
    public void cancelInflight_cancelsRuntimeExecutionHandleFromAnotherThread() throws Exception {
        when(resource.uuid()).thenReturn("uuid-cancel");
        var runtime = new CancellableRuntime();
        var session = new AiStreamingSession(delegate, runtime,
                "", null, List.of(), resource);

        // stream() parks on handle.whenDone().join() — runtime never completes
        // on its own, so the only way the VT can return is via cancelInflight.
        var streamDone = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try {
                session.stream("hello");
                streamDone.complete(null);
            } catch (Throwable t) {
                streamDone.completeExceptionally(t);
            }
        });

        // Wait for the runtime to receive the request and publish its handle.
        // 10s is intentionally generous; the actual bug this test guards is
        // "cancel races publish and is lost" — see AiStreamingSession's
        // cancelPending latch — not "VT is slow to start."
        runtime.handlePublished.get(10, TimeUnit.SECONDS);

        // Disconnect path: cancellation must unblock the parked VT and fire
        // the runtime's native cancel primitive (here, Settable's CAS). The
        // cancelInflight call may arrive before stream() has assigned
        // this.currentHandle (the race that caused 10s-flake-then-timeout on
        // loaded JDK 21 runners) — AiStreamingSession's cancelPending latch
        // catches it and self-cancels post-publish.
        session.cancelInflight();

        // If the cancel-race regresses, this get(...) times out and the test
        // fails loudly instead of waiting for a never-completing handle.
        streamDone.get(10, TimeUnit.SECONDS);

        assertEquals(ExecutionHandle.TerminalReason.CANCELLED,
                runtime.handle.terminalReason(),
                "runtime handle must observe CANCELLED terminal reason");
    }

    @Test
    public void cancelInflight_winsRaceWhenFiredBetweenPublishAndCurrentHandleAssignment()
            throws Exception {
        // Deterministically reproduces the JDK 21 CI flake (#25706065128):
        // executeWithHandle() publishes the handle and then BLOCKS before
        // returning, simulating a runtime that hands off control to a side
        // channel before the AiStreamingSession can assign currentHandle.
        // The disconnect path fires cancelInflight while currentHandle is
        // still null. Without the cancelPending latch the cancel is silently
        // dropped and the VT parks on whenDone().join() forever. With the
        // latch the post-assignment re-check fires the cancel and the VT
        // unwinds normally.
        when(resource.uuid()).thenReturn("uuid-race");
        var runtime = new RaceableRuntime();
        var session = new AiStreamingSession(delegate, runtime,
                "", null, List.of(), resource);

        var streamDone = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try {
                session.stream("hello");
                streamDone.complete(null);
            } catch (Throwable t) {
                streamDone.completeExceptionally(t);
            }
        });

        // Wait until executeWithHandle has published the handle but is still
        // parked (currentHandle has NOT been assigned yet on the VT).
        runtime.handlePublished.get(5, TimeUnit.SECONDS);

        // Fire cancel while the race window is open.
        session.cancelInflight();

        // Release executeWithHandle so the VT can return and assign
        // currentHandle, then observe cancelPending and self-cancel.
        runtime.release.complete(null);

        streamDone.get(5, TimeUnit.SECONDS);

        assertEquals(ExecutionHandle.TerminalReason.CANCELLED,
                runtime.handle.terminalReason(),
                "post-publish race cancel must reach the runtime handle");
    }

    @Test
    public void removeAllForResource_firesCancelInflightOnEachSession() throws Exception {
        when(resource.uuid()).thenReturn("uuid-removeall");
        var session = new AiStreamingSession(delegate, new NoopRuntime(),
                "", null, List.of(), resource);
        var f = session.approvalRegistry().register(pending("apr_remove", 600));

        AiStreamingSession.registerActive(session);
        try {
            AiStreamingSession.removeAllForResource("uuid-removeall");
        } finally {
            // Defensive: even if the assertion fails, leave no global state behind.
            AiStreamingSession.removeActiveSession(session);
        }

        assertTrue(f.isDone(),
                "removeAllForResource must fire cancelInflight on each session");
        assertFalse(f.get(),
                "session unblocked via removeAllForResource resolves as denied");
    }

    @Test
    public void cancelInflightForResource_cancelsButDoesNotRemoveSessions() throws Exception {
        // Admin "cancel-inflight" path: abort the upstream LLM call but keep
        // the session registered so the socket stays alive for the next turn.
        when(resource.uuid()).thenReturn("uuid-admin-cancel");
        var session = new AiStreamingSession(delegate, new NoopRuntime(),
                "", null, List.of(), resource);
        var f = session.approvalRegistry().register(pending("apr_admin", 600));

        AiStreamingSession.registerActive(session);
        try {
            assertTrue(AiStreamingSession.resourceHasActiveSessions("uuid-admin-cancel"));
            AiStreamingSession.cancelInflightForResource("uuid-admin-cancel");
            assertTrue(f.isDone(), "approval future must complete on admin cancel");
            assertTrue(AiStreamingSession.resourceHasActiveSessions("uuid-admin-cancel"),
                    "session must remain registered (transport stays open)");
        } finally {
            AiStreamingSession.removeActiveSession(session);
        }
    }

    @Test
    public void resourceHasActiveSessions_returnsFalseForUnknownUuid() {
        assertFalse(AiStreamingSession.resourceHasActiveSessions("uuid-nonexistent"));
        assertFalse(AiStreamingSession.resourceHasActiveSessions(null));
    }

    @Test
    public void removeAllForResource_handlesMultipleOverlappingSessions() throws Exception {
        // Atmosphere allows two concurrent @Prompt invocations on a single
        // resource (AG-UI, streaming-chat). Disconnect must cancel ALL of them,
        // not just the most recently registered one.
        when(resource.uuid()).thenReturn("uuid-multi");
        var s1 = new AiStreamingSession(delegate, new NoopRuntime(),
                "", null, List.of(), resource);
        var s2 = new AiStreamingSession(delegate, new NoopRuntime(),
                "", null, List.of(), resource);
        var f1 = s1.approvalRegistry().register(pending("apr_m1", 600));
        var f2 = s2.approvalRegistry().register(pending("apr_m2", 600));

        AiStreamingSession.registerActive(s1);
        AiStreamingSession.registerActive(s2);
        try {
            AiStreamingSession.removeAllForResource("uuid-multi");
        } finally {
            AiStreamingSession.removeActiveSession(s1);
            AiStreamingSession.removeActiveSession(s2);
        }

        assertTrue(f1.isDone(), "first overlapping session must be cancelled");
        assertTrue(f2.isDone(), "second overlapping session must be cancelled");
        assertFalse(f1.get());
        assertFalse(f2.get());
    }

    private PendingApproval pending(String id, long timeoutSeconds) {
        return new PendingApproval(id, "tool", Map.of("a", "b"),
                "Approve?", "conv-1",
                Instant.now().plusSeconds(timeoutSeconds));
    }

    /** No-op runtime that returns the COMPLETED handle (default executeWithHandle path). */
    static final class NoopRuntime implements AgentRuntime {
        @Override public String name() { return "noop"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings s) { }
        @Override public void execute(AgentExecutionContext c, StreamingSession s) { }
    }

    /**
     * Runtime that overrides {@link AgentRuntime#executeWithHandle} to return a
     * non-completing {@link ExecutionHandle.Settable}. Lets the test drive
     * {@code cancel()} from a separate thread and verify the parked stream() VT
     * unblocks.
     */
    static final class CancellableRuntime implements AgentRuntime {
        final ExecutionHandle.Settable handle = new ExecutionHandle.Settable(null);
        final CompletableFuture<ExecutionHandle> handlePublished = new CompletableFuture<>();

        @Override public String name() { return "cancellable"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings s) { }

        @Override
        public void execute(AgentExecutionContext c, StreamingSession s) {
            // Unused: stream() switches to executeWithHandle, which we override.
        }

        @Override
        public ExecutionHandle executeWithHandle(AgentExecutionContext context, StreamingSession session) {
            handlePublished.complete(handle);
            return handle;
        }
    }

    /**
     * Like {@link CancellableRuntime} but {@code executeWithHandle} parks on
     * {@code release} after publishing the handle, so the test can fire
     * {@code cancelInflight} while {@code currentHandle} is still null and
     * deterministically exercise the cancel-publish race window.
     */
    static final class RaceableRuntime implements AgentRuntime {
        final ExecutionHandle.Settable handle = new ExecutionHandle.Settable(null);
        final CompletableFuture<ExecutionHandle> handlePublished = new CompletableFuture<>();
        final CompletableFuture<Void> release = new CompletableFuture<>();

        @Override public String name() { return "raceable"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings s) { }

        @Override
        public void execute(AgentExecutionContext c, StreamingSession s) { }

        @Override
        public ExecutionHandle executeWithHandle(AgentExecutionContext context, StreamingSession session) {
            handlePublished.complete(handle);
            // Block here until the test releases — currentHandle is still
            // unassigned on the AiStreamingSession side.
            release.join();
            return handle;
        }
    }
}
