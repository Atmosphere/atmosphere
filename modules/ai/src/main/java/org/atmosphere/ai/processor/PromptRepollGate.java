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
 * <p>Only a tracking id this endpoint recently suspended a connection for, or
 * assigned in a protocol handshake, is waited for; an id it never saw is
 * refused without holding anything. That is not proof of who posts: the
 * tracking id is a client-presented bearer token. A client may choose its own
 * id, the framework adopts any well-formed one for a new connection, and the
 * prompt goes to whichever connection holds the id when it is resolved, as a
 * prompt resolved without waiting always has. Waiters are bounded to one per
 * tracking id and to {@code org.atmosphere.ai.prompt.maxRepollWaiters} (64)
 * across every endpoint of the framework: each holds a request thread, so the
 * bound is one semaphore shared through {@link AtmosphereConfig#properties()}. A prompt whose id
 * only a handshake vouches for (no connection of it was made ready yet) may take at most half of
 * those slots ({@link #handshakeShare}): a handshake costs one unauthenticated request, so
 * handshake-and-post pairs alone must not leave a long-polling client between two polls without
 * a slot. The set of known
 * tracking ids is bounded to {@link #MAX_KNOWN_IDS}. An id assigned in a handshake
 * that no connection of it has followed yet is kept apart, in a set bounded to
 * {@link #MAX_ASSIGNED_IDS}, and only for the wait: the handshake is one
 * unauthenticated request that suspends nothing, so it must neither keep an id
 * known for the whole suspend window nor fill the set connections are recorded
 * in. A connection of the id moves it to the known set. An id whose connection was closed or
 * cancelled ({@link #connectionGone}) is forgotten, so a prompt naming it is refused at once
 * rather than waited for. A refused or timed-out
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

    /** Handshake-assigned ids no connection followed yet, remembered at once; see {@link #idAssigned}. */
    static final int MAX_ASSIGNED_IDS = 1_024;

    /** Used when the endpoint never times its connections out. */
    private static final long UNBOUNDED_SUSPEND_WINDOW_MS = 120_000;

    /** The {@link AtmosphereConfig#properties()} key of the waiter semaphore every endpoint shares. */
    static final String WAITER_SLOTS_PROPERTY = PromptRepollGate.class.getName() + ".waiterSlots";

    /** The {@link AtmosphereConfig#properties()} key of the share of those slots a handshake-only id may take. */
    static final String HANDSHAKE_SLOTS_PROPERTY = PromptRepollGate.class.getName() + ".handshakeWaiterSlots";

    private static final long SWEEP_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private static final Logger logger = LoggerFactory.getLogger(PromptRepollGate.class);

    /** Why a prompt could not be dispatched. */
    enum Refusal {
        /** This endpoint never suspended a connection for the id nor assigned it, or not recently. */
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

    private record Limits(long waitNanos, Semaphore slots, Semaphore handshakeSlots) {
    }

    /** Tracking id -> {@link System#nanoTime()} at which a connection of it was last made ready. */
    private final ConcurrentHashMap<String, Long> readyAt = new ConcurrentHashMap<>();
    /**
     * Tracking id -> {@link System#nanoTime()} at which a handshake assigned it,
     * until a connection of it is made ready; each is waited for only within the wait.
     */
    private final ConcurrentHashMap<String, Long> assignedAt = new ConcurrentHashMap<>();
    /** Tracking id -> the signal its one waiting prompt sleeps on. */
    private final ConcurrentHashMap<String, Semaphore> waiters = new ConcurrentHashMap<>();
    private final long suspendWindowMs;
    private final int maxKnownIds;
    private final int maxAssignedIds;
    private volatile Limits limits;
    private volatile boolean closed;
    /** When the known ids were last swept for expired ones; a full set is swept at most once a second. */
    private volatile long lastSweepNanos = System.nanoTime() - SWEEP_INTERVAL_NANOS;
    /** When the handshake-assigned ids were last swept; a full set is swept at most once a second. */
    private volatile long lastAssignedSweepNanos = System.nanoTime() - SWEEP_INTERVAL_NANOS;

    PromptRepollGate(long suspendTimeoutMs) {
        this(suspendTimeoutMs, MAX_KNOWN_IDS);
    }

    PromptRepollGate(long suspendTimeoutMs, int maxKnownIds) {
        this(suspendTimeoutMs, maxKnownIds, MAX_ASSIGNED_IDS);
    }

    PromptRepollGate(long suspendTimeoutMs, int maxKnownIds, int maxAssignedIds) {
        this.suspendWindowMs = suspendTimeoutMs > 0 ? suspendTimeoutMs : UNBOUNDED_SUSPEND_WINDOW_MS;
        this.maxKnownIds = maxKnownIds;
        this.maxAssignedIds = maxAssignedIds;
    }

    /**
     * A connection of {@code trackingId} is suspended on this endpoint and ready to
     * take a prompt: remember the id and wake the prompt waiting for it, if any.
     */
    void connectionReady(String trackingId) {
        if (remember(trackingId)) {
            assignedAt.remove(trackingId);
            var waiter = waiters.get(trackingId);
            if (waiter != null) {
                waiter.release();
            }
        }
    }

    /**
     * A connection of {@code trackingId} was closed by its client or cancelled:
     * forget the id, so a prompt naming it later is refused at once instead of
     * holding a waiter slot for a poll that will not come. A client that comes
     * back under the same id is known again once that connection is ready. A
     * prompt already waiting keeps waiting, within its wait.
     */
    void connectionGone(String trackingId) {
        if (trackingId == null) {
            return;
        }
        readyAt.remove(trackingId);
        assignedAt.remove(trackingId);
    }

    /**
     * The server assigned {@code trackingId} in a protocol handshake that suspends
     * no connection (long-polling): remember the id for the wait only, so a prompt
     * posted right after the handshake, before the client's first poll, waits for
     * that poll instead of being refused. The id goes to its own bounded set, so
     * handshakes alone never crowd connection ids out of the known set.
     */
    void idAssigned(String trackingId) {
        if (closed || trackingId == null || trackingId.isEmpty()) {
            return;
        }
        var now = System.nanoTime();
        if (assignedAt.size() >= maxAssignedIds && !assignedAt.containsKey(trackingId)
                && now - lastAssignedSweepNanos >= SWEEP_INTERVAL_NANOS) {
            lastAssignedSweepNanos = now;
            assignedAt.entrySet().removeIf(e -> assignedExpired(e.getValue(), now));
        }
        if (assignedAt.size() < maxAssignedIds || assignedAt.containsKey(trackingId)) {
            assignedAt.put(trackingId, now);
        } else {
            logger.debug("{} handshake ids already pending; not recording {}", maxAssignedIds, trackingId);
        }
    }

    private boolean remember(String trackingId) {
        if (closed || trackingId == null || trackingId.isEmpty()) {
            return false;
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
        return true;
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
        var l = limits(config);
        var now = System.nanoTime();
        var connectionKnown = connectionKnown(trackingId, now);
        if (!connectionKnown && !handshakeKnown(trackingId, now)) {
            return Outcome.refused(Refusal.UNKNOWN);
        }
        if (l.waitNanos() <= 0) {
            return Outcome.refused(Refusal.TIMEOUT);
        }
        var handshakeSlot = connectionKnown ? null : l.handshakeSlots();
        if (handshakeSlot != null && !handshakeSlot.tryAcquire()) {
            return Outcome.refused(Refusal.BUSY);
        }
        if (!l.slots().tryAcquire()) {
            if (handshakeSlot != null) {
                handshakeSlot.release();
            }
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
            if (handshakeSlot != null) {
                handshakeSlot.release();
            }
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
        assignedAt.clear();
    }

    /** Tracking ids currently remembered; for tests. */
    int knownIds() {
        return readyAt.size();
    }

    /** Handshake-assigned ids no connection followed yet; for tests. */
    int assignedIds() {
        return assignedAt.size();
    }

    /** Prompts currently waiting; for tests. */
    int waiting() {
        return waiters.size();
    }

    /** The wait a prompt is given, in ms, once the configured value is clamped; for tests. */
    long waitMs(AtmosphereConfig config) {
        return TimeUnit.NANOSECONDS.toMillis(limits(config).waitNanos());
    }

    /** A connection of the id was made ready within the window. */
    private boolean connectionKnown(String trackingId, long now) {
        var ready = readyAt.get(trackingId);
        return ready != null && !expired(ready, now);
    }

    /** A handshake assigned the id within the wait. */
    private boolean handshakeKnown(String trackingId, long now) {
        var assigned = assignedAt.get(trackingId);
        return assigned != null && !assignedExpired(assigned, now);
    }

    private boolean assignedExpired(long assignedNanos, long now) {
        return now - assignedNanos > waitNanos();
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
                    l = new Limits(TimeUnit.MILLISECONDS.toNanos(waitMs),
                            sharedSlots(config, WAITER_SLOTS_PROPERTY, maxWaiters),
                            sharedSlots(config, HANDSHAKE_SLOTS_PROPERTY, handshakeShare(maxWaiters)));
                    limits = l;
                }
            }
        }
        return l;
    }

    /**
     * How many of {@code maxWaiters} slots prompts of handshake-only ids may hold at
     * once: half, rounded down, and at least one.
     */
    static int handshakeShare(int maxWaiters) {
        return Math.max(1, maxWaiters / 2);
    }

    /**
     * A waiter semaphore of the framework, created by the first endpoint that needs
     * it. Without a config (unit tests) the gate bounds only its own waiters.
     */
    private static Semaphore sharedSlots(AtmosphereConfig config, String key, int permits) {
        var properties = config != null ? config.properties() : null;
        if (properties == null) {
            return new Semaphore(permits);
        }
        return properties.computeIfAbsent(key,
                k -> new Semaphore(permits)) instanceof Semaphore shared ? shared : new Semaphore(permits);
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
