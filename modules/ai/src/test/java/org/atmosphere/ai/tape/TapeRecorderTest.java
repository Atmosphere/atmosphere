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
package org.atmosphere.ai.tape;

import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link TapeRecorder} write-path semantics: batch drain ordering, queue
 * overflow dropping steps but never terminals, write-once terminal status
 * with late-terminal counting, append-after-terminal drop-and-count, the
 * disconnect drain-before-cancel ordering, the idle sweep, store-throw
 * containment, the bounded uninstall, and the {@link TapeSupport}
 * install/uninstall token-CAS.
 */
class TapeRecorderTest {

    private static TapeRecorder.Config config(int queueCapacity, Duration idleTimeout) {
        return new TapeRecorder.Config(queueCapacity, 262_144, idleTimeout,
                Duration.ofSeconds(10));
    }

    private static TapeRecordingSession pipelineSession(TapeRecorder recorder) {
        return new TapeRecordingSession(recorder, new NoopDelegate(),
                TapeRunInfo.pipeline("client-1", "model-x", "rt"));
    }

    @Test
    void batchDrainAppendsInProducedOrderWithMonotonicSeq() throws Exception {
        var store = new InMemoryTapeStore();
        var recorder = new TapeRecorder(store);
        try {
            var session = pipelineSession(recorder);
            for (int i = 0; i < 10; i++) {
                session.progress("p" + i);
            }
            session.complete();
            awaitStatus(store, session.tapeRunId(), TapeStatus.COMPLETED);
            var steps = store.readSteps(session.tapeRunId(), 0, 0);
            assertEquals(11, steps.size(), "10 progress steps + the terminal step");
            for (int i = 0; i < steps.size(); i++) {
                assertEquals(i, steps.get(i).seq(), "seq must be writer-assigned and monotonic");
            }
            for (int i = 0; i < 10; i++) {
                assertTrue(steps.get(i).payload().contains("\"message\":\"p" + i + "\""),
                        "produced order must survive the batch drain: " + steps.get(i).payload());
            }
        } finally {
            recorder.close();
        }
    }

    @Test
    void queueOverflowDropsStepsButNeverTheTerminal() throws Exception {
        var store = new GatedStore();
        var recorder = new TapeRecorder(store, config(4, Duration.ofMinutes(30)));
        try {
            var session = pipelineSession(recorder);
            session.progress("wedge");
            assertTrue(store.entered.await(5, TimeUnit.SECONDS),
                    "writer must be blocked inside the first append");
            // Writer is parked in the store: fill the 4-slot queue, then overflow.
            for (int i = 0; i < 20; i++) {
                session.progress("flood-" + i);
            }
            assertTrue(recorder.droppedSteps() > 0,
                    "steps past queue capacity must be dropped and counted");
            session.complete();
            store.gate.countDown();
            awaitStatus(store.delegate, session.tapeRunId(), TapeStatus.COMPLETED);
            var run = findRun(store.delegate, session.tapeRunId());
            assertEquals(TapeStatus.COMPLETED, run.status(),
                    "the terminal must not ride the droppable queue");
            assertTrue(run.droppedSteps() > 0,
                    "the run row must expose the overflow drops: " + run);
        } finally {
            recorder.close();
        }
    }

    @Test
    void terminalStatusIsWriteOnceAndLaterTerminalsAreCounted() throws Exception {
        var store = new InMemoryTapeStore();
        var recorder = new TapeRecorder(store);
        try {
            var session = new TapeRecordingSession(recorder, new NoopDelegate(),
                    TapeRunInfo.endpoint("conv", "res-9", "/chat", "m", "rt"));
            session.bindRun("run-9", "alice");
            session.send("some text");
            recorder.resourceDisconnected("res-9");
            // In-chain error AFTER the disconnect cancel: counted, never a flip.
            session.error(new RuntimeException("late error"));
            awaitStatus(store, "run-9", TapeStatus.CANCELLED);
            assertEquals(TapeStatus.CANCELLED, findRun(store, "run-9").status(),
                    "CANCELLED-then-ERROR flapping must be impossible");
            assertTrue(recorder.lateTerminals() >= 1,
                    "the losing terminal must be counted");
        } finally {
            recorder.close();
        }
    }

