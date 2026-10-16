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

import org.atmosphere.ai.resume.RunEventReplayBuffer;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
 * complete, at its terminal frame, at a frame the client must answer before
 * the reply can go on ({@code approval-required}), and at the run id
 * ({@code X-Atmosphere-Run-Id} metadata), which a client that loses its
 * connection mid-reply needs to reattach to the run. Handing over parks the
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
 * <p>The frames of a run ({@link org.atmosphere.ai.resume.RunEventCapturingSession})
 * a poll wrote count as handed to the poll's tracking id in that run's replay
 * ({@link RunEventReplayBuffer#handed}) when they follow on, without a gap,
 * from what the client was handed of the run before: a later poll of the client
 * carrying the run id is not handed them again. Frames a poll did not write (a
 * failed write, a reply dropped or expired) do not count, and neither do the
 * frames written after them: a later poll carrying the run id is handed the
 * frames the client missed, and those after them again. A run remembers what
 * it wrote that way to at most
 * {@value RunEventReplayBuffer#MAX_REPLAY_POSITIONS_PARAM} clients (a run
 * broadcast to a room: as many as the room takes members), the least recently
 * served forgotten first, apart from the clients a poll carrying the run id was
 * handed events to, which these never make it forget; a forgotten client is
 * handed the run's retained events again on its next poll carrying the run id,
 * and then takes a place among those clients. A client gone for good (closed on
 * purpose, or no poll of it for the parked-reply time to live) frees its place
 * ({@link #discard(String, boolean)}).</p>
 *
 * <p>Bounds (Correctness Invariant #3), read from the init parameters of the
 * framework the session's connection belongs to:</p>
 * <ul>
 *   <li>{@value #MAX_REPLY_BYTES_PARAM}: UTF-8 bytes of one reply (8 MiB);</li>
 *   <li>{@value #MAX_REPLY_FRAMES_PARAM}: frames of one reply (10,000);</li>
 *   <li>{@value #MAX_BUFFERED_BYTES_PARAM}: bytes of every reply buffered or
 *       parked in the JVM, and of the room frames kept for long-polling room
 *       members, each counted once (256 MiB);</li>
 *   <li>{@value #MAX_PARKED_REPLIES_PARAM}: tracking ids with a parked reply
 *       (1,024), and the long-polling members of one room; the room frames kept
 *       for a member do not count against the parked replies;</li>
 *   <li>{@value #PARKED_TTL_MS_PARAM}: how long a parked reply waits for a poll
 *       (120 s);</li>
 *   <li>{@value #MAX_ROOM_REPLIES_PER_MEMBER_PARAM}: room replies a long-polling
 *       member of a room follows at once (64);</li>
 *   <li>{@value #ROOM_MEMBER_FRAME_BYTES_PARAM}: bytes a room frame kept for one
 *       more member counts against {@value #MAX_BUFFERED_BYTES_PARAM}, on top of
 *       its own bytes, counted once (16).</li>
 * </ul>
 * <p>A value that is not a positive integer is ignored, with a warning, for the
 * default.</p>
 * <p>A reply that outgrows a bound ends with an {@code error} frame in place of
 * its frames; a reply that cannot be parked is dropped and logged. A room
 * reply's frames are kept for each long-polling member of the room apart from
 * the parked replies, and a poll takes both; see
 * {@link LongPollingRoomReplies}.</p>
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
    /** Maximum room replies a long-polling member of a room follows at once; see {@link LongPollingRoomReplies}. */
    public static final String MAX_ROOM_REPLIES_PER_MEMBER_PARAM =
            "org.atmosphere.ai.longPolling.maxRoomRepliesPerMember";
    /**
     * Bytes a room frame kept for one more long-polling member counts, on top of
     * the frame's own bytes, counted once; see {@link LongPollingRoomReplies}.
     */
    public static final String ROOM_MEMBER_FRAME_BYTES_PARAM = "org.atmosphere.ai.longPolling.roomMemberFrameBytes";

    static final long DEFAULT_MAX_REPLY_BYTES = 8L * 1024 * 1024;
    static final int DEFAULT_MAX_REPLY_FRAMES = 10_000;
    static final long DEFAULT_MAX_BUFFERED_BYTES = 256L * 1024 * 1024;
    static final int DEFAULT_MAX_PARKED_REPLIES = 1_024;
    static final long DEFAULT_PARKED_TTL_MS = 120_000;
    static final int DEFAULT_MAX_ROOM_REPLIES_PER_MEMBER = 64;
    static final int DEFAULT_ROOM_MEMBER_FRAME_BYTES = 16;

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

    /** The bounds a buffer, or a room member, is created with. */
    record Limits(long maxReplyBytes, int maxReplyFrames, long maxBufferedBytes,
                  int maxParkedReplies, long parkedTtlNanos, int maxRoomRepliesPerMember,
                  int roomMemberFrameBytes) {

        static final Limits DEFAULTS = new Limits(DEFAULT_MAX_REPLY_BYTES, DEFAULT_MAX_REPLY_FRAMES,
                DEFAULT_MAX_BUFFERED_BYTES, DEFAULT_MAX_PARKED_REPLIES,
                TimeUnit.MILLISECONDS.toNanos(DEFAULT_PARKED_TTL_MS), DEFAULT_MAX_ROOM_REPLIES_PER_MEMBER,
                DEFAULT_ROOM_MEMBER_FRAME_BYTES);

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
                    TimeUnit.MILLISECONDS.toNanos(positive(config, PARKED_TTL_MS_PARAM, DEFAULT_PARKED_TTL_MS)),
                    (int) Math.min(Integer.MAX_VALUE, positive(config, MAX_ROOM_REPLIES_PER_MEMBER_PARAM,
                            DEFAULT_MAX_ROOM_REPLIES_PER_MEMBER)),
                    (int) Math.min(Integer.MAX_VALUE, positive(config, ROOM_MEMBER_FRAME_BYTES_PARAM,
                            DEFAULT_ROOM_MEMBER_FRAME_BYTES)));
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

    /**
     * Frames handed over at once, with the bytes they hold reserved, and, per
     * run, a span of the run events the frames stand for, every one of them.
     */
    record Batch(List<String> frames, long bytes, List<RunEventReplayBuffer.Span> spans) {

        Batch {
            spans = List.copyOf(spans);
        }

        Batch(List<String> frames, long bytes) {
            this(frames, bytes, List.of());
        }
    }

    /**
     * Records that the frames of the events {@code first} to {@code last} of
     * {@code run} are among those {@code spans} holds, one span per run, each
     * made only of events whose frames are there: a span next to or over the
     * run's span joins it; across a gap the span starting first stays, the one
     * that may follow on from what the client was handed before.
     */
    static void advance(Map<RunEventReplayBuffer, long[]> spans, RunEventReplayBuffer run, long first, long last) {
        var span = spans.get(run);
        if (span == null) {
            spans.put(run, new long[] {first, last});
        } else if (first <= span[1] + 1 && last >= span[0] - 1) {
            span[0] = Math.min(span[0], first);
            span[1] = Math.max(span[1], last);
        } else if (first < span[0]) {
            spans.put(run, new long[] {first, last});
        }
    }

    static List<RunEventReplayBuffer.Span> spansOf(Map<RunEventReplayBuffer, long[]> spans) {
        if (spans.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<RunEventReplayBuffer.Span>(spans.size());
        spans.forEach((run, span) -> out.add(new RunEventReplayBuffer.Span(run, span[0], span[1])));
        return List.copyOf(out);
    }

    /** {@code trackingId}'s client received the frames of {@code spans}; see {@link RunEventReplayBuffer#handed}. */
    private static void received(List<RunEventReplayBuffer.Span> spans, String trackingId) {
        // In the order of their first event: a span of a run may follow on from an earlier one of it.
        var ordered = new ArrayList<>(spans);
        ordered.sort(Comparator.comparingLong(RunEventReplayBuffer.Span::first));
        for (var span : ordered) {
            try {
                span.reachedBy(trackingId);
            } catch (RuntimeException e) {
                // Its next poll carrying the run id may then be handed these frames again.
                logger.debug("Unable to record what {} received of a run", trackingId, e);
            }
        }
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
        /** Run -> the span of its events whose frames were added; at most one run per session. */
        private final Map<RunEventReplayBuffer, long[]> spans = new IdentityHashMap<>();
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

        /**
         * The frames added so far include the frame of the run event at
         * {@code position} (or the event's frame was filtered out): the client
         * that receives them was handed that event. Ignored once the buffer was
         * closed.
         */
        synchronized void reached(RunEventReplayBuffer.Position position) {
            if (!closed.get()) {
                advance(spans, position.run(), position.sequence(), position.sequence());
            }
        }

        /** Takes the frames added so far; their bytes stay reserved by the batch. */
        synchronized Batch drain() {
            var batch = new Batch(List.copyOf(frames), bytes, spansOf(spans));
            frames.clear();
            spans.clear();
            bytes = 0;
            return batch;
        }

        /** Drops the frames added so far and releases their bytes. */
        synchronized void discardFrames() {
            frames.clear();
            spans.clear();
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
        /**
         * Run -> the span of its events the parked frames stand for; one entry
         * per run whose frames are parked, so bounded like the frames.
         */
        private final Map<RunEventReplayBuffer, long[]> spans = new IdentityHashMap<>();
        private long bytes;
        private volatile long touchedNanos = System.nanoTime();
        /** Set, under the lock, once the entry left the map; a hand-over then parks in a new one. */
        private boolean dead;

        private Parked(Object owner, long ttlNanos) {
            this.owner = owner;
            this.ttlNanos = ttlNanos;
        }
    }

    /** Reserves {@code size} bytes, or none when that would take every reply past {@code max}. */
    static boolean reserve(long size, long max) {
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

    /** Releases {@code size} bytes {@link #reserve} reserved. */
    static void release(long size) {
        RESERVED.addAndGet(-size);
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
                for (var span : batch.spans()) {
                    advance(entry.spans, span.run(), span.first(), span.last());
                }
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
        AtmosphereConfig config;
        try {
            config = origin.getAtmosphereConfig();
        } catch (RuntimeException e) {
            // The reply stays parked: the client's next poll takes it.
            logger.debug("Unable to resume the poll of {}", trackingId, e);
            return;
        }
        wake(config, trackingId);
    }

    /** Resumes the suspended poll of {@code trackingId}, empty, so its next poll takes what waits for it. */
    static void wake(AtmosphereConfig config, String trackingId) {
        try {
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
     * {@code ownerMatches} accepts the identity the reply was asked under, with
     * the frames of room replies kept for that client
     * ({@link LongPollingRoomReplies}) whose membership {@code ownerMatches}
     * accepts too, after them. The frames leave the parked reply and the
     * mailboxes only once {@code writer} returned. A poll that is not suspended
     * is never resumed by a hand-over, so nothing ends it in the middle of the
     * write. The write holds up only this client's own polls and hand-overs,
     * never the room's broadcaster: the room frames kept for the client while it
     * runs wait for its next poll. The frames of a run written count as handed
     * to the poll's tracking id in the run's replay when they follow on from
     * what it was handed before without a gap ({@link RunEventReplayBuffer#handed}).
     *
     * @return whether a reply was written; the poll is then answered with it
     * instead of being suspended
     */
    public static boolean deliver(AtmosphereResource poll, Predicate<Object> ownerMatches, FrameWriter writer) {
        var trackingId = poll.uuid();
        if (trackingId == null) {
            return false;
        }
        var entry = PARKED.get(trackingId);
        var mailboxes = LongPollingRoomReplies.mailboxes(trackingId);
        if (entry == null && mailboxes.isEmpty()) {
            return false;
        }
        // Always the parked reply's lock first, then the mailboxes' delivery locks
        // in their fixed order: no other path holds more than one of them.
        if (entry != null) {
            entry.lock.lock();
        }
        var locked = 0;
        var taken = new ArrayList<LongPollingRoomReplies.Member>();
        var written = false;
        var reached = new ArrayList<RunEventReplayBuffer.Span>();
        try {
            for (var mailbox : mailboxes) {
                mailbox.lockDelivery();
                locked++;
            }
            if (poll.isResumed() || poll.isCancelled()) {
                // A poll the hand-over already resumed: the next one takes the frames.
                logger.debug("Poll of {} not handed its parked reply (resumed={})", trackingId, poll.isResumed());
                return false;
            }
            var frames = new ArrayList<String>();
            var takeParked = false;
            if (entry != null && !entry.dead && !entry.frames.isEmpty()) {
                if (ownerMatches.test(entry.owner)) {
                    frames.addAll(entry.frames);
                    takeParked = true;
                } else {
                    // Not logged with the identities: one of them is another user's.
                    logger.warn("A poll of {} carries another identity than its parked reply; not delivered",
                            trackingId);
                }
            }
            for (var mailbox : mailboxes) {
                if (!ownerMatches.test(mailbox.owner())) {
                    if (mailbox.hasFrames()) {
                        logger.warn("A poll of {} carries another identity than its room membership; room frames"
                                + " not delivered", trackingId);
                    }
                    continue;
                }
                var kept = mailbox.take();
                taken.add(mailbox);
                frames.addAll(kept);
            }
            if (frames.isEmpty()) {
                return false;
            }
            // The write runs under the parked reply's lock and the delivery locks: a
            // hand-over of this client's own reply (and the sweep of that reply)
            // waits for it, so no resume ends the poll in the middle of it. The room
            // frames stay in their mailboxes until it returned, and the mailbox locks
            // the room's broadcaster takes on every frame are not held: frames kept
            // for this client while it runs wait for its next poll.
            try {
                writer.write(poll, List.copyOf(frames));
            } catch (IOException | RuntimeException e) {
                logger.debug("Writing the parked reply of {} failed; it stays parked", trackingId, e);
                return false;
            }
            written = true;
            logger.debug("Delivered {} parked frame(s) to the poll of {}", frames.size(), trackingId);
            if (takeParked) {
                RESERVED.addAndGet(-entry.bytes);
                entry.frames.clear();
                reached.addAll(spansOf(entry.spans));
                entry.spans.clear();
                entry.bytes = 0;
                entry.dead = true;
                PARKED.remove(trackingId, entry);
            }
            return true;
        } finally {
            for (var mailbox : taken) {
                if (written) {
                    reached.addAll(mailbox.taken());
                } else {
                    mailbox.untaken();
                }
            }
            for (var i = locked - 1; i >= 0; i--) {
                mailboxes.get(i).unlockDelivery();
            }
            if (entry != null) {
                entry.lock.unlock();
            }
            // Outside every lock of the client: a run's buffer has a lock of its own.
            received(reached, trackingId);
        }
    }

    /**
     * Whether a reply, or a room frame kept for the client, waits for the next
     * poll of {@code trackingId} that {@code ownerMatches} accepts the identity
     * of.
     */
    public static boolean hasParked(String trackingId, Predicate<Object> ownerMatches) {
        var entry = trackingId != null ? PARKED.get(trackingId) : null;
        if (entry != null) {
            entry.lock.lock();
            try {
                if (!entry.dead && !entry.frames.isEmpty() && ownerMatches.test(entry.owner)) {
                    return true;
                }
            } finally {
                entry.lock.unlock();
            }
        }
        for (var mailbox : LongPollingRoomReplies.mailboxes(trackingId)) {
            // After a poll that is writing the mailbox: the frames it writes are not waiting any more.
            mailbox.lockDelivery();
            try {
                if (mailbox.hasFrames() && ownerMatches.test(mailbox.owner())) {
                    return true;
                }
            } finally {
                mailbox.unlockDelivery();
            }
        }
        return false;
    }

    /**
     * Drops the reply parked for a client that went away; the client is no
     * longer a long-polling member of any room ({@link LongPollingRoomReplies}),
     * and the room frames kept for it are dropped too.
     */
    public static void discard(String trackingId) {
        discard(trackingId, false);
    }

    /**
     * {@link #discard(String)}; when the client {@code departed} for good (it
     * closed its connection on purpose, rather than lost it), every run also
     * forgets its replay position ({@link RunEventReplayBuffer#forget}). A client
     * that lost its connection may come back under its tracking id: its runs
     * then hand it what it missed and nothing more.
     */
    public static void discard(String trackingId, boolean departed) {
        if (trackingId == null) {
            return;
        }
        LongPollingRoomReplies.forget(trackingId, departed);
        if (departed) {
            forgetRunPositions(trackingId);
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
     * Drops the parked replies no poll took within their time to live, and the
     * room members no poll of which was seen for it, with the frames kept for
     * them; run by {@link StreamingSessionSweeper}, and by a hand-over that
     * finds no room.
     *
     * @return how many parked replies were dropped
     */
    static int sweepExpired() {
        LongPollingRoomReplies.sweepExpired();
        var now = System.nanoTime();
        var dropped = 0;
        for (var e : PARKED.entrySet()) {
            if (now - e.getValue().touchedNanos > e.getValue().ttlNanos && drop(e.getKey(), e.getValue())) {
                dropped++;
                // No poll of it for the time to live: its client is gone.
                forgetRunPositions(e.getKey());
                logger.debug("No poll took the long-polling reply of {}; dropped", e.getKey());
            }
        }
        return dropped;
    }

    /** The client of {@code trackingId} is gone for good: every run forgets its replay position. */
    static void forgetRunPositions(String trackingId) {
        try {
            org.atmosphere.ai.resume.RunRegistryHolder.get().forgetConsumer(trackingId);
        } catch (RuntimeException e) {
            logger.debug("Unable to forget the run replay positions of {}", trackingId, e);
        }
    }

    private static boolean drop(String trackingId, Parked entry) {
        entry.lock.lock();
        try {
            if (entry.dead) {
                return false;
            }
            RESERVED.addAndGet(-entry.bytes);
            entry.frames.clear();
            entry.spans.clear();
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
