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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins {@link StreamIdleWatchdog}: one fire per turn, activity and pauses
 * deferring it, one terminal per turn, the guarded handle, the outside close,
 * the bound turned off, discovery through injectables, and the tracked
 * session forwarding the whole {@link StreamingSession} surface. Clock-driven
 * cases run on a manual ticker and call {@code check()} directly, so a loaded
 * machine cannot make them pass or fail.
 */
class StreamIdleWatchdogTest {

    private static final Duration BOUND = Duration.ofMinutes(5);
    private static final long BOUND_NANOS = BOUND.toNanos();
    private static final Duration GENEROUS = Duration.ofSeconds(20);

    /** A ticker the test moves by hand. */
    private static final class ManualTicker {
        final AtomicLong now = new AtomicLong(1_000_000_000L);
        /** Runs once, inside the next read: what another thread does while check() looks at the clock. */
        volatile Runnable onNextRead;

        long get() {
            var hook = onNextRead;
            if (hook != null) {
                onNextRead = null;
                hook.run();
            }
            return now.get();
        }

        void advance(long nanos) {
            now.addAndGet(nanos);
        }
    }

    /** Starts no thread: the test drives {@code check()} itself. */
    private static final BiFunction<String, Runnable, Thread> NO_THREAD = (name, task) -> null;

    private static StreamIdleWatchdog manual(StreamingSession session, ManualTicker ticker) {
        return new StreamIdleWatchdog(BOUND, session, "test", ticker::get,
                Duration.ofMillis(10), Duration.ofSeconds(30), NO_THREAD);
    }

    /**
     * A session with no single-close guard of its own, so a second terminal
     * the watchdog let through would show here. Like a real session, a
     * terminal call closes it.
     */
    static class RecordingSession implements StreamingSession {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final AtomicInteger terminals = new AtomicInteger();
        volatile boolean closed;
        volatile String runId;

        @Override public String sessionId() { return "s1"; }
        @Override public Optional<String> runId() { return Optional.ofNullable(runId); }
        @Override public void send(String text) { calls.add("send:" + text); }
        @Override public void sendMetadata(String key, Object value) { calls.add("meta:" + key); }
        @Override public void progress(String message) { calls.add("progress:" + message); }
        @Override public void emit(AiEvent event) {
            calls.add("emit:" + event.getClass().getSimpleName());
            if (event instanceof AiEvent.Complete || event instanceof AiEvent.Error) {
                terminals.incrementAndGet();
            }
        }
        @Override public void complete() {
            calls.add("complete");
            terminals.incrementAndGet();
            closed = true;
        }
        @Override public void complete(String summary) {
            calls.add("complete:" + summary);
            terminals.incrementAndGet();
            closed = true;
        }
        @Override public void error(Throwable t) {
            calls.add("error");
            errors.add(t);
            terminals.incrementAndGet();
            closed = true;
        }
        @Override public boolean isClosed() { return closed; }
    }

    /** A handle whose model call never settles. */
    private static class StuckHandle implements ExecutionHandle {
        final AtomicInteger cancels = new AtomicInteger();
        final CompletableFuture<Void> done = new CompletableFuture<>();

