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
 * cancelled at once.</p>
 *
 * <p>Lifecycle (Correctness Invariant #1): the timer thread is a daemon owned by
 * this class, started on the first detach and stopped by {@link #shutdown()},
 * which the endpoint handler calls on destroy; a run still detached then is
 * cancelled at once rather than left waiting for a timer that is gone
 * (Invariant #2). Entries exist only for runs still in flight, and each one is
 * removed when its run ends (Invariant #3).</p>
 */
public final class RunDisconnectGrace {

    private static final Logger logger = LoggerFactory.getLogger(RunDisconnectGrace.class);

    /** Status code of a WebSocket closed by a browser leaving or reloading the page. */
    private static final int GOING_AWAY = 1001;

    private static final ConcurrentHashMap<String, Detached> RUNS = new ConcurrentHashMap<>();
    private static final AtomicReference<ScheduledExecutorService> SCHEDULER = new AtomicReference<>();

    private RunDisconnectGrace() {
    }

    /**
     * Whether {@code event} reports a connection the client may come back on,
     * rather than a client that left on purpose. A WebSocket closed with 1000
     * (atmosphere.js closes with it on an explicit disconnect), an HTTP
     * transport's close message, and a close the application made are a leave;
     * a cancelled connection (a lost network, a 1006, an idle eviction, a
     * container closing it) and a WebSocket closed with 1001 (a browser leaving
     * or reloading the page) are a transport loss.
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
                    && impl.webSocketCloseCode() == GOING_AWAY;
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
     * subscribed (a long-polling one, answered with the backlog in one reply)
     * came back for it. Nothing detaches such a run again if the client never
     * returns, so its pending cancel is pushed back rather than rescinded. A
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
     * Stop the timer thread and cancel every run still detached at once.
     * Idempotent; the next detach starts the timer again.
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
