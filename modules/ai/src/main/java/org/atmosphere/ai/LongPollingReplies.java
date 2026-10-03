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

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Delivery of a streaming AI reply to a long-polling client.
 *
 * <p>A long-polling poll is answered by the first frame written to it, and the
 * frames written after it target a poll that has already returned. So a
 * {@link DefaultStreamingSession} whose client is on long-polling does not
 * stream: it keeps its frames in a bounded {@link Buffer} and hands them over
 * complete, at its terminal frame, and at a frame the client must answer before
 * the reply can go on ({@code approval-required}). Handing over parks the
 * frames under the client's tracking id and resumes the poll waiting for it, if
 * any, empty. The client's next poll takes the parked frames
 * ({@link #deliver}) and writes them, every frame length-delimited, in one
 * response from its own request thread. WebSocket, SSE and every other
 * transport stream as before.</p>
 *
 * <p>Parked frames leave only through a write that completed on the poll that
 * took them, under the lock of their entry. A hand-over that resumes a poll
 * holds the same lock, so it never resumes a poll in the middle of that write,
 * and a poll that the hand-over resumed first leaves the frames for the next
 * one. A write that fails leaves them parked.</p>
 *
 * <p>Bounds (Correctness Invariant #3), read from the init parameters of the
 * framework the session's connection belongs to:</p>
 * <ul>
 *   <li>{@value #MAX_REPLY_BYTES_PARAM}: UTF-8 bytes of one reply (8 MiB);</li>
 *   <li>{@value #MAX_REPLY_FRAMES_PARAM}: frames of one reply (10,000);</li>
 *   <li>{@value #MAX_BUFFERED_BYTES_PARAM}: bytes of every reply buffered or
 *       parked in the JVM (256 MiB);</li>
 *   <li>{@value #MAX_PARKED_REPLIES_PARAM}: tracking ids with a parked reply
 *       (1,024);</li>
 *   <li>{@value #PARKED_TTL_MS_PARAM}: how long a parked reply waits for a poll
 *       (120 s).</li>
 * </ul>
 * <p>A reply that outgrows a bound ends with an {@code error} frame in place of
 * its frames; a reply that cannot be parked is dropped and logged.</p>
 */
public final class LongPollingReplies {

    /** Maximum UTF-8 bytes of one long-polling reply. */
    public static final String MAX_REPLY_BYTES_PARAM = "org.atmosphere.ai.longPolling.maxReplyBytes";
    /** Maximum frames of one long-polling reply. */
    public static final String MAX_REPLY_FRAMES_PARAM = "org.atmosphere.ai.longPolling.maxReplyFrames";
    /** Maximum bytes of all long-polling replies buffered or parked at once. */
    public static final String MAX_BUFFERED_BYTES_PARAM = "org.atmosphere.ai.longPolling.maxBufferedBytes";
    /** Maximum tracking ids with a parked reply at once. */
    public static final String MAX_PARKED_REPLIES_PARAM = "org.atmosphere.ai.longPolling.maxParkedReplies";
    /** How long a parked reply waits for its client's next poll, in ms. */
    public static final String PARKED_TTL_MS_PARAM = "org.atmosphere.ai.longPolling.parkedReplyTtlMs";

    static final long DEFAULT_MAX_REPLY_BYTES = 8L * 1024 * 1024;
    static final int DEFAULT_MAX_REPLY_FRAMES = 10_000;
    static final long DEFAULT_MAX_BUFFERED_BYTES = 256L * 1024 * 1024;
    static final int DEFAULT_MAX_PARKED_REPLIES = 1_024;
    static final long DEFAULT_PARKED_TTL_MS = 120_000;

    /**
     * Request attribute holding the identity a connection was suspended under;
     * a parked reply is handed only to a poll of the same identity.
     */
    public static final String CONNECTION_OWNER_ATTRIBUTE = "org.atmosphere.ai.connectionOwner";

    private static final Logger logger = LoggerFactory.getLogger(LongPollingReplies.class);

    /** Tracking id -> its parked reply. */
    private static final ConcurrentHashMap<String, Parked> PARKED = new ConcurrentHashMap<>();
    /** Tracking id -> buffers of its sessions still running; see {@link #inFlight}. */
    private static final ConcurrentHashMap<String, AtomicInteger> IN_FLIGHT = new ConcurrentHashMap<>();
    /** Bytes held by every buffer and parked reply. */
    private static final AtomicLong RESERVED = new AtomicLong();

    private LongPollingReplies() {
    }

    /** Writes parked frames to the poll that took them, then completes that poll. */
    @FunctionalInterface
    public interface FrameWriter {
        void write(AtmosphereResource poll, List<String> frames) throws IOException;
    }

    /** The bounds a buffer is created with. */
    record Limits(long maxReplyBytes, int maxReplyFrames, long maxBufferedBytes,
                  int maxParkedReplies, long parkedTtlNanos) {

        static final Limits DEFAULTS = new Limits(DEFAULT_MAX_REPLY_BYTES, DEFAULT_MAX_REPLY_FRAMES,
                DEFAULT_MAX_BUFFERED_BYTES, DEFAULT_MAX_PARKED_REPLIES,
                TimeUnit.MILLISECONDS.toNanos(DEFAULT_PARKED_TTL_MS));

        static Limits of(AtmosphereConfig config) {
            if (config == null) {
                return DEFAULTS;
            }
            return new Limits(
                    positive(config, MAX_REPLY_BYTES_PARAM, DEFAULT_MAX_REPLY_BYTES),
                    (int) Math.min(Integer.MAX_VALUE,
                            positive(config, MAX_REPLY_FRAMES_PARAM, DEFAULT_MAX_REPLY_FRAMES)),
                    positive(config, MAX_BUFFERED_BYTES_PARAM, DEFAULT_MAX_BUFFERED_BYTES),
                    (int) Math.min(Integer.MAX_VALUE,
                            positive(config, MAX_PARKED_REPLIES_PARAM, DEFAULT_MAX_PARKED_REPLIES)),
                    TimeUnit.MILLISECONDS.toNanos(positive(config, PARKED_TTL_MS_PARAM, DEFAULT_PARKED_TTL_MS)));
        }

        private static long positive(AtmosphereConfig config, String name, long defaultValue) {
            String raw;
            try {
                raw = config.getInitParameter(name);
            } catch (RuntimeException e) {
                logger.trace("Unable to read {}", name, e);
                return defaultValue;
            }
            if (raw == null || raw.isBlank()) {
                return defaultValue;
            }
            try {
                var value = Long.parseLong(raw.trim());
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException e) {
                logger.trace("{} is not a number", name, e);
            }
            logger.warn("Ignoring {}={}: not a positive integer; using {}", name, raw, defaultValue);
            return defaultValue;
        }
    }

    /** Frames handed over at once, with the bytes they hold reserved. */
    record Batch(List<String> frames, long bytes) {
    }

    /**
     * The frames of one session's reply not handed over yet. Each frame's bytes
     * are reserved against {@value #MAX_BUFFERED_BYTES_PARAM} until the frame is
     * written, discarded or expires. {@link #close} is idempotent and is called
     * on every terminal path of the session.
     */
    static final class Buffer {
        private final String trackingId;
        private final Limits limits;
        private final ArrayList<String> frames = new ArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private long bytes;

        Buffer(String trackingId, Limits limits) {
            this.trackingId = trackingId;
            this.limits = limits;
            IN_FLIGHT.computeIfAbsent(trackingId, k -> new AtomicInteger()).incrementAndGet();
        }

        Limits limits() {
            return limits;
        }

        String trackingId() {
            return trackingId;
        }

        /**
         * Adds {@code frame}, or refuses it, with nothing added, when it would
         * take the reply or the JVM past a bound, or the buffer was closed.
         */
        synchronized boolean add(String frame) {
            if (closed.get()) {
                return false;
            }
            var size = frame.getBytes(StandardCharsets.UTF_8).length;
            if (frames.size() >= limits.maxReplyFrames() || bytes + size > limits.maxReplyBytes()
                    || !reserve(size, limits.maxBufferedBytes())) {
                return false;
            }
            frames.add(frame);
            bytes += size;
            return true;
        }

        /** Takes the frames added so far; their bytes stay reserved by the batch. */
        synchronized Batch drain() {
            var batch = new Batch(List.copyOf(frames), bytes);
            frames.clear();
            bytes = 0;
            return batch;
        }

        /** Drops the frames added so far and releases their bytes. */
        synchronized void discardFrames() {
            frames.clear();
            RESERVED.addAndGet(-bytes);
            bytes = 0;
        }

        boolean isClosed() {
            return closed.get();
        }

        /** Drops the frames and stops counting the session as in flight. Idempotent. */
        void close() {
            var first = closed.compareAndSet(false, true);
            discardFrames();
            if (first) {
                IN_FLIGHT.computeIfPresent(trackingId, (k, count) -> count.decrementAndGet() <= 0 ? null : count);
            }
        }
    }

    /** A reply waiting for its client's next poll. */
    private static final class Parked {
        private final ReentrantLock lock = new ReentrantLock();
        private final Object owner;
        private final long ttlNanos;
        private final ArrayList<String> frames = new ArrayList<>();
        private long bytes;
        private volatile long touchedNanos = System.nanoTime();
        /** Set, under the lock, once the entry left the map; a hand-over then parks in a new one. */
        private boolean dead;

        private Parked(Object owner, long ttlNanos) {
            this.owner = owner;
            this.ttlNanos = ttlNanos;
        }
    }

    private static boolean reserve(long size, long max) {
        while (true) {
            var current = RESERVED.get();
            if (current + size > max) {
                return false;
            }
            if (RESERVED.compareAndSet(current, current + size)) {
                return true;
            }
        }
    }

    /**
     * Parks {@code batch} for the client {@code trackingId}, after any frames
     * already parked for it, and resumes the client's waiting poll, empty, so it
     * comes back for them. The batch's bytes move to the parked reply, or are
     * released when it cannot be parked.
     *
     * @param origin a connection of the framework, to find the client's poll with
     * @param owner  the identity of the connection the reply was asked on
     * @return {@code false}, with the frames dropped, when no reply can be parked
     */
    static boolean park(AtmosphereResource origin, String trackingId, Object owner, Batch batch, Limits limits) {
        if (batch.frames().isEmpty()) {
            RESERVED.addAndGet(-batch.bytes());
            return true;
        }
        while (true) {
            var entry = PARKED.get(trackingId);
            if (entry == null) {
                if (PARKED.size() >= limits.maxParkedReplies()) {
                    sweepExpired();
                }
                if (PARKED.size() >= limits.maxParkedReplies()) {
                    RESERVED.addAndGet(-batch.bytes());
                    logger.warn("{} long-polling replies are already waiting for a poll; dropping the reply of {}",
                            limits.maxParkedReplies(), trackingId);
                    return false;
                }
                var fresh = new Parked(owner, limits.parkedTtlNanos());
                entry = PARKED.putIfAbsent(trackingId, fresh);
                if (entry == null) {
                    entry = fresh;
                }
            }
            entry.lock.lock();
            try {
                if (entry.dead) {
                    continue;
                }
                if (!Objects.equals(entry.owner, owner)) {
                    RESERVED.addAndGet(-batch.bytes());
                    logger.warn("A reply of another identity is already parked for {}; dropping this one", trackingId);
                    return false;
                }
                entry.frames.addAll(batch.frames());
                entry.bytes += batch.bytes();
                entry.touchedNanos = System.nanoTime();
                logger.debug("Parked {} frame(s) for the next poll of {}", entry.frames.size(), trackingId);
                wake(origin, trackingId);
                return true;
            } finally {
                entry.lock.unlock();
            }
        }
    }

    /** Resumes the client's suspended poll, empty, so its next poll takes the parked frames. */
    private static void wake(AtmosphereResource origin, String trackingId) {
        try {
            var config = origin.getAtmosphereConfig();
            var factory = config != null ? config.resourcesFactory() : null;
            if (factory == null) {
                return;
            }
            factory.findResource(trackingId).ifPresent(poll -> {
                if (poll.transport() == AtmosphereResource.TRANSPORT.LONG_POLLING && poll.isSuspended()) {
                    logger.debug("Resuming the waiting poll of {} to fetch its reply", trackingId);
                    poll.resume();
                }
            });
        } catch (RuntimeException e) {
            // The reply stays parked: the client's next poll takes it.
            logger.debug("Unable to resume the poll of {}", trackingId, e);
        }
    }

    /**
     * Writes the reply parked for {@code poll}'s tracking id to {@code poll}, a
     * long-polling poll of that client not suspended yet, when
     * {@code ownerMatches} accepts the identity the reply was asked under. The
     * frames leave the parked reply only once {@code writer} returned. A poll
     * that is not suspended is never resumed by a hand-over, so nothing ends it
     * in the middle of the write.
     *
     * @return whether a reply was written; the poll is then answered with it
     * instead of being suspended
     */
    public static boolean deliver(AtmosphereResource poll, Predicate<Object> ownerMatches, FrameWriter writer) {
        var trackingId = poll.uuid();
        var entry = trackingId != null ? PARKED.get(trackingId) : null;
        if (entry == null) {
            return false;
        }
        entry.lock.lock();
        try {
            if (entry.dead || entry.frames.isEmpty() || poll.isResumed() || poll.isCancelled()) {
                // A poll the hand-over already resumed: the next one takes the frames.
                logger.debug("Poll of {} not handed its parked reply (dead={}, frames={}, resumed={})",
                        trackingId, entry.dead, entry.frames.size(), poll.isResumed());
                return false;
            }
            if (!ownerMatches.test(entry.owner)) {
                // Not logged with the identities: one of them is another user's.
                logger.warn("A poll of {} carries another identity than its parked reply; not delivered", trackingId);
                return false;
            }
            // The write runs under the entry's lock: a hand-over to this client
            // (and the sweep of this one entry) waits for it, so no resume ends
            // the poll in the middle of it. Only this client's own reply waits.
            try {
                writer.write(poll, List.copyOf(entry.frames));
            } catch (IOException | RuntimeException e) {
                logger.debug("Writing the parked reply of {} failed; it stays parked", trackingId, e);
                return false;
            }
            logger.debug("Delivered {} parked frame(s) to the poll of {}", entry.frames.size(), trackingId);
            RESERVED.addAndGet(-entry.bytes);
            entry.frames.clear();
            entry.bytes = 0;
            entry.dead = true;
            PARKED.remove(trackingId, entry);
            return true;
        } finally {
            entry.lock.unlock();
        }
    }

    /**
     * Whether a reply waits for the next poll of {@code trackingId} that
     * {@code ownerMatches} accepts the identity of.
     */
    public static boolean hasParked(String trackingId, Predicate<Object> ownerMatches) {
        var entry = trackingId != null ? PARKED.get(trackingId) : null;
        if (entry == null) {
            return false;
        }
        entry.lock.lock();
        try {
            return !entry.dead && !entry.frames.isEmpty() && ownerMatches.test(entry.owner);
        } finally {
            entry.lock.unlock();
        }
    }

    /** Drops the reply parked for a client that went away. */
    public static void discard(String trackingId) {
        if (trackingId == null) {
            return;
        }
        var entry = PARKED.get(trackingId);
        if (entry != null) {
            drop(trackingId, entry);
        }
    }

    /**
     * Whether a long-polling session of {@code trackingId} is still running: the
     * client's polls are then the same connection's, not a reconnection to
     * replay the run to.
     */
    public static boolean inFlight(String trackingId) {
        return trackingId != null && IN_FLIGHT.containsKey(trackingId);
    }

    /**
     * Drops the parked replies no poll took within their time to live; run by
     * {@link StreamingSessionSweeper}, and by a hand-over that finds no room.
     */
    static int sweepExpired() {
        var now = System.nanoTime();
        var dropped = 0;
        for (var e : PARKED.entrySet()) {
            if (now - e.getValue().touchedNanos > e.getValue().ttlNanos && drop(e.getKey(), e.getValue())) {
                dropped++;
                logger.debug("No poll took the long-polling reply of {}; dropped", e.getKey());
            }
        }
        return dropped;
    }

    private static boolean drop(String trackingId, Parked entry) {
        entry.lock.lock();
        try {
            if (entry.dead) {
                return false;
            }
            RESERVED.addAndGet(-entry.bytes);
            entry.frames.clear();
            entry.bytes = 0;
            entry.dead = true;
            PARKED.remove(trackingId, entry);
            return true;
        } finally {
            entry.lock.unlock();
        }
    }

    /** Bytes reserved by every buffer and parked reply; for tests. */
    static long reservedBytes() {
        return RESERVED.get();
    }

    /** Tracking ids with a parked reply; for tests. */
    static int parkedReplies() {
        return PARKED.size();
    }
}
