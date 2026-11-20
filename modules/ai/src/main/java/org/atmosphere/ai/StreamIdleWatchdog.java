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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/**
 * Ends a turn whose model sends nothing for longer than
 * {@value AiConfig#STREAM_IDLE_TIMEOUT_PROPERTY}, and aborts the model call
 * through the runtime's own {@link ExecutionHandle#cancel()}. A provider that
 * stops sending after the response headers, or before them, otherwise holds the
 * turn, its connection and a thread for as long as the transport lets it
 * (Correctness Invariant #2).
 *
 * <p><b>One per turn.</b> A runtime opens it once per turn with
 * {@link #open(AiEnvironment, StreamingSession, String)}, writes everything
 * through {@link #session()}, hands each attempt's handle to
 * {@link #attach(ExecutionHandle)} and {@link #close() closes} it when the turn
 * is over. It reads its bound only from the environment it is given, never from
 * process-wide state (Correctness Invariant #1).</p>
 *
 * <p><b>Clock.</b> When the bound is greater than zero, one virtual thread per
 * turn watches the time since the last activity. It wakes at least once a
 * second, so it also sees the session it wraps closed from outside (the
 * {@code @Prompt} time limit, the idle sweep): it then cancels the attached
 * handle and stops, writing no frame. It ends with the turn on every terminal
 * path: a terminal written through {@link #session()}, a fire, a cancel, an
 * outside close, or {@link #close()}. With the bound at zero no thread starts;
 * the tracked session, the single-terminal guarantee and cancel work the same.</p>
 *
 * <p><b>Activity.</b> Every output call on {@link #session()} is activity, and
 * so is every {@link #activity()} call, which transports make for each byte or
 * event they read (see {@link org.atmosphere.ai.llm.ActivityInputStream}). A
 * {@link #pause()} stops the clock while it is open, for work that is not the
 * model's (a tool, an approval, a retry backoff); pauses nest, from any thread,
 * and closing the last one restarts the full window.</p>
 *
 * <p><b>One terminal.</b> The first of {@code complete()}, {@code complete(String)},
 * {@code error(Throwable)}, a terminal {@link AiEvent} or {@code close()} on
 * {@link #session()}, a fire, or an outside close of the wrapped session wins;
 * only the winner writes to the wrapped session, and every later terminal is
 * dropped (logged at TRACE). A fire writes one
 * {@link TimeoutException} "The model sent nothing for &lt;bound&gt;
 * ({@value AiConfig#STREAM_IDLE_TIMEOUT_PROPERTY})". {@link #timedOut()} is
 * true only when the fire won, so the session and its listeners never
 * disagree. A fire takes the clock's stop before it writes, so a cancel or a
 * {@link #close()} that stopped the clock first leaves it nothing to write.</p>
 *
 * <p><b>State.</b> {@link #session()}'s {@code isClosed()} and
 * {@code hasErrored()} report the wrapped session's, plus the watchdog's own
 * fire: a turn the fire ended is closed and errored. The single terminal only
 * decides what reaches the wrapped session, never how it reads, so a wrapped
 * session that does not take an error as one (a terminal {@link AiEvent.Error}
 * event, an error a decorator handles itself) reads the same through the
 * watchdog.</p>
 *
 * <p><b>Discovery.</b> {@link #session()} publishes the watchdog in its
 * {@link StreamingSession#injectables()}, its own entry winning over any the
 * wrapped session carries, so {@link #of(StreamingSession)} finds the watchdog
 * of the innermost turn through every wrapper that forwards
 * {@code injectables()}, and an {@code @AiTool} may declare it as a
 * parameter.</p>
 */
