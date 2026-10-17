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

import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Keeps a run going for a grace period after the client that started it lost
 * its connection, so a client that comes back with the run id
 * ({@code X-Atmosphere-Run-Id}) reattaches to a run still streaming rather than
 * to one the disconnect cancelled. The run keeps writing into its replay buffer
 * meanwhile.
 *
 * <p>A run is <em>detached</em> with the action that cancels it. Unless a
 * reattach rescinds it first, that action runs once when the grace expires; a
 * run that ends on its own within the grace is forgotten and its timer
 * cancelled, so no cancel ever fires late (the same rule a delayed broadcast
 * follows). A reattached client that loses its connection in turn detaches the
 * run again; one that leaves on purpose cancels it at once.</p>
 *
 * <p>A run a reattached client is still listening to is not waiting for anyone:
 * the server may notice the loss of the original connection only after the
 * client came back on a new one (an idle timeout, a failed write). Such a run
 * is tracked, so its last listener leaving detaches or cancels it, but no
 * grace runs for it; a timer that finds a listener when it fires stands down
 * rather than cancel the run under it.</p>
 *
 * <p>Only a lost transport is granted the grace ({@link #isTransportLoss}): a
 * client that closes the connection on purpose has left, and its run is
 * cancelled at once. A page that reloads or navigates away is a lost transport
 * on every transport: a WebSocket closes with 1001, an HTTP transport sends its
 * close message marked going-away, and the Spring Boot WebTransport sidecar
 * reports a session the browser closed with code 0 and no atmosphere.js
 * reason, its stream unfinished, as 1001.</p>
 *
 * <p>Lifecycle (Correctness Invariant #1): the timer thread is a daemon owned by
 * this class and shared by every endpoint in the JVM, started on the first
 * detach. Each endpoint handler that served a prompt holds it
 * ({@link #acquire()}) and lets go of it on destroy ({@link #release()}),
 * having withdrawn the pending graces of the runs it started
 * ({@link #withdraw}) and cancelled those runs itself: the destroy of one
 * endpoint, or of another application's in the same JVM, leaves every other
 * run's grace armed. The last holder letting go stops the timer, as
 * {@link #shutdown()} — for a host tearing the process down — does whoever
 * holds it; a run still detached then is cancelled at once rather than left
 * waiting for a timer that is gone (Invariant #2), and nothing restarts the
 * timer until a holder acquires it again: a run detached meanwhile is
 * cancelled at once. In a JVM where nothing ever held it the first detach
 * starts it, as before. Entries exist only for runs still in flight, and each one is
 * removed when its run ends (Invariant #3).</p>
 */
public final class RunDisconnectGrace {

    private static final Logger logger = LoggerFactory.getLogger(RunDisconnectGrace.class);

    /** Status code of a WebSocket closed by a browser leaving or reloading the page. */
    private static final int GOING_AWAY = 1001;

    /**
     * The runs detached, by run id. In the framework an entry is created only
     * by the departure of the connection a run started on (the
     * {@code AiStreamingSession} of the run detaching it when that connection
     * goes) and removed when the run ends, its cancel fires, or its grace is
     * withdrawn: an entry stands for that connection being gone. A reattached
     * client's departure only re-arms or fires an existing entry, never
     * creates one ({@link #isTracked}). A reconnect under the same tracking id
     * that registers the run's session as its own again reaches the same entry
     * on its departure.
     */
    private static final ConcurrentHashMap<String, Detached> RUNS = new ConcurrentHashMap<>();
    private static final AtomicReference<ScheduledExecutorService> SCHEDULER = new AtomicReference<>();

    /** Guards {@link #holders}. */
    private static final Object HOLDERS_LOCK = new Object();

    /** How many {@link #acquire()} calls no {@link #release()} answered yet. Guarded by {@link #HOLDERS_LOCK}. */
    private static int holders;

    /** Set by the first {@link #acquire()}: from then on only a hold lets the timer start. Guarded by {@link #HOLDERS_LOCK}. */
    private static boolean everHeld;

    private RunDisconnectGrace() {
    }

    /**
     * Whether {@code event} reports a connection the client may come back on,
     * rather than a client that left on purpose. A WebSocket closed with 1000
     * (atmosphere.js closes with it on an explicit disconnect), an HTTP
     * transport's close message without a reason (atmosphere.js
     * {@code close()}), and a close the application made are a leave; a
     * cancelled connection (a lost network, a 1006, an idle eviction, a
     * container closing it), a WebSocket closed with 1001 (a browser leaving or
     * reloading the page; a WebTransport session reports the same 1001 for a
     * close with code 0 that finds its stream unfinished, which is what a
     * browser sends for such a page, while atmosphere.js {@code close()}
     * finishes the stream and closes with the reason
     * {@code 'Client disconnect'}, which reads as 1000) and an HTTP transport's
     * close message marked
     * {@code X-Atmosphere-Close-Reason: going-away} (the beacon atmosphere.js
     * sends from {@code pagehide} over SSE, long-polling and streaming) are a
     * transport loss.
     *
     * <p>The going-away mark is the client's word and is not authenticated
     * beyond the close message itself; it can only turn an immediate cancel into
     * one after the bounded grace — what dropping the connection gets anyway —
     * and a reattach is still subject to the run's ownership check.</p>
     *
     * @param event the disconnect event
     * @return {@code true} when the run of the connection is granted the grace
     */
    public static boolean isTransportLoss(AtmosphereResourceEvent event) {
        if (event == null || event.isClosedByApplication()) {
            return false;
        }
        if (event.isClosedByClient()) {
            return event instanceof AtmosphereResourceEventImpl impl
                    && (impl.webSocketCloseCode() == GOING_AWAY || impl.closedGoingAway());
        }
        return event.isCancelled();
    }

    /**
     * Keep the run going for {@code grace}, then run {@code expiry} unless a
     * client reattached meanwhile. A run already detached keeps its earlier
     * deadline.
     *
     * @param runId  the run
     * @param grace  how long to wait; zero or negative disables the grace
     * @param expiry cancels the run; runs at most once
     * @return {@code true} when the run was detached, {@code false} when the
     *         grace is disabled or the run already ended or expired (the caller
     *         cancels it now)
     */
    public static boolean detach(String runId, Duration grace, Runnable expiry) {
        return detach(runId, grace, expiry, () -> false);
    }

    /**
     * {@link #detach(String, Duration, Runnable)} a run that reattached clients
     * may be listening to. While {@code watched} answers {@code true} the run is
     * only tracked: no grace runs, and a grace that expires while it does is
     * stood down. A listener that goes away detaches the run again
     * ({@link #redetach}) or cancels it ({@link #expireNow}).
     *
     * @param runId   the run
     * @param grace   how long to wait; zero or negative disables the grace
     * @param expiry  cancels the run; runs at most once
     * @param watched whether a reattached client is listening to the run
     * @return {@code true} when the run was detached or is watched,
     *         {@code false} when the grace is disabled or the run already ended
     *         or expired (the caller cancels it now)
     */
    public static boolean detach(String runId, Duration grace, Runnable expiry, BooleanSupplier watched) {
        if (runId == null || !enabled(grace) || expiry == null || watched == null) {
            return false;
        }
        // The entry exists before the listener count is read, so a listener
        // leaving after that read finds it and detaches the run again.
        var entry = RUNS.computeIfAbsent(runId, id -> new Detached(id, expiry, watched));
        return entry.arm(grace);
    }

    /**
     * Detach again a run whose reattached client lost its connection, with the
     * action the run was first detached with.
     *
     * @return {@code true} when the run was detached
     */
    public static boolean redetach(String runId, Duration grace) {
        if (runId == null || !enabled(grace)) {
            return false;
        }
        var entry = RUNS.get(runId);
        return entry != null && entry.arm(grace);
    }

    /**
     * Restart the grace of a detached run from now: a client that cannot stay
     * subscribed (a long-polling one, which listens to the run at most while
     * one poll waits for its next event) came back for it. Nothing detaches such a run again if the client never
     * returns, so its pending cancel is pushed back rather than rescinded. Only
     * the run's own client restarts it ({@code RunReattachSupport.ownClient}),
     * not a member of a {@code broadcastReply} room that read the run id from the
     * room's reply. A
     * run not waiting out a grace is left as it is, and so is the pending
     * deadline when {@code grace} is disabled.
     *
     * @return {@code true} when the grace was restarted
     */
    public static boolean restart(String runId, Duration grace) {
        if (runId == null || !enabled(grace)) {
            return false;
        }
        var entry = RUNS.get(runId);
        return entry != null && entry.restart(grace);
    }

    /**
     * Cancel a detached run now: its reattached client left on purpose.
     *
     * @return {@code true} when this call ran the cancel
     */
    public static boolean expireNow(String runId) {
        var entry = runId != null ? RUNS.get(runId) : null;
        return entry != null && entry.fire(entry.generation(), true);
    }

    /**
     * A client reattached to the run: the pending cancel is rescinded and will
     * not fire.
     *
     * @return {@code true} when a pending cancel was rescinded; {@code false}
     *         when none was pending or the grace had already expired
     */
    public static boolean reattached(String runId) {
        var entry = runId != null ? RUNS.get(runId) : null;
        return entry != null && entry.disarm();
    }

    /**
     * Take back the pending cancel of a run waiting out its grace, without
     * running it: its owner cancels the run itself (an endpoint destroyed
     * while the run waits). A run not waiting out a grace — ended, never
     * detached, or listened to by a reattached client — is left as it is.
     *
     * @return {@code true} when a pending cancel was withdrawn; the caller
     *         then cancels the run
     */
    public static boolean withdraw(String runId) {
        var entry = runId != null ? RUNS.get(runId) : null;
        if (entry == null || !entry.withdraw()) {
            return false;
        }
        RUNS.remove(runId, entry);
        return true;
    }

    /**
     * Whether the run is tracked: the connection it started on went away and
     * detached it, and the run has not ended since — whether its grace is
     * armed, or stood down while a reattached client listens. A reattached
     * client that stops listening decides for the run only then: while the
     * connection the run started on is live, that connection's departure does.
     */
    public static boolean isTracked(String runId) {
        return runId != null && RUNS.containsKey(runId);
    }

    /** Whether a cancel is pending for the run. */
    public static boolean isDetached(String runId) {
        var entry = runId != null ? RUNS.get(runId) : null;
        return entry != null && entry.isArmed();
    }

    /** Forget a run that ended: its pending cancel, if any, never fires. */
    public static void runEnded(String runId) {
        if (runId == null) {
            return;
        }
        var entry = RUNS.remove(runId);
        if (entry != null) {
            entry.end();
        }
    }

    private static boolean enabled(Duration grace) {
        return grace != null && !grace.isZero() && !grace.isNegative();
    }

    /** Number of runs tracked. Visible for testing. */
    static int size() {
        return RUNS.size();
    }

    /**
     * Hold the grace timer: {@link #release()} does not stop it until this
     * caller released it too. Each call is answered by one {@code release()}.
     */
    public static void acquire() {
        synchronized (HOLDERS_LOCK) {
            holders++;
            everHeld = true;
        }
    }

    /**
     * Let go of a hold {@link #acquire()} took: the last holder letting go
     * stops the timer and cancels every run still detached
     * ({@link #shutdown()}). A call with no hold left changes nothing.
     */
    public static void release() {
        synchronized (HOLDERS_LOCK) {
            if (holders == 0 || --holders > 0) {
                return;
            }
        }
        shutdown();
    }

    /** Whether the timer thread is scheduled. Visible for testing. */
    static boolean timerRunning() {
        return SCHEDULER.get() != null;
    }

    /** How many holds are taken. Visible for testing. */
    static int holders() {
        synchronized (HOLDERS_LOCK) {
            return holders;
        }
    }

    /**
     * Stop the timer thread and cancel every run still detached at once,
     * whoever holds the timer: process teardown. An endpoint's destroy
     * withdraws its own runs' graces and lets go of its hold with
     * {@link #release()} instead. Idempotent; the next detach starts the
     * timer again.
     */
    public static void shutdown() {
        var scheduler = SCHEDULER.getAndSet(null);
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        for (var entry : List.copyOf(RUNS.values())) {
            if (entry.isArmed()) {
                entry.fire(entry.generation(), false);
            }
        }
    }

    private static ScheduledExecutorService scheduler() {
        synchronized (HOLDERS_LOCK) {
            if (holders == 0 && everHeld) {
                // The last holder let go: nothing restarts the timer, and a run
                // detached now has no grace to wait out (arm cancels it at once).
                throw new RejectedExecutionException("the reattach grace timer is released");
            }
        }
        while (true) {
            var current = SCHEDULER.get();
            if (current != null) {
                return current;
            }
            var candidate = Executors.newSingleThreadScheduledExecutor(
                    r -> Thread.ofPlatform().daemon().name("ai-reattach-grace").unstarted(r));
            if (SCHEDULER.compareAndSet(null, candidate)) {
                return candidate;
            }
            // Lost the race: another thread installed the timer first.
            candidate.shutdownNow();
        }
    }

    /** The pending cancel of one run. All state is guarded by {@code this}. */
    private static final class Detached {

        private final String runId;
        private final Runnable expiry;
        // Read outside this object's lock: it reaches into the run's replay buffer.
        private final BooleanSupplier watched;
        private ScheduledFuture<?> timer;
        // Bumped on every arm and disarm, so a timer that fires after its arm was
        // rescinded (it had started before the cancel) finds a stale generation.
        private long generation;
        private boolean armed;
        private boolean done;

        Detached(String runId, Runnable expiry, BooleanSupplier watched) {
            this.runId = runId;
            this.expiry = expiry;
            this.watched = watched;
        }

        synchronized long generation() {
            return generation;
        }

        synchronized boolean isArmed() {
            return armed;
        }

        boolean arm(Duration grace) {
            var listened = watched.getAsBoolean();
            long armedGeneration;
            synchronized (this) {
                if (done) {
                    return false;
                }
                if (armed) {
                    return true;
                }
                if (listened) {
                    logger.debug("Run {} lost a connection while a reattached client listens to it;"
                            + " no grace runs", runId);
                    return true;
                }
                armed = true;
                armedGeneration = ++generation;
                try {
                    timer = scheduler().schedule(() -> fire(armedGeneration, false),
                            grace.toMillis(), TimeUnit.MILLISECONDS);
                    logger.debug("Run {} detached; cancelled in {} unless a client reattaches",
                            runId, grace);
                    return true;
                } catch (RejectedExecutionException e) {
                    // The timer was shut down meanwhile: there is no grace to give.
                    logger.debug("Reattach grace timer unavailable for run {}; cancelling it now", runId);
                }
            }
            fire(armedGeneration, false);
            return true;
        }

        synchronized boolean disarm() {
            if (done || !armed) {
                return false;
            }
            standDown();
            logger.debug("Run {} reattached within its grace; its cancel is rescinded", runId);
            return true;
        }

        boolean restart(Duration grace) {
            synchronized (this) {
                if (done || !armed) {
                    return false;
                }
                standDown();
            }
            logger.debug("Run {} polled for within its grace; the grace restarts", runId);
            return arm(grace);
        }

        /** Drop the pending cancel; a timer already running finds a stale generation. Holds {@code this}. */
        private void standDown() {
            armed = false;
            generation++;
            if (timer != null) {
                timer.cancel(false);
                timer = null;
            }
        }

        /**
         * Run the cancel once, if {@code expected} is still the current arm
         * (or {@code force}: a reattached client left on purpose).
         */
        boolean fire(long expected, boolean force) {
            // A client that reattached since the arm listens to the run: the
            // grace is over for it, and its leaving detaches the run again.
            var listened = !force && watched.getAsBoolean();
            synchronized (this) {
                if (done || expected != generation || !armed && !force) {
                    return false;
                }
                if (listened) {
                    standDown();
                    logger.debug("Run {} is listened to by a reattached client; its grace stands down", runId);
                    return false;
                }
                done = true;
                armed = false;
                if (timer != null) {
                    timer.cancel(false);
                    timer = null;
                }
            }
            RUNS.remove(runId, this);
            try {
                expiry.run();
            } catch (RuntimeException e) {
                logger.warn("Cancelling run {} after its reattach grace failed: {}",
                        runId, e.toString(), e);
            }
            return true;
        }

        /** Take back a pending cancel without running it. */
        synchronized boolean withdraw() {
            if (done || !armed) {
                return false;
            }
            done = true;
            armed = false;
            generation++;
            if (timer != null) {
                timer.cancel(false);
                timer = null;
            }
            return true;
        }

        synchronized void end() {
            done = true;
            armed = false;
            if (timer != null) {
                timer.cancel(false);
                timer = null;
            }
        }
    }
}