    @Test
    void appendAfterTerminalIsDroppedAndCounted() throws Exception {
        var store = new InMemoryTapeStore();
        var recorder = new TapeRecorder(store);
        try {
            var session = pipelineSession(recorder);
            session.complete();
            awaitStatus(store, session.tapeRunId(), TapeStatus.COMPLETED);
            var persisted = store.readSteps(session.tapeRunId(), 0, 0).size();
            var droppedBefore = recorder.droppedSteps();
            session.send("late text");
            session.progress("late progress");
            assertEquals(droppedBefore + 2, recorder.droppedSteps(),
                    "post-terminal steps must be dropped and counted");
            assertEquals(persisted, store.readSteps(session.tapeRunId(), 0, 0).size(),
                    "post-terminal steps must never be inserted");
        } finally {
            recorder.close();
        }
    }

    @Test
    void resourceDisconnectMarksCancelledOnlyAfterDrainingQueuedSteps() throws Exception {
        var store = new GatedStore();
        var recorder = new TapeRecorder(store);
        try {
            var session = new TapeRecordingSession(recorder, new NoopDelegate(),
                    TapeRunInfo.endpoint("conv", "res-1", "/chat", "m", "rt"));
            session.bindRun("run-d", "alice");
            session.progress("a");
            assertTrue(store.entered.await(5, TimeUnit.SECONDS),
                    "writer must be blocked inside the first append");
            session.progress("b");
            session.progress("c");
            recorder.resourceDisconnected("res-1");
            store.gate.countDown();
            awaitStatus(store.delegate, "run-d", TapeStatus.CANCELLED);
            var steps = store.delegate.readSteps("run-d", 0, 0);
            assertEquals(3, steps.size(),
                    "steps queued before the disconnect must land before CANCELLED: " + steps);
            assertEquals(0, findRun(store.delegate, "run-d").droppedSteps(),
                    "nothing may be dropped by the cancel ordering");
        } finally {
            recorder.close();
        }
    }

    @Test
    void idleSweepAbandonsSilentRunsAfterFlushingPendingText() throws Exception {
        var store = new InMemoryTapeStore();
        var recorder = new TapeRecorder(store, config(64, Duration.ofMillis(300)));
        try {
            var session = pipelineSession(recorder);
            // Accumulated-but-unflushed text, then silence past the idle timeout.
            session.send("orphaned text");
            awaitStatus(store, session.tapeRunId(), TapeStatus.ABANDONED);
            var steps = store.readSteps(session.tapeRunId(), 0, 0);
            assertEquals(1, steps.size(), "the sweep must flush the accumulator first: " + steps);
            assertEquals("text", steps.get(0).kind());
            assertTrue(steps.get(0).payload().contains("\"text\":\"orphaned text\""),
                    steps.get(0).payload());
        } finally {
            recorder.close();
        }
    }

    @Test
    void storeThrowIsContainedCountedAndTheWriterSurvives() throws Exception {
        var store = new ThrowingAppendStore();
        var recorder = new TapeRecorder(store);
        try {
            var first = pipelineSession(recorder);
            first.progress("will fail to persist");
            first.complete();
            awaitStatus(store.delegate, first.tapeRunId(), TapeStatus.COMPLETED);
            assertTrue(recorder.storeFailures() > 0, "the append throw must be counted");
            assertTrue(findRun(store.delegate, first.tapeRunId()).droppedSteps() > 0,
                    "steps lost to the failing store must be counted as dropped");

            // The writer must survive the throw and keep serving other runs.
            var second = pipelineSession(recorder);
            second.complete();
            awaitStatus(store.delegate, second.tapeRunId(), TapeStatus.COMPLETED);
        } finally {
            recorder.close();
        }
    }