public final class StreamIdleWatchdog implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(StreamIdleWatchdog.class);

    /** How long the clock may sleep before it looks again at the wrapped session. */
    static final Duration MAX_WAKE = Duration.ofSeconds(1);

    /** How long an aborted model call may take to settle before a WARN says it did not. */
    static final Duration RELEASE_GRACE = Duration.ofSeconds(30);

    /** How long a cancelled handle waits for its call to unwind before it settles anyway. */
    static final Duration CANCEL_SETTLE = Duration.ofSeconds(1);

    private static final Pause NO_PAUSE = () -> { };

    /** Who ended the turn. */
    private enum Terminal {
        COMPLETE, ERROR, TIMEOUT, EXTERNAL
    }

    private final Duration idleTimeout;
    private final long idleNanos;
    private final String runtimeName;
    private final LongSupplier ticker;
    private final long maxWakeNanos;
    private final Duration releaseGrace;
    private final Duration cancelSettle = CANCEL_SETTLE;
    private final StreamingSession delegate;
    private final Tracked tracked;
    private final AtomicReference<Terminal> terminal = new AtomicReference<>();
    /** Completed when the clock has nothing left to watch. */
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private final AtomicReference<Guarded> current = new AtomicReference<>();
    private final AtomicInteger pauseDepth = new AtomicInteger();
    private final AtomicBoolean releaseWarned = new AtomicBoolean();
    private final Thread timer;
    private volatile long lastActivityNanos;
    /** Set once the turn was aborted (fire, cancel, outside close): a handle attached later is cancelled on arrival. */
    private volatile boolean aborted;
    /** The fire's timeout, when the abort was a fire; {@code null} for a cancel or an outside close. */
    private volatile TimeoutException abortCause;

    StreamIdleWatchdog(Duration idleTimeout, StreamingSession session, String runtimeName,
                       LongSupplier ticker, Duration maxWake, Duration releaseGrace,
                       BiFunction<String, Runnable, Thread> threadStarter) {
        this.idleTimeout = idleTimeout == null || idleTimeout.isNegative() ? Duration.ZERO : idleTimeout;
        this.idleNanos = saturatedNanos(this.idleTimeout);
        this.delegate = Objects.requireNonNull(session, "session");
        this.runtimeName = runtimeName == null || runtimeName.isBlank() ? "AI runtime" : runtimeName;
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.maxWakeNanos = Math.max(saturatedNanos(maxWake), TimeUnit.MILLISECONDS.toNanos(1));
        this.releaseGrace = Objects.requireNonNull(releaseGrace, "releaseGrace");
        this.tracked = new Tracked(session);
        // Armed at open: waiting for the connection and the headers counts as idle.
        this.lastActivityNanos = ticker.getAsLong();
        this.timer = this.idleTimeout.isZero()
                ? null
                : threadStarter.apply("atmosphere-stream-idle-watchdog-" + this.runtimeName, this::watch);
    }

    /**
     * Opens the watchdog of a turn, bounded by its application's
     * {@link AiEnvironment#resolveStreamIdleTimeout()}.
     *
     * @param environment the environment of the application that runs the turn
     * @param session     the session the turn writes to
     * @param runtimeName the runtime's name, for logs
     * @return the turn's watchdog; its clock is running
     */
    public static StreamIdleWatchdog open(AiEnvironment environment, StreamingSession session,
                                          String runtimeName) {
        Objects.requireNonNull(environment, "environment");
        return open(environment.resolveStreamIdleTimeout(), session, runtimeName);
    }

    /**
     * Opens the watchdog of a turn with an explicit bound.
     *
     * @param idleTimeout how long the model may send nothing; {@link Duration#ZERO}
     *                    (or a negative value) turns the bound off and starts no thread
     * @param session     the session the turn writes to
     * @param runtimeName the runtime's name, for logs
     * @return the turn's watchdog; its clock is running when the bound is on
     */
    public static StreamIdleWatchdog open(Duration idleTimeout, StreamingSession session, String runtimeName) {
        return new StreamIdleWatchdog(idleTimeout, session, runtimeName, System::nanoTime,
                MAX_WAKE, RELEASE_GRACE, (name, task) -> Thread.ofVirtual().name(name).start(task));
    }

    /**
     * Finds the watchdog of the turn a session belongs to: the session itself
     * when it is a watchdog's {@link #session()}, else the entry its
     * {@link StreamingSession#injectables()} carry.
     *
     * @param session any session, possibly wrapped; may be {@code null}
     * @return the innermost turn's watchdog, or empty when the session has none
     */
    public static Optional<StreamIdleWatchdog> of(StreamingSession session) {
        if (session == null) {
            return Optional.empty();
        }
        if (session instanceof Tracked t) {
            return Optional.of(t.owner());
        }
        Map<Class<?>, Object> injectables;
        try {
            injectables = session.injectables();
        } catch (RuntimeException e) {
            logger.trace("Session {} gave no injectables; it carries no watchdog", session.getClass(), e);
            return Optional.empty();
        }
        return injectables != null && injectables.get(StreamIdleWatchdog.class) instanceof StreamIdleWatchdog w
                ? Optional.of(w)
                : Optional.empty();
    }

    /**
     * Pauses the clock of the turn a session belongs to, for work that is not
     * the model's.
     *
     * @param session any session, possibly wrapped; may be {@code null}
     * @return the open pause; a no-op when the session carries no watchdog
     */
    public static Pause pauseFor(StreamingSession session) {
        return of(session).map(StreamIdleWatchdog::pause).orElse(NO_PAUSE);
    }

    /**
     * @return the session the turn must write to: it records activity, lets one
     *         terminal through and carries this watchdog in its injectables
     */
    public StreamingSession session() {
        return tracked;
    }

    /**
     * Takes the handle of one attempt of the turn (a retry attaches again).
     *
     * @param inner the runtime's handle for the attempt
     * @return the handle to give the caller. Its {@code cancel()} cancels the
     *         turn: {@code inner} and the attempt attached last, each once, and
     *         any attempt attached after it on arrival; it stops the clock
     *         first, so the turn cannot time out after it, and settles
     *         normally (whatever {@code inner} settles with) once {@code inner}
     *         has settled, or at the latest one second after the cancel, so the
     *         caller reacting to the settled handle finds the model call
     *         already unwound unless it is stuck. Its {@code whenDone()}
     *         settles when {@code inner} does, at a fire (exceptionally, with
     *         the {@link TimeoutException}) or at a cancel, whichever comes
     *         first. When the turn was already aborted, {@code inner} is
     *         cancelled on arrival.
     */
    public ExecutionHandle attach(ExecutionHandle inner) {
        var guarded = new Guarded(Objects.requireNonNull(inner, "inner"));
        current.set(guarded);
        if (aborted) {
            guarded.abort(abortCause);
        }
        return guarded;
    }

    /** Records activity: the model, or its transport, has just sent something. */
    public void activity() {
        lastActivityNanos = ticker.getAsLong();
    }

    /**
     * Stops the clock until the returned pause is closed. Pauses nest and may
     * be opened and closed on any thread; closing the last one restarts the
     * full window.
     *
     * @return the open pause; closing it more than once has no further effect
     */
    public Pause pause() {
        pauseDepth.incrementAndGet();
        var open = new AtomicBoolean(true);
        return () -> {
            if (open.compareAndSet(true, false)) {
                activity();
                pauseDepth.decrementAndGet();
            }
        };
    }

    /** @return whether the idle bound ended the turn */
    public boolean timedOut() {
        return terminal.get() == Terminal.TIMEOUT;
    }

    /**
     * @return the {@link TimeoutException} the fire wrote, when the idle bound
     *         ended the turn; empty otherwise
     */
    public Optional<TimeoutException> timeout() {
        return timedOut() ? Optional.ofNullable(abortCause) : Optional.empty();
    }

    /**
     * Stops the clock: the turn is over. Writes nothing and cancels nothing; a
     * fire that has not yet taken the clock's stop writes nothing either, while a
     * terminal still written through {@link #session()} goes through as usual.
     * Idempotent.
     */
    @Override
    public void close() {
        stopped.complete(null);
    }

    /** An open pause of the clock. */
    @FunctionalInterface
    public interface Pause extends AutoCloseable {
        /** Ends this pause; idempotent. */
        @Override
        void close();
    }

    // -- package-private views for tests --

    int pauseDepth() {
        return pauseDepth.get();
    }

    long lastActivityNanos() {
        return lastActivityNanos;
    }

    Thread timer() {
        return timer;
    }

    boolean isStopped() {
        return stopped.isDone();
    }

    /**
     * Looks at the clock once, as the timer does on each wake.
     *
     * @return how long the timer may sleep, in nanoseconds, or a negative
     *         value when the clock has nothing left to watch
     */
    long check() {
        if (stopped.isDone()) {
            return -1;
        }
        if (terminal.get() == null && delegate.isClosed()) {
            external();
            return -1;
        }
        if (pauseDepth.get() > 0) {
            return Math.min(idleNanos, maxWakeNanos);
        }
        // Never below zero: an activity() between the two reads, or a ticker that
        // steps back, must not overflow a saturated bound into an instant fire.
        var idleSoFar = Math.max(0, ticker.getAsLong() - lastActivityNanos);
        var waitNanos = idleNanos - idleSoFar;
        if (waitNanos <= 0) {
            fire();
            return -1;
        }
        return Math.min(waitNanos, maxWakeNanos);
    }

    // -- clock --

    private void watch() {
        while (true) {
            var waitNanos = check();
            if (waitNanos < 0) {
                return;
            }
            try {
                stopped.get(Math.max(waitNanos, TimeUnit.MILLISECONDS.toNanos(1)), TimeUnit.NANOSECONDS);
                return;
            } catch (TimeoutException e) {
                // Not over yet: look at the clock again.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | CancellationException e) {
                // Stopped, one way or another: nothing to watch.
                return;
            }
        }
    }

    /**
     * Ends the turn with the idle timeout, unless it already ended or the clock
     * was already stopped (a cancel, {@link #close()}, a terminal).
     */
    void fire() {
        // Taking the stop is the fire's claim: a cancel or close() that stopped
        // the clock first, even after check() looked, leaves it nothing to write.
        if (!stopped.complete(null) || !terminal.compareAndSet(null, Terminal.TIMEOUT)) {
            return;
        }
        var timeout = new TimeoutException("The model sent nothing for " + idleTimeout
                + " (" + AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY + ")");
        logger.warn("Ending a {} turn: {}", runtimeName, timeout.getMessage());
        abortCause = timeout;
        try {
            delegate.error(timeout);
        } finally {
            abort(timeout);
        }
    }

    /** The wrapped session was closed from outside: cancel the model call, write nothing. */
    private void external() {
        if (terminal.compareAndSet(null, Terminal.EXTERNAL)) {
            logger.debug("{} turn of session {} was closed from outside; cancelling its model call",
                    runtimeName, delegate.sessionId());
            abort(null);
        } else {
            stopped.complete(null);
        }
    }

    private void abort(TimeoutException cause) {
        abortCause = cause;
        aborted = true;
        stopped.complete(null);
        var guarded = current.get();
        if (guarded != null) {
            guarded.abort(cause);
        }
    }

    /** A terminal written through the tracked session: the first one wins. */
    private boolean win(Terminal kind) {
        if (terminal.compareAndSet(null, kind)) {
            stopped.complete(null);
            return true;
        }
        logger.trace("{} turn of session {} already ended ({}); dropping a later {}",
                runtimeName, delegate.sessionId(), terminal.get(), kind);
        return false;
    }

    private void warnUnreleased(ExecutionHandle inner) {
        if (inner.isDone()) {
            return;
        }
        // A copy, so the timeout never completes the runtime's own future.
        inner.whenDone().copy()
                .orTimeout(saturatedNanos(releaseGrace), TimeUnit.NANOSECONDS)
                .whenComplete((ignored, error) -> {
                    if (error instanceof TimeoutException && releaseWarned.compareAndSet(false, true)) {
                        logger.warn("{} did not release its model call {} after it was aborted",
                                runtimeName, releaseGrace);
                    }
                });
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            // Longer than about 292 years: as good as no bound, but still one.
            return Long.MAX_VALUE;
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    /** The handle of one attempt, as the caller sees it. */
    private final class Guarded implements ExecutionHandle {

        private final ExecutionHandle inner;
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final AtomicBoolean innerCancelled = new AtomicBoolean();
        /** Set before a plain cancel reaches the inner handle: what it settles with then is no error. */
        private volatile boolean cancelling;

        Guarded(ExecutionHandle inner) {
            this.inner = inner;
            inner.whenDone().whenComplete((ignored, error) -> {
                if (error == null || cancelling) {
                    done.complete(null);
                } else {
                    done.completeExceptionally(unwrap(error));
                }
            });
        }

        @Override
        public void cancel() {
            // A cancel ends the turn: a retry attached after it is cancelled on
            // arrival, and the clock stops so it cannot fire on a cancelled turn
            // (a fire takes the stop before it writes).
            aborted = true;
            stopped.complete(null);
            // The model call is aborted before the handle settles, so a caller
            // that reacts to the settled handle (the cancelled frame) finds the
            // provider's connection already let go. An inner handle whose
            // cancel completes its own future exceptionally must not make a
            // plain cancel read as an error.
            cancelling = true;
            cancelInner();
            // It settles when the aborted call has unwound, which the caller's
            // cancelled frame then follows; a call that does not unwind within
            // CANCEL_SETTLE leaves it settled anyway, so no caller hangs on it.
            done.completeOnTimeout(null, saturatedNanos(cancelSettle), TimeUnit.NANOSECONDS);
            // The caller may hold an earlier attempt's handle: the attempt that
            // runs now is cancelled too, or it would run with no bound left.
            // aborted is written before current is read, and attach() writes
            // current before it reads aborted, so one side always sees the other.
            var live = current.get();
            if (live != null && live != this) {
                live.abort(null);
            }
        }

        /** A fire or an outside close: settle first, so a waiter wakes to the turn's outcome. */
        void abort(TimeoutException cause) {
            if (cause != null) {
                done.completeExceptionally(cause);
            } else {
                done.complete(null);
            }
            cancelInner();
        }

        private void cancelInner() {
            if (innerCancelled.compareAndSet(false, true)) {
                try {
                    inner.cancel();
                } catch (RuntimeException e) {
                    logger.debug("Cancelling the {} model call failed", runtimeName, e);
                }
                warnUnreleased(inner);
            }
        }

        @Override
        public boolean isDone() {
            return done.isDone();
        }

        @Override
        public CompletableFuture<Void> whenDone() {
            return done;
        }
    }

    /**
     * The session the turn writes to: records activity on every output call,
     * drops output once the turn ended, and lets one terminal through.
     */
    private final class Tracked extends DelegatingStreamingSession {

        Tracked(StreamingSession delegate) {
            super(delegate);
        }

        StreamIdleWatchdog owner() {
            return StreamIdleWatchdog.this;
        }

        /** Records activity; {@code false} when the turn ended and the output must be dropped. */
        private boolean live() {
            activity();
            return terminal.get() == null;
        }

        @Override
        public Map<Class<?>, Object> injectables() {
            var own = new HashMap<Class<?>, Object>(delegate.injectables());
            // Ours last: a nested turn finds its own watchdog, not its parent's.
            own.put(StreamIdleWatchdog.class, StreamIdleWatchdog.this);
            return java.util.Collections.unmodifiableMap(own);
        }

        @Override
        public void send(String text) {
            if (live()) {
                delegate.send(text);
            }
        }

        @Override
        public void sendContent(Content content) {
            if (live()) {
                delegate.sendContent(content);
            }
        }

        @Override
        public void stream(String message) {
            if (live()) {
                delegate.stream(message);
            }
        }

        @Override
        public void stream(String message, List<Content> parts) {
            if (live()) {
                delegate.stream(message, parts);
            }
        }

        @Override
        public void sendMetadata(String key, Object value) {
            if (live()) {
                delegate.sendMetadata(key, value);
            }
        }

        @Override
        public void usage(TokenUsage usage) {
            if (live()) {
                delegate.usage(usage);
            }
        }

        @Override
        public void confidence(AiConfidence confidence) {
            if (live()) {
                delegate.confidence(confidence);
            }
        }

        @Override
        public void toolCallDelta(String toolCallId, String argsChunk) {
            if (live()) {
                delegate.toolCallDelta(toolCallId, argsChunk);
            }
        }

        @Override
        public void progress(String message) {
            if (live()) {
                delegate.progress(message);
            }
        }

        @Override
        public void handoff(String agentName, String message) {
            if (live()) {
                delegate.handoff(agentName, message);
            }
        }

        @Override
        public void emit(AiEvent event) {
            activity();
            // A terminal event closes the wrapped session: it takes the
            // single terminal like complete() and error() do.
            var kind = switch (event) {
                case AiEvent.Complete c -> Terminal.COMPLETE;
                case AiEvent.Error e -> Terminal.ERROR;
                default -> null;
            };
            if (kind != null ? win(kind) : terminal.get() == null) {
                delegate.emit(event);
            }
        }

        @Override
        public void complete() {
            if (win(Terminal.COMPLETE)) {
                delegate.complete();
            }
        }

        @Override
        public void complete(String summary) {
            if (win(Terminal.COMPLETE)) {
                delegate.complete(summary);
            }
        }

        @Override
        public void error(Throwable t) {
            if (win(Terminal.ERROR)) {
                delegate.error(t);
            } else {
                logger.trace("Dropping the {} error that followed the end of the turn", runtimeName, t);
            }
        }

        @Override
        public void close() {
            // Closing an open session completes it: the same single terminal.
            if (win(Terminal.COMPLETE)) {
                super.close();
            }
        }

        @Override
        public boolean isClosed() {
            // The wrapped session's state, plus our own fire: the single
            // terminal never changes how a session the turn handed on reads.
            return terminal.get() == Terminal.TIMEOUT || delegate.isClosed();
        }

        @Override
        public boolean hasErrored() {
            return terminal.get() == Terminal.TIMEOUT || delegate.hasErrored();
        }
    }
}