        @Override public void cancel() { cancels.incrementAndGet(); }
        @Override public boolean isDone() { return done.isDone(); }
        @Override public CompletableFuture<Void> whenDone() { return done; }
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        var deadline = System.nanoTime() + GENEROUS.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting for " + what);
            }
            Thread.sleep(5);
        }
    }

    // -- fire --

    @Test
    void firesOnceWithATimeoutNamingTheProperty() throws Exception {
        var delegate = new RecordingSession();
        var names = new CopyOnWriteArrayList<String>();
        var wd = new StreamIdleWatchdog(Duration.ofMillis(200), delegate, "test", System::nanoTime,
                Duration.ofMillis(10), Duration.ofSeconds(30),
                (name, task) -> {
                    names.add(name);
                    return Thread.ofVirtual().name(name).start(task);
                });

        await(() -> !delegate.errors.isEmpty(), "the idle fire");
        assertTrue(wd.timer().join(GENEROUS), "the timer ends with the turn");

        assertEquals(List.of("atmosphere-stream-idle-watchdog-test"), names);
        assertEquals(1, delegate.errors.size());
        var timeout = assertInstanceOf(TimeoutException.class, delegate.errors.get(0));
        assertEquals("The model sent nothing for PT0.2S (" + AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY + ")",
                timeout.getMessage());
        assertTrue(wd.timedOut());
        assertTrue(wd.session().hasErrored());
        assertTrue(wd.session().isClosed());

        // Nothing after the error: a late fire, a late chunk, a late terminal.
        wd.fire();
        wd.session().send("late");
        wd.session().complete();
        wd.session().error(new IllegalStateException("late"));
        assertEquals(1, delegate.terminals.get());
        assertEquals(List.of("error"), delegate.calls);
    }

    @Test
    void activityDefersTheFire() {
        var delegate = new RecordingSession();
        var ticker = new ManualTicker();
        var wd = manual(delegate, ticker);

        ticker.advance(BOUND_NANOS - 1);
        assertTrue(wd.check() > 0, "not idle yet");
        wd.session().send("chunk");
        ticker.advance(BOUND_NANOS - 1);
        assertTrue(wd.check() > 0, "an output call is activity");
        wd.activity();
        ticker.advance(BOUND_NANOS - 1);
        assertTrue(wd.check() > 0, "a transport's activity() counts too");
        assertFalse(wd.timedOut());

        ticker.advance(2);
        assertTrue(wd.check() < 0);
        assertTrue(wd.timedOut());
        assertEquals(1, delegate.errors.size());
    }

    @Test
    void nestedPausesOnThreeThreadsNeverFireAndClosingRestartsTheWindow() throws Exception {
        var delegate = new RecordingSession();
        var ticker = new ManualTicker();
        var wd = manual(delegate, ticker);

        var opened = new CountDownLatch(3);
        var release = new CountDownLatch[] {new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)};
        var closed = new CountDownLatch[] {new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)};
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 3; i++) {
            var index = i;
            threads.add(Thread.ofPlatform().start(() -> {
                var pause = StreamIdleWatchdog.pauseFor(wd.session());
                try {
                    opened.countDown();
                    release[index].await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    pause.close();
                }
                closed[index].countDown();
            }));
        }
        assertTrue(opened.await(GENEROUS.toSeconds(), TimeUnit.SECONDS));
        assertEquals(3, wd.pauseDepth());

        ticker.advance(10 * BOUND_NANOS);
        assertTrue(wd.check() > 0, "paused three times: never idle");

        release[0].countDown();
        assertTrue(closed[0].await(GENEROUS.toSeconds(), TimeUnit.SECONDS));
        assertEquals(2, wd.pauseDepth());
        ticker.advance(10 * BOUND_NANOS);
        assertTrue(wd.check() > 0, "still paused twice");

        release[1].countDown();
        release[2].countDown();
        for (var thread : threads) {
            assertTrue(thread.join(GENEROUS));
        }
        assertEquals(0, wd.pauseDepth());
        assertTrue(wd.check() > 0, "closing the last pause restarts the full window");
        ticker.advance(BOUND_NANOS - 1);
        assertTrue(wd.check() > 0);
        assertFalse(wd.timedOut());
        ticker.advance(2);
        assertTrue(wd.check() < 0);
        assertTrue(wd.timedOut());
    }

    @Test
    void closingAPauseTwiceDoesNotUnpauseAnother() {
        var ticker = new ManualTicker();
        var wd = manual(new RecordingSession(), ticker);
        var outer = wd.pause();
        var inner = wd.pause();
        inner.close();
        inner.close();
        assertEquals(1, wd.pauseDepth());
        ticker.advance(10 * BOUND_NANOS);
        assertTrue(wd.check() > 0);
        outer.close();
        assertEquals(0, wd.pauseDepth());
    }

    // -- one terminal --

    @Test
    void aCompleteBeforeTheFireWinsAndTheFireWritesNothing() {
        var delegate = new RecordingSession();
        var wd = manual(delegate, new ManualTicker());
        wd.session().complete("done");
        wd.fire();
        assertEquals(1, delegate.terminals.get());
        assertEquals(List.of("complete:done"), delegate.calls);
        assertFalse(wd.timedOut());
        assertFalse(wd.session().hasErrored());
        assertTrue(wd.isStopped(), "a terminal stops the clock");
    }

    @Test
    void theFireBeforeACompleteWinsAndTheCompleteIsDropped() {
        var delegate = new RecordingSession();
        var wd = manual(delegate, new ManualTicker());
        wd.fire();
        wd.session().complete();
        wd.session().emit(new AiEvent.Complete(null, Map.of()));
        wd.session().close();
        assertEquals(1, delegate.terminals.get());
        assertEquals(List.of("error"), delegate.calls);
        assertTrue(wd.timedOut());
    }

    @Test
    void aTerminalEventAndCloseTakeTheSingleTerminalToo() {
        var delegate = new RecordingSession();
        var wd = manual(delegate, new ManualTicker());
        wd.session().emit(new AiEvent.Error("boom", "x", false));
        wd.fire();
        wd.session().close();
        assertEquals(1, delegate.terminals.get());
        assertFalse(wd.timedOut());
        assertFalse(wd.session().hasErrored(),
                "an Error event reads as the wrapped session reads it, which it does not mark errored");

        var second = new RecordingSession();
        var wd2 = manual(second, new ManualTicker());
        wd2.session().close();
        wd2.fire();
        assertEquals(List.of("complete"), second.calls, "close() completes an open session once");
        assertFalse(wd2.timedOut());
    }

    @Test
    void aRacingCompleteAndFireGiveExactlyOneTerminalThatTimedOutAgreesWith() throws Exception {
        for (int i = 0; i < 300; i++) {
            var delegate = new RecordingSession();
            var wd = manual(delegate, new ManualTicker());
            var barrier = new CyclicBarrier(2);
            var index = i;
            var completer = Thread.ofPlatform().start(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                if (index % 2 == 0) {
                    wd.session().complete();
                } else {
                    wd.session().error(new IllegalStateException("model failed"));
                }
            });
            barrier.await();
            wd.fire();
            assertTrue(completer.join(GENEROUS));

            assertEquals(1, delegate.terminals.get(), "iteration " + i + ": " + delegate.calls);
            var timedOutOnTheWire = delegate.errors.size() == 1
                    && delegate.errors.get(0) instanceof TimeoutException;
            assertEquals(timedOutOnTheWire, wd.timedOut(), "iteration " + i + ": " + delegate.calls);
        }
    }

    @Test
    void stateReadsAsTheWrappedSessionsAndOnlyTheFireAddsToIt() {
        // A decorator that handles a schema rejection itself (the native
        // structured-output fall-back) is neither closed nor errored after it.
        var swallowing = new RecordingSession() {
            @Override public void error(Throwable t) {
                calls.add("swallowed");
            }
            @Override public boolean hasErrored() {
                return false;
            }
        };
        var wd = manual(swallowing, new ManualTicker());
        wd.session().error(new IllegalStateException("schema rejected"));
        assertEquals(List.of("swallowed"), swallowing.calls);
        assertFalse(wd.session().hasErrored(),
                "the tracked session must not read errored where the wrapped one does not");
        assertFalse(wd.session().isClosed());

        var tracking = new RecordingSession() {
            @Override public boolean hasErrored() {
                return !errors.isEmpty();
            }
        };
        var wd2 = manual(tracking, new ManualTicker());
        wd2.session().error(new IllegalStateException("model failed"));
        assertTrue(wd2.session().hasErrored(), "the wrapped session's error is forwarded");

        var plain = new RecordingSession();
        var wd3 = manual(plain, new ManualTicker());
        wd3.fire();
        assertTrue(wd3.session().hasErrored(), "the fire is an error although the wrapped session tracks none");
        assertTrue(wd3.session().isClosed());
    }

    @Test
    void aCancelLandingWhileTheClockLooksLeavesTheFireNothingToWrite() {
        var delegate = new RecordingSession();
        var ticker = new ManualTicker();
        var wd = manual(delegate, ticker);
        var guarded = wd.attach(new ExecutionHandle.Settable(null));

        ticker.advance(10 * BOUND_NANOS);
        // check() has passed its stopped test; the caller cancels before it fires.
        ticker.onNextRead = guarded::cancel;

        assertTrue(wd.check() < 0);
        assertFalse(wd.timedOut(), "a cancelled turn never times out");
        assertEquals(List.of(), delegate.calls, "a cancel writes no frame");
        assertFalse(guarded.whenDone().isCompletedExceptionally(), "a cancel is not a timeout");
    }

    @Test
    void aCloseLandingWhileTheClockLooksLeavesTheFireNothingToWrite() {
        var delegate = new RecordingSession();
        var ticker = new ManualTicker();
        var wd = manual(delegate, ticker);
        var inner = new StuckHandle();
        wd.attach(inner);

        ticker.advance(10 * BOUND_NANOS);
        ticker.onNextRead = wd::close;

        assertTrue(wd.check() < 0);
        assertFalse(wd.timedOut());
        assertEquals(List.of(), delegate.calls, "close() writes nothing, and the fire after it neither");
        assertEquals(0, inner.cancels.get(), "close() cancels nothing");
        wd.session().complete();
        assertEquals(List.of("complete"), delegate.calls, "a terminal after close() still goes through");
    }

    @Test
    void aSaturatedBoundNeverFiresOnAClockThatSteppedBack() {
        var delegate = new RecordingSession();
        var ticker = new ManualTicker();
        var wd = new StreamIdleWatchdog(Duration.ofSeconds(Long.MAX_VALUE), delegate, "test", ticker::get,
                Duration.ofMillis(10), Duration.ofSeconds(30), NO_THREAD);
        ticker.advance(10);
        wd.activity();
        // An activity() between check()'s two reads makes the idle time negative.
        ticker.advance(-10);

        assertTrue(wd.check() > 0, "a negative idle time must not overflow the saturated bound");
        assertFalse(wd.timedOut());
        assertEquals(List.of(), delegate.calls);
    }

    // -- the guarded handle --

    @Test
    void cancellingAnEarlierAttemptsHandleCancelsTheAttemptThatRunsNow() {
        var delegate = new RecordingSession();
        var ticker = new ManualTicker();
        var wd = manual(delegate, ticker);
        var first = new StuckHandle();
        var second = new StuckHandle();
        var g1 = wd.attach(first);
        var g2 = wd.attach(second); // the retry

        g1.cancel();

        assertEquals(1, first.cancels.get());
        assertEquals(1, second.cancels.get(), "the attempt that runs now must not outlive the cancel unbounded");
        assertTrue(g2.isDone());
        assertFalse(g2.whenDone().isCompletedExceptionally(), "a cancel is not an error");
        ticker.advance(10 * BOUND_NANOS);
        assertTrue(wd.check() < 0);
        assertFalse(wd.timedOut());
        assertEquals(List.of(), delegate.calls);

        g2.cancel();
        g1.cancel();
        assertEquals(1, first.cancels.get(), "each attempt is cancelled once");
        assertEquals(1, second.cancels.get());
    }

    @Test
    void aFireBeforeAttachCancelsTheHandleOnArrival() {
        var wd = manual(new RecordingSession(), new ManualTicker());
        wd.fire();
        var cancels = new AtomicInteger();
        var inner = new ExecutionHandle.Settable(cancels::incrementAndGet);

        var guarded = wd.attach(inner);

        assertEquals(1, cancels.get(), "a handle attached after the fire is cancelled on arrival");
        assertTrue(guarded.isDone());
        var thrown = assertThrows(ExecutionException.class, () -> guarded.whenDone().get());
        assertInstanceOf(TimeoutException.class, thrown.getCause());
    }

    @Test
    void theFireSettlesTheGuardedHandleWithTheTimeoutBeforeTheInnerCancel() {
        var wd = manual(new RecordingSession(), new ManualTicker());
        // Settable completes its own future normally on cancel: the guarded
        // handle must already carry the timeout by then.
        var inner = new ExecutionHandle.Settable(null);
        var guarded = wd.attach(inner);
        wd.fire();
        assertTrue(inner.isCancelled());
        var thrown = assertThrows(ExecutionException.class, () -> guarded.whenDone().get());
        assertInstanceOf(TimeoutException.class, thrown.getCause());
    }

    @Test
    void theGuardedHandleFollowsTheInnerOne() throws Exception {
        var wd = manual(new RecordingSession(), new ManualTicker());
        var ok = new ExecutionHandle.Settable(null);
        var g1 = wd.attach(ok);
        assertFalse(g1.isDone());
        ok.complete();
        g1.whenDone().get(GENEROUS.toSeconds(), TimeUnit.SECONDS);

        var failing = new ExecutionHandle.Settable(null);
        var g2 = wd.attach(failing);
        failing.completeExceptionally(new IllegalStateException("pre-stream"));
        var thrown = assertThrows(ExecutionException.class, () -> g2.whenDone().get());
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        assertFalse(wd.isStopped(), "an attempt that settles does not end the turn: a retry may follow");
    }

    @Test
    void theGuardedHandleSettlesAlthoughTheInnerNeverDoesAndWarnsOnce() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(StreamIdleWatchdog.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var wd = new StreamIdleWatchdog(BOUND, new RecordingSession(), "stuck-runtime",
                    new ManualTicker()::get, Duration.ofMillis(10), Duration.ofMillis(50), NO_THREAD);
            var inner = new StuckHandle();
            var guarded = wd.attach(inner);

            wd.fire();

            assertTrue(guarded.isDone(), "the caller is released although the model call is not");
            assertEquals(1, inner.cancels.get());
            await(() -> unreleasedWarnings(appender) == 1, "the unreleased-call WARN");

            // A second abort path and a later attempt neither cancel again nor warn again.
            guarded.cancel();
            var later = new StuckHandle();
            wd.attach(later);
            assertEquals(1, inner.cancels.get());
            assertEquals(1, later.cancels.get());
            Thread.sleep(500);
            assertEquals(1, unreleasedWarnings(appender), "the WARN is logged once per turn");
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static long unreleasedWarnings(ListAppender<ILoggingEvent> appender) {
        // The JDK delay thread appends under the appender's lock.
        synchronized (appender) {
            return appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .filter(e -> e.getFormattedMessage().startsWith("stuck-runtime did not release its model call"))
                    .count();
        }
    }

    @Test
    void aPlainCancelSettlesNormallyAlthoughTheInnerCancelFailsItsFuture() throws Exception {
        var wd = manual(new RecordingSession(), new ManualTicker());
        var inner = new StuckHandle() {
            @Override public void cancel() {
                super.cancel();
                done.cancel(true);
            }
        };
        var guarded = wd.attach(inner);
        guarded.cancel();
        guarded.whenDone().get(GENEROUS.toSeconds(), TimeUnit.SECONDS);
        assertFalse(guarded.whenDone().isCompletedExceptionally(), "a cancel is not an error");
        assertEquals(1, inner.cancels.get());
    }

    @Test
    void cancelCancelsTheInnerOnceAndStopsTheClock() {
        var delegate = new RecordingSession();
        var ticker = new ManualTicker();
        var wd = manual(delegate, ticker);
        var cancels = new AtomicInteger();
        var guarded = wd.attach(new ExecutionHandle.Settable(cancels::incrementAndGet));

        guarded.cancel();
        guarded.cancel();

        assertEquals(1, cancels.get());
        assertTrue(guarded.isDone());
        assertTrue(wd.isStopped());
        ticker.advance(10 * BOUND_NANOS);
        assertTrue(wd.check() < 0);
        assertFalse(wd.timedOut(), "a cancelled turn never times out");
        assertEquals(List.of(), delegate.calls, "a cancel writes no frame");

        var retry = new AtomicInteger();
        wd.attach(new ExecutionHandle.Settable(retry::incrementAndGet));
        assertEquals(1, retry.get(), "an attempt attached after the cancel is cancelled on arrival");
    }

    // -- outside close --

    @Test
    void anOutsideCloseCancelsTheHandleAndSettlesWritingNothing() throws Exception {
        var delegate = new RecordingSession();
        var cancels = new AtomicInteger();
        var wd = new StreamIdleWatchdog(BOUND, delegate, "test", System::nanoTime,
                Duration.ofMillis(10), Duration.ofSeconds(30),
                (name, task) -> Thread.ofVirtual().name(name).start(task));
        var guarded = wd.attach(new ExecutionHandle.Settable(cancels::incrementAndGet));

        delegate.closed = true; // the @Prompt limit or the sweep closed it

        guarded.whenDone().get(GENEROUS.toSeconds(), TimeUnit.SECONDS);
        assertTrue(wd.timer().join(GENEROUS));
        assertEquals(1, cancels.get());
        assertEquals(List.of(), delegate.calls, "no frame after an outside close");
        assertFalse(wd.timedOut());
        wd.session().complete();
        assertEquals(0, delegate.terminals.get(), "the outside close was the terminal");
    }

    // -- bound off --

    @Test
    void withTheBoundOffNoThreadStartsAndCancelStillWorks() {
        var started = new AtomicInteger();
        BiFunction<String, Runnable, Thread> counting = (name, task) -> {
            started.incrementAndGet();
            return null;
        };
        var off = new StreamIdleWatchdog(Duration.ZERO, new RecordingSession(), "test",
                System::nanoTime, Duration.ofMillis(10), Duration.ofSeconds(30), counting);
        assertEquals(0, started.get());
        assertNull(off.timer());
        new StreamIdleWatchdog(Duration.ofMillis(-1), new RecordingSession(), "test",
                System::nanoTime, Duration.ofMillis(10), Duration.ofSeconds(30), counting);
        assertEquals(0, started.get(), "a negative bound is off too");
        new StreamIdleWatchdog(Duration.ofMinutes(1), new RecordingSession(), "test",
                System::nanoTime, Duration.ofMillis(10), Duration.ofSeconds(30), counting);
        assertEquals(1, started.get(), "a positive bound starts the timer: the counter is not vacuous");

        try (var environment = AiEnvironment.create(
                Map.of(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY, "0")::get)) {
            var wd = StreamIdleWatchdog.open(environment, new RecordingSession(), "test");
            assertNull(wd.timer(), "the environment's 0 turns the bound off");
            var cancels = new AtomicInteger();
            var guarded = wd.attach(new ExecutionHandle.Settable(cancels::incrementAndGet));
            guarded.cancel();
            assertEquals(1, cancels.get());
            assertTrue(guarded.isDone());
            wd.close();
        }
        try (var environment = AiEnvironment.create(
                Map.of(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY, "5m")::get)) {
            var wd = StreamIdleWatchdog.open(environment, new RecordingSession(), "env-runtime");
            assertNotNull(wd.timer(), "the environment's bound starts the timer");
            assertEquals("atmosphere-stream-idle-watchdog-env-runtime", wd.timer().getName());
            wd.close();
            assertTrue(wd.timer().join(GENEROUS), "close() ends the timer");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
    }

    // -- discovery --

    /** A wrapper that does not extend DelegatingStreamingSession and forwards only injectables. */
    private static StreamingSession opaqueWrapper(StreamingSession inner) {
        return new StreamingSession() {
            @Override public String sessionId() { return "opaque"; }
            @Override public Map<Class<?>, Object> injectables() { return inner.injectables(); }
            @Override public void send(String text) { inner.send(text); }
            @Override public void sendMetadata(String key, Object value) { inner.sendMetadata(key, value); }
            @Override public void progress(String message) { inner.progress(message); }
            @Override public void complete() { inner.complete(); }
            @Override public void complete(String summary) { inner.complete(summary); }
            @Override public void error(Throwable t) { inner.error(t); }
            @Override public boolean isClosed() { return inner.isClosed(); }
        };
    }

    @Test
    void ofFindsTheWatchdogAcrossANonDelegatingWrapperAndTheNestedOneWins() {
        var outer = StreamIdleWatchdog.open(Duration.ZERO, new RecordingSession(), "outer");
        var wrapped = opaqueWrapper(outer.session());
        assertSame(outer, StreamIdleWatchdog.of(outer.session()).orElseThrow());
        assertSame(outer, StreamIdleWatchdog.of(wrapped).orElseThrow());

        var nested = StreamIdleWatchdog.open(Duration.ZERO, wrapped, "nested");
        assertSame(nested, StreamIdleWatchdog.of(nested.session()).orElseThrow());
        assertSame(nested, StreamIdleWatchdog.of(opaqueWrapper(nested.session())).orElseThrow(),
                "a nested turn's own entry wins over its parent's");
        assertSame(nested, nested.session().injectables().get(StreamIdleWatchdog.class));

        assertTrue(StreamIdleWatchdog.of(new RecordingSession()).isEmpty());
        assertTrue(StreamIdleWatchdog.of(null).isEmpty());
        StreamIdleWatchdog.pauseFor(null).close();
        StreamIdleWatchdog.pauseFor(new RecordingSession()).close();

        var pause = StreamIdleWatchdog.pauseFor(opaqueWrapper(nested.session()));
        assertEquals(1, nested.pauseDepth());
        assertEquals(0, outer.pauseDepth(), "the pause is keyed by the innermost turn");
        pause.close();
        assertEquals(0, nested.pauseDepth());
    }

    @Test
    void injectablesKeepTheWrappedSessionsEntries() {
        var delegate = new RecordingSession() {
            @Override public Map<Class<?>, Object> injectables() {
                return Map.of(String.class, "kept");
            }
        };
        var wd = StreamIdleWatchdog.open(Duration.ZERO, delegate, "test");
        assertEquals("kept", wd.session().injectables().get(String.class));
        assertSame(wd, wd.session().injectables().get(StreamIdleWatchdog.class));
    }

    // -- the whole session surface --

    private static final Set<String> OUTPUT = Set.of("send", "sendContent", "stream", "sendMetadata",
            "usage", "confidence", "toolCallDelta", "progress", "handoff", "emit");
    private static final Set<String> TERMINAL = Set.of("complete", "error", "close");
    private static final Set<String> PASS_THROUGH = Set.of("sessionId", "runId", "injectables",
            "isClosed", "isTurnCancelled", "turnDeadline", "hasErrored", "onTerminate");

    @Test
    void everyStreamingSessionMethodIsTrackedAndForwarded() throws Exception {
        var methods = Arrays.stream(StreamingSession.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && !m.isBridge())
                .filter(m -> !Modifier.isPrivate(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()))
                .toList();
        assertTrue(methods.size() >= 20, "reflection filter sanity: " + methods.size());

        var unclassified = new ArrayList<String>();
        for (var m : methods) {
            if (!OUTPUT.contains(m.getName()) && !TERMINAL.contains(m.getName())
                    && !PASS_THROUGH.contains(m.getName())) {
                unclassified.add(m.toString());
                continue;
            }
            var ticker = new ManualTicker();
            var received = Collections.synchronizedList(new ArrayList<Method>());
            var delegate = (StreamingSession) Proxy.newProxyInstance(
                    StreamingSession.class.getClassLoader(), new Class<?>[] {StreamingSession.class},
                    (proxy, called, args) -> {
                        received.add(called);
                        return defaultReturn(called);
                    });
            var wd = manual(delegate, ticker);
            var tracked = wd.session();
            var overriding = tracked.getClass().getMethod(m.getName(), m.getParameterTypes());
            assertTrue(overriding.getDeclaringClass() != StreamingSession.class,
                    m + " falls back to the interface default instead of being tracked");

            var before = wd.lastActivityNanos();
            ticker.advance(1);
            m.invoke(tracked, argumentsFor(m));

            assertTrue(received.stream().anyMatch(r -> sameSignature(r, m)),
                    m + " is not forwarded to the wrapped session; it saw " + received);
            if (OUTPUT.contains(m.getName())) {
                assertTrue(wd.lastActivityNanos() > before, m + " is output but records no activity");
            }
        }
        assertEquals(List.of(), unclassified,
                "classify each new StreamingSession method as output, terminal or pass-through, "
                        + "and track or forward it in StreamIdleWatchdog's session");
    }

    private static boolean sameSignature(Method a, Method b) {
        return a.getName().equals(b.getName()) && Arrays.equals(a.getParameterTypes(), b.getParameterTypes());
    }

    private static Object defaultReturn(Method m) {
        var type = m.getReturnType();
        if (type == boolean.class) {
            return false;
        }
        if (type == String.class) {
            return "s1";
        }
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (type == Map.class) {
            return Map.of();
        }
        return null;
    }

    private static Object[] argumentsFor(Method m) {
        return Arrays.stream(m.getParameterTypes())
                .map(type -> {
                    if (type == String.class) {
                        return "x";
                    }
                    if (type == List.class) {
                        return List.of();
                    }
                    if (type == AiEvent.class) {
                        return new AiEvent.Progress("p", null);
                    }
                    if (type == Throwable.class) {
                        return new IllegalStateException("x");
                    }
                    if (type == AutoCloseable.class) {
                        return (AutoCloseable) () -> { };
                    }
                    return null;
                })
                .toArray();
    }

    @Test
    void aCancelledHandleSettlesOnlyOnceItsCallHasUnwound() throws Exception {
        // The caller writes its cancelled frame when the handle settles: by then
        // the model call must have unwound, as before the watchdog existed.
        var wd = StreamIdleWatchdog.open(Duration.ZERO, new RecordingSession(), "unwind");
        var unwound = new CompletableFuture<Void>();
        var guarded = wd.attach(new ExecutionHandle() {
            @Override public void cancel() { }
            @Override public boolean isDone() { return unwound.isDone(); }
            @Override public CompletableFuture<Void> whenDone() { return unwound; }
        });
        guarded.cancel();
        assertFalse(guarded.isDone(), "a cancelled handle waits for its call to unwind");
        unwound.completeExceptionally(new java.io.IOException("Stream closed"));
        assertTrue(guarded.isDone());
        assertFalse(guarded.whenDone().isCompletedExceptionally(), "the unwinding failure of a cancel is no error");
    }

    @Test
    void aCancelledHandleWhoseCallNeverUnwindsSettlesWithinTheBackstop() throws Exception {
        var wd = StreamIdleWatchdog.open(Duration.ZERO, new RecordingSession(), "stuck");
        var never = new CompletableFuture<Void>();
        var guarded = wd.attach(new ExecutionHandle() {
            @Override public void cancel() { }
            @Override public boolean isDone() { return false; }
            @Override public CompletableFuture<Void> whenDone() { return never; }
        });
        guarded.cancel();
        // Generous: the backstop is CANCEL_SETTLE (1s); a loaded runner may be late.
        guarded.whenDone().get(StreamIdleWatchdog.CANCEL_SETTLE.toMillis() + 5_000, TimeUnit.MILLISECONDS);
        assertTrue(guarded.isDone());
        assertFalse(guarded.whenDone().isCompletedExceptionally());
    }
}