    @Test
    void boundedUninstallBailsOutOfAWedgedStoreWithinTheJoinTimeout() throws Exception {
        var store = new WedgedStore();
        var recorder = new TapeRecorder(store);
        try {
            var session = pipelineSession(recorder);
            session.progress("wedge-me");
            assertTrue(store.entered.await(5, TimeUnit.SECONDS),
                    "writer must reach the wedged append");
            var start = System.nanoTime();
            recorder.close();
            var elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(recorder.isClosed());
            assertTrue(elapsedMs < 4500,
                    "close() must bail out on the 2s join bound, took " + elapsedMs + "ms");
            // A session holding a closed recorder degrades to a no-op forward.
            session.progress("after close");
        } finally {
            store.release.countDown();
        }
    }

    @Test
    void doubleInstallIsRefusedAndReturnsTheExistingRecorder() {
        var first = TapeSupport.install(new InMemoryTapeStore());
        try {
            var second = TapeSupport.install(new InMemoryTapeStore());
            assertSame(first, second,
                    "a second install must be refused and the existing recorder returned");
            assertFalse(first.isClosed(), "the surviving recorder must stay live");
        } finally {
            TapeSupport.uninstall(first);
        }
        assertThrows(IllegalArgumentException.class, () -> TapeSupport.install(TapeStore.NOOP),
                "TapeStore.NOOP means 'leave the tape uninstalled', not 'install a no-op'");
    }

    @Test
    void uninstallIsATokenCasAndAlwaysStopsItsOwnWriter() {
        var stale = TapeSupport.install(new InMemoryTapeStore());
        TapeSupport.uninstall(stale);
        assertFalse(TapeSupport.installed());
        assertTrue(stale.isClosed(), "uninstall must always stop the recorder's writer");

        var current = TapeSupport.install(new InMemoryTapeStore());
        try {
            // A stale uninstall must not clear a holder it no longer owns.
            TapeSupport.uninstall(stale);
            assertTrue(TapeSupport.installed(),
                    "the stale uninstall must not evict the current recorder");
            var wrapped = TapeSupport.wrap(new NoopDelegate(),
                    TapeRunInfo.pipeline("c", "m", "rt"));
            assertInstanceOf(TapeRecordingSession.class, wrapped,
                    "wrap must still record against the current recorder");
        } finally {
            TapeSupport.uninstall(current);
        }
    }

    @Test
    void wrapIsZeroCostWhenNothingIsInstalled() {
        var delegate = new NoopDelegate();
        assertFalse(TapeSupport.installed());
        assertSame(delegate, TapeSupport.wrap(delegate, TapeRunInfo.pipeline("c", "m", "rt")),
                "wrap must return the session unchanged when nothing is installed");
    }

    @Test
    void textProducedAfterDisconnectIsDroppedAndCountedNotStranded() throws Exception {
        var store = new InMemoryTapeStore();
        var recorder = new TapeRecorder(store);
        try {
            var session = new TapeRecordingSession(recorder, new NoopDelegate(),
                    TapeRunInfo.endpoint("conv", "res-7", "/chat", "m", "rt"));
            session.bindRun("run-7", "alice");
            session.send("hello");                    // accumulated, flushed by the disconnect
            recorder.resourceDisconnected("res-7");   // terminalFlush + requestedTerminal=CANCELLED
            var droppedBefore = recorder.droppedSteps();
            // Late tokens from a runtime callback that has not yet observed the
            // cancel: before the fix these appended to a stranded accumulator the
            // writer never flushed again (silent loss); now they drop-and-count.
            session.send(" world");
            session.send("!");
            assertEquals(droppedBefore + 2, recorder.droppedSteps(),
                    "text produced after the disconnect cancel must be dropped and counted, "
                            + "not stranded in the accumulator");
            awaitStatus(store, "run-7", TapeStatus.CANCELLED);
        } finally {
            recorder.close();
        }
    }

