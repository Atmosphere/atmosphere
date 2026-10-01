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
package org.atmosphere.ai.processor;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Lets a prompt POST whose tracking id is between two connections of the same
 * client (a long-polling client posting after one poll ended and before the
 * next arrived) wait, briefly, for that client's next connection instead of
 * being fanned out to every subscriber of the path.
 *
 * <p>Only a tracking id this endpoint itself suspended a connection for
 * recently is waited for: an id the client merely claims is refused without
 * holding anything. Waiters are bounded to one per tracking id and to
 * {@code org.atmosphere.ai.prompt.maxRepollWaiters} (64) across every endpoint
 * of the framework: each holds a request thread, so the bound is one semaphore
 * shared through {@link AtmosphereConfig#properties()}. The set of known
 * tracking ids is bounded to {@link #MAX_KNOWN_IDS}. A refused or timed-out
 * prompt was not dispatched, so the caller answers {@code 503} and the client
 * may send it again. {@link #shutdown()} wakes every waiter and refuses new
 * ones, so a stopping server is not held up by prompts waiting for a poll that
 * can no longer arrive.</p>
 */
final class PromptRepollGate {

    /** How long a prompt POST waits for its client's next connection, in ms; 0 disables the wait. */
    static final String WAIT_MS_PARAM = "org.atmosphere.ai.prompt.repollWaitMs";

    /** How many prompt POSTs may wait at once, across every client and every endpoint. */
    static final String MAX_WAITERS_PARAM = "org.atmosphere.ai.prompt.maxRepollWaiters";

    static final long DEFAULT_WAIT_MS = 2_000;
    /** Each waiting prompt holds a request thread, so the wait never exceeds this. */
    static final long MAX_WAIT_MS = 30_000;
    static final int DEFAULT_MAX_WAITERS = 64;

    /** Tracking ids remembered at once; past it an id is not recorded and its prompt is not waited for. */
    static final int MAX_KNOWN_IDS = 10_000;

    /** Used when the endpoint never times its connections out. */
    private static final long UNBOUNDED_SUSPEND_WINDOW_MS = 120_000;

    /** The {@link AtmosphereConfig#properties()} key of the waiter semaphore every endpoint shares. */
    static final String WAITER_SLOTS_PROPERTY = PromptRepollGate.class.getName() + ".waiterSlots";

    private static final long SWEEP_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private static final Logger logger = LoggerFactory.getLogger(PromptRepollGate.class);

    /** Why a prompt could not be dispatched. */
    enum Refusal {
        /** This endpoint never suspended a connection for the id, or not recently. */
        UNKNOWN,
        /** A prompt of the same id already waits, or the endpoint's waiters are all taken. */
        BUSY,
        /** No connection of the id arrived in time, or the wait is disabled or was interrupted. */
        TIMEOUT,
        /** The endpoint is shutting down. */
        SHUTDOWN
    }

    /** A connection to dispatch to, or the reason there is none. */
    record Outcome(AtmosphereResource target, Refusal refusal) {
        static Outcome of(AtmosphereResource target) {
            return new Outcome(target, null);
        }

        static Outcome refused(Refusal refusal) {
            return new Outcome(null, refusal);
        }
    }

    private record Limits(long waitNanos, Semaphore slots) {
    }

    /** Tracking id -> {@link System#nanoTime()} at which a connection of it was last made ready. */
    private final ConcurrentHashMap<String, Long> readyAt = new ConcurrentHashMap<>();
    /** Tracking id -> the signal its one waiting prompt sleeps on. */
    private final ConcurrentHashMap<String, Semaphore> waiters = new ConcurrentHashMap<>();
    private final long suspendWindowMs;
    private final int maxKnownIds;
    private volatile Limits limits;
    private volatile boolean closed;
    /** When the known ids were last swept for expired ones; a full set is swept at most once a second. */
    private volatile long lastSweepNanos = System.nanoTime() - SWEEP_INTERVAL_NANOS;

    PromptRepollGate(long suspendTimeoutMs) {
        this(suspendTimeoutMs, MAX_KNOWN_IDS);
    }

    PromptRepollGate(long suspendTimeoutMs, int maxKnownIds) {
        this.suspendWindowMs = suspendTimeoutMs > 0 ? suspendTimeoutMs : UNBOUNDED_SUSPEND_WINDOW_MS;
        this.maxKnownIds = maxKnownIds;
    }

    /**
     * A connection of {@code trackingId} is suspended on this endpoint and ready to
     * take a prompt: remember the id and wake the prompt waiting for it, if any.
     */
    void connectionReady(String trackingId) {
        if (closed || trackingId == null || trackingId.isEmpty()) {
            return;
        }
        var now = System.nanoTime();
        if (readyAt.size() >= maxKnownIds && !readyAt.containsKey(trackingId)
                && now - lastSweepNanos >= SWEEP_INTERVAL_NANOS) {
            lastSweepNanos = now;
            readyAt.entrySet().removeIf(e -> expired(e.getValue(), now));
        }
        if (readyAt.size() < maxKnownIds || readyAt.containsKey(trackingId)) {
            readyAt.put(trackingId, now);
        } else {
            logger.debug("{} tracking ids already known; not recording {}", maxKnownIds, trackingId);
        }
        var waiter = waiters.get(trackingId);
        if (waiter != null) {
            waiter.release();
        }
    }

    /**
     * Wait for a connection of {@code trackingId} made ready after {@code since}
     * (a {@link System#nanoTime()} taken before the caller last failed to resolve
     * it), and return the one {@code resolve} finds then.
     */
    Outcome await(String trackingId, long since, Supplier<AtmosphereResource> resolve,
                  AtmosphereConfig config) {
        if (closed) {
            return Outcome.refused(Refusal.SHUTDOWN);
        }
        var last = readyAt.get(trackingId);
        if (last == null || expired(last, System.nanoTime())) {
            return Outcome.refused(Refusal.UNKNOWN);
        }
        var l = limits(config);
        if (l.waitNanos() <= 0) {
            return Outcome.refused(Refusal.TIMEOUT);
        }
        if (!l.slots().tryAcquire()) {
            return Outcome.refused(Refusal.BUSY);
        }
        try {
            var signal = new Semaphore(0);
            if (waiters.putIfAbsent(trackingId, signal) != null) {
                return Outcome.refused(Refusal.BUSY);
            }
            try {
                var deadline = System.nanoTime() + l.waitNanos();
                while (true) {
                    // Re-read after registering: a shutdown() that ran before the
                    // putIfAbsent did not see this waiter to wake it.
                    if (closed) {
                        return Outcome.refused(Refusal.SHUTDOWN);
                    }
                    var ready = readyAt.get(trackingId);
                    if (ready != null && ready - since >= 0) {
                        var target = resolve.get();
                        if (target != null) {
                            return Outcome.of(target);
                        }
                    }
                    var left = deadline - System.nanoTime();
                    if (left <= 0) {
                        return Outcome.refused(Refusal.TIMEOUT);
                    }
                    signal.tryAcquire(left, TimeUnit.NANOSECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.debug("Prompt of {} interrupted while waiting for its next connection", trackingId, e);
                return Outcome.refused(Refusal.TIMEOUT);
            } finally {
                waiters.remove(trackingId, signal);
            }
        } finally {
            l.slots().release();
        }
    }

    /**
     * The endpoint is going away: wake every waiting prompt so it is refused at
     * once, and refuse any later one without waiting. Idempotent.
     */
    void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        waiters.values().forEach(Semaphore::release);
        readyAt.clear();
    }

    /** Tracking ids currently remembered; for tests. */
    int knownIds() {
        return readyAt.size();
    }

    /** Prompts currently waiting; for tests. */
    int waiting() {
        return waiters.size();
    }

    private boolean expired(long readyNanos, long now) {
        return now - readyNanos > TimeUnit.MILLISECONDS.toNanos(suspendWindowMs) + waitNanos();
    }

    private long waitNanos() {
        var l = limits;
        return l != null ? l.waitNanos() : TimeUnit.MILLISECONDS.toNanos(DEFAULT_WAIT_MS);
    }

    private Limits limits(AtmosphereConfig config) {
        var l = limits;
        if (l == null) {
            synchronized (this) {
                l = limits;
                if (l == null) {
                    var waitMs = Math.min(MAX_WAIT_MS,
                            Math.max(0, intParam(config, WAIT_MS_PARAM, (int) DEFAULT_WAIT_MS)));
                    var maxWaiters = Math.max(1, intParam(config, MAX_WAITERS_PARAM, DEFAULT_MAX_WAITERS));
                    l = new Limits(TimeUnit.MILLISECONDS.toNanos(waitMs), sharedSlots(config, maxWaiters));
                    limits = l;
                }
            }
        }
        return l;
    }

    /**
     * The one waiter semaphore of the framework, created by the first endpoint that
     * needs it. Without a config (unit tests) the gate bounds only its own waiters.
     */
    private static Semaphore sharedSlots(AtmosphereConfig config, int maxWaiters) {
        var properties = config != null ? config.properties() : null;
        if (properties == null) {
            return new Semaphore(maxWaiters);
        }
        return properties.computeIfAbsent(WAITER_SLOTS_PROPERTY,
                k -> new Semaphore(maxWaiters)) instanceof Semaphore shared ? shared : new Semaphore(maxWaiters);
    }

    private static int intParam(AtmosphereConfig config, String name, int defaultValue) {
        var raw = config != null ? config.getInitParameter(name) : null;
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            logger.warn("Ignoring {}={}: not an integer; using {}", name, raw, defaultValue);
            return defaultValue;
        }
    }
}