    @Test
    void inFlightRunLeftOpenAtCloseStaysOpenAsAResumeAnchor() throws Exception {
        var store = new InMemoryTapeStore();
        var recorder = new TapeRecorder(store);
        var session = pipelineSession(recorder);
        session.progress("mid-stream");               // OPEN run, no terminal signal
        awaitTrue(() -> store.listRuns(TapeQuery.all(0)).stream()
                        .anyMatch(r -> r.runId().equals(session.tapeRunId())),
                "the in-flight run to be begun in the store");
        recorder.close();                             // graceful shutdown final drain
        var run = findRun(store, session.tapeRunId());
        // An in-flight run must NOT be terminated at close: it is a resume
        // anchor. Abandoning it would poison a later crash-resume (its steps
        // would be dropped as append-after-terminal). Matches SqliteEffectJournal,
        // which never evicts a non-terminal run. The idle sweep — not close() —
        // is what bounds silent OPEN runs within a live process.
        assertEquals(TapeStatus.OPEN, run.status(),
                "an in-flight run must stay OPEN at close so crash-resume can continue it");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static TapeRun findRun(InMemoryTapeStore store, String runId) {
        return store.listRuns(TapeQuery.all(0)).stream()
                .filter(r -> r.runId().equals(runId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("run " + runId + " not in store"));
    }

    private static void awaitStatus(InMemoryTapeStore store, String runId, TapeStatus status)
            throws InterruptedException {
        awaitTrue(() -> store.listRuns(TapeQuery.all(0)).stream()
                        .anyMatch(r -> r.runId().equals(runId) && r.status() == status),
                "run " + runId + " to reach " + status);
    }

    private static void awaitTrue(BooleanSupplier condition, String what)
            throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("timed out waiting for " + what);
    }

    /** Forwards everything to a live in-memory store — the override seam for fault fakes. */
    private static class ForwardingStore implements TapeStore {
        final InMemoryTapeStore delegate = new InMemoryTapeStore();

        @Override
        public void begin(TapeRun run) {
            delegate.begin(run);
        }

        @Override
        public void append(String runId, List<TapeStep> steps) {
            delegate.append(runId, steps);
        }

        @Override
        public void markTerminal(String runId, TapeStatus status, Counters counters) {
            delegate.markTerminal(runId, status, counters);
        }

        @Override
        public List<TapeRun> listRuns(TapeQuery query) {
            return delegate.listRuns(query);
        }

        @Override
        public List<TapeStep> readSteps(String runId, long fromSeq, int max) {
            return delegate.readSteps(runId, fromSeq, max);
        }

        @Override
        public Optional<String> fork(String runId) {
            return delegate.fork(runId);
        }

        @Override
        public void removeRun(String runId) {
            delegate.removeRun(runId);
        }

        @Override
        public int maxRuns() {
            return delegate.maxRuns();
        }

        @Override
        public int maxStepsPerRun() {
            return delegate.maxStepsPerRun();
        }

        @Override
        public boolean durable() {
            return false;
        }

        @Override
        public String name() {
            return "forwarding";
        }
    }

    /** Every append parks on {@code gate} (bounded), signalling {@code entered} first. */
    private static final class GatedStore extends ForwardingStore {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch gate = new CountDownLatch(1);

        @Override
        public void append(String runId, List<TapeStep> steps) {
            entered.countDown();
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            super.append(runId, steps);
        }
    }

    /** Models a truly wedged backend: append blocks and shrugs off interrupts. */
    private static final class WedgedStore extends ForwardingStore {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void append(String runId, List<TapeStep> steps) {
            entered.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    // Deliberately wedged for the bounded-uninstall test: the
                    // fake ignores the interrupt and keeps blocking.
                }
            }
            super.append(runId, steps);
        }
    }

    /** Append always throws; begin / markTerminal still reach the delegate. */
    private static final class ThrowingAppendStore extends ForwardingStore {
        @Override
        public void append(String runId, List<TapeStep> steps) {
            throw new IllegalStateException("append boom");
        }
    }

    private static final class NoopDelegate implements StreamingSession {
        @Override
        public String sessionId() {
            return "leaf";
        }

        @Override
        public void send(String text) {
            // no-op leaf
        }

        @Override
        public void sendMetadata(String key, Object value) {
            // no-op leaf
        }

        @Override
        public void progress(String message) {
            // no-op leaf
        }

        @Override
        public void complete() {
            // no-op leaf
        }

        @Override
        public void complete(String summary) {
            // no-op leaf
        }

        @Override
        public void error(Throwable t) {
            // no-op leaf
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }
}
