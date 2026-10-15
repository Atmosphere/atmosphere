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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcastFilter.BroadcastAction;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.cpr.BroadcasterListenerAdapter;
import org.atmosphere.cpr.Deliver;
import org.atmosphere.cpr.PerRequestBroadcastFilter;
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Delivery of a room reply ({@code @AiEndpoint(broadcastReply = true)},
 * {@link StreamingSessions#startRoomBroadcast}) to the long-polling members of
 * the room.
 *
 * <p>A room reply is broadcast to every subscriber of the room's broadcaster,
 * but a long-polling client is a subscriber only while one of its polls waits:
 * the first frame answers that poll, and the frames broadcast before its next
 * poll would not reach it. So a long-polling client is a member of the room from
 * the first of its polls the room's broadcaster takes until it leaves, or until
 * no poll of it was seen for the parked-reply time to live
 * ({@link LongPollingReplies#PARKED_TTL_MS_PARAM}), whether a poll of it waits
 * or not. Each frame of an AI reply broadcast to the whole room, as the room's
 * filters let it through (it is filtered once, for every subscriber), is kept
 * in every member's own mailbox instead of being written to a waiting poll of
 * it. At a terminal frame and at an {@code approval-required} frame the member's
 * waiting poll is resumed, empty, and its next poll receives every frame kept
 * for it in one response ({@link LongPollingReplies#deliver}), with the reply of
 * its own that waits for it, if any. A client that becomes a member in the
 * middle of a reply receives that reply from there on.</p>
 *
 * <p>Bounds (Correctness Invariant #3), from the init parameters of the member's
 * framework. A room takes at most
 * {@value LongPollingReplies#MAX_PARKED_REPLIES_PARAM} members; a member's
 * mailbox does not count against that bound for the replies waiting in the JVM.
 * A frame kept for several members is held once: its bytes count once against
 * {@value LongPollingReplies#MAX_BUFFERED_BYTES_PARAM}, and each member it is
 * kept for adds {@value #MEMBER_FRAME_BYTES} bytes. The frames of one reply
 * waiting for a member are bounded like one reply
 * ({@value LongPollingReplies#MAX_REPLY_BYTES_PARAM},
 * {@value LongPollingReplies#MAX_REPLY_FRAMES_PARAM}); the reply that would
 * outgrow them is replaced, for that member only, by an {@code error} frame, and
 * the rest of that reply is not kept for it, while the other replies kept for
 * it stay whole. A member follows at most {@value #MAX_REPLIES_PER_MEMBER}
 * replies at once. A frame of a reply that cannot be kept (no room is left, or
 * the member already follows that many replies) reaches that member the way it
 * did before, only while a poll of it waits, and so does the rest of that
 * reply.</p>
 *
 * <p>When the room's broadcaster has a {@link BroadcasterCache}, the frame kept
 * for a member is also removed from what the cache keeps for that member, so its
 * next poll does not receive it twice; the cache keeps the frames that could not
 * be kept for the member, as its inspectors allow. When the framework shares one
 * list of broadcaster listeners between broadcasters
 * ({@link ApplicationConfig#BROADCASTER_SHAREABLE_LISTENERS}), a broadcaster
 * registers no listener of its own, so no room is set up: its long-polling
 * clients receive a room reply only while a poll of theirs waits.</p>
 */
public final class LongPollingRoomReplies {

    private static final Logger logger = LoggerFactory.getLogger(LongPollingRoomReplies.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Error sent to a member, in place of a room reply, that the reply outgrew. */
    static final String TOO_LARGE_MESSAGE = "The reply is too large to be delivered over long-polling";
    /** Bytes a frame kept for one more member counts, on top of the frame's own bytes, counted once. */
    static final int MEMBER_FRAME_BYTES = 16;
    /** Replies a member follows at once; the frames of a reply past them are not kept for it. */
    static final int MAX_REPLIES_PER_MEMBER = 64;

    /** Broadcaster -> its room; an entry leaves when its broadcaster is destroyed. */
    private static final ConcurrentHashMap<Broadcaster, Room> ROOMS = new ConcurrentHashMap<>();
    /** Tracking id -> its memberships, in the order they were made; see {@link #mailboxes}. */
    private static final ConcurrentHashMap<String, List<Member>> MEMBERSHIPS = new ConcurrentHashMap<>();
    private static final AtomicLong MEMBER_SEQUENCE = new AtomicLong();
    private static final AtomicBoolean SHARED_LISTENERS_WARNED = new AtomicBoolean();

    private LongPollingRoomReplies() {
    }

    /**
     * Makes {@code broadcaster} a room whose AI replies reach its long-polling
     * members whole. Idempotent; the long-polling clients subscribed to it right
     * now become members. Called before a connection of a room endpoint is
     * suspended, so the connection becomes a member as it subscribes.
     *
     * @param broadcaster the room's broadcaster; {@code null} is ignored
     */
    public static void install(Broadcaster broadcaster) {
        if (broadcaster == null || ROOMS.containsKey(broadcaster)) {
            return;
        }
        var installed = ROOMS.computeIfAbsent(broadcaster, LongPollingRoomReplies::attach);
        if (installed != null) {
            try {
                for (var subscriber : broadcaster.getAtmosphereResources()) {
                    if (isLongPolling(subscriber)) {
                        installed.join(subscriber);
                    }
                }
            } catch (RuntimeException e) {
                logger.debug("Unable to read the subscribers of {}", broadcaster, e);
            }
        }
    }

    /** Registers a new room's listener and filter on {@code broadcaster}, or returns {@code null} when it cannot. */
    private static Room attach(Broadcaster broadcaster) {
        try {
            var config = broadcaster.getBroadcasterConfig();
            if (config == null) {
                return null;
            }
            if (sharesListeners(config.getAtmosphereConfig())) {
                // The broadcaster would ignore the room's listener: the room would never see a member, and
                // nothing would remove it from ROOMS once the broadcaster is destroyed.
                if (SHARED_LISTENERS_WARNED.compareAndSet(false, true)) {
                    logger.warn("{} is set: the AI room replies of {} and every other room reach their long-polling"
                            + " members only while a poll waits", ApplicationConfig.BROADCASTER_SHAREABLE_LISTENERS,
                            broadcaster.getID());
                } else {
                    logger.debug("Shared broadcaster listeners: no long-polling room for {}", broadcaster.getID());
                }
                return null;
            }
            var room = new Room();
            broadcaster.addBroadcasterListener(room);
            try {
                config.addFilter(room.suppressor);
            } catch (RuntimeException e) {
                broadcaster.removeBroadcasterListener(room);
                throw e;
            }
            return room;
        } catch (RuntimeException e) {
            logger.warn("Unable to deliver the AI replies of {} to its long-polling members; they receive them"
                    + " only while a poll waits", broadcaster, e);
            return null;
        }
    }

    private static boolean sharesListeners(AtmosphereConfig config) {
        return config != null && config.getInitParameter(ApplicationConfig.BROADCASTER_SHAREABLE_LISTENERS, false);
    }

    /** {@code trackingId}'s client left: it is no longer a member of any room, and what was kept for it is dropped. */
    static void forget(String trackingId) {
        if (trackingId == null) {
            return;
        }
        for (var room : ROOMS.values()) {
            var member = room.members.remove(trackingId);
            if (member != null) {
                member.leave();
            }
        }
    }

    /**
     * The mailboxes of {@code trackingId}, one per room it is a member of, in a
     * fixed order: {@link LongPollingReplies#deliver} takes their delivery locks
     * in that order.
     */
    static List<Member> mailboxes(String trackingId) {
        return trackingId != null ? MEMBERSHIPS.getOrDefault(trackingId, List.of()) : List.of();
    }

    /**
     * Drops the members no poll of which was seen for their time to live, with
     * what was kept for them; run with {@link LongPollingReplies#sweepExpired}.
     *
     * @return how many members left
     */
    static int sweepExpired() {
        var now = System.nanoTime();
        var left = 0;
        for (var room : ROOMS.entrySet()) {
            for (var member : room.getValue().members.values()) {
                if (member.expired(now) && room.getValue().members.remove(member.trackingId, member)) {
                    member.leave();
                    left++;
                    logger.debug("Long-polling client {} left room {}: no poll of it for its time to live",
                            member.trackingId, room.getKey().getID());
                }
            }
        }
        return left;
    }

    /** Members of {@code broadcaster}'s room; for tests. */
    static Set<String> members(Broadcaster broadcaster) {
        var room = ROOMS.get(broadcaster);
        return room != null ? Set.copyOf(room.members.keySet()) : Set.of();
    }

    /** Whether {@code broadcaster} is a room; for tests. */
    static boolean installed(Broadcaster broadcaster) {
        return ROOMS.containsKey(broadcaster);
    }

    private static boolean isLongPolling(AtmosphereResource resource) {
        try {
            return resource != null && resource.transport() == AtmosphereResource.TRANSPORT.LONG_POLLING;
        } catch (RuntimeException e) {
            logger.trace("Unable to read the transport of {}", resource, e);
            return false;
        }
    }

    /** The AI frame {@code json} is, or {@code null} when it is not one. */
    private static Frame parse(String json) {
        try {
            var node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                return null;
            }
            var sessionId = node.get("sessionId");
            if (sessionId == null || !sessionId.isString()) {
                return null;
            }
            var type = node.get("type");
            var event = node.get("event");
            String kind;
            if (type != null && type.isString()) {
                kind = type.stringValue();
            } else if (event != null && event.isString()) {
                kind = event.stringValue();
            } else {
                return null;
            }
            var seq = node.get("seq");
            var terminal = switch (kind) {
                case "complete", "error", RunCancelledException.FRAME_TYPE -> true;
                default -> false;
            };
            return new Frame(sessionId.stringValue(), seq != null ? seq.asLong() : 0L,
                    terminal || "approval-required".equals(kind), terminal);
        } catch (JacksonException | IllegalArgumentException e) {
            logger.trace("Not an AI frame", e);
            return null;
        }
    }

    /**
     * What the room needs of an AI frame: its session, its sequence, whether it
     * ends a hand-over and whether it ends its reply.
     */
    private record Frame(String sessionId, long seq, boolean handOver, boolean terminal) {
    }

    /** What became of a frame offered to a member. */
    private enum Outcome {
        /** Kept in the member's mailbox. */
        KEPT,
        /** Not kept and not to be written to the member: its reply ended, for it, with an error frame. */
        SUPPRESSED,
        /** Not kept: it reaches the member only while a poll of it waits. */
        NOT_KEPT
    }

    /**
     * A frame kept for one member or more, held once; its bytes are reserved
     * while at least one member holds it.
     */
    private static final class Kept {
        private final String json;
        private final String sessionId;
        private final long size;
        private int holders;

        private Kept(String json, String sessionId) {
            this.json = json;
            this.sessionId = sessionId;
            this.size = json.getBytes(StandardCharsets.UTF_8).length;
        }

        /** One more member holds this frame, or none can: no room is left under {@code maxBytes}. */
        synchronized boolean hold(long maxBytes) {
            if (holders == 0 && !LongPollingReplies.reserve(size, maxBytes)) {
                return false;
            }
            holders++;
            return true;
        }

        synchronized void release() {
            if (holders > 0 && --holders == 0) {
                LongPollingReplies.release(size);
            }
        }
    }

    /** A reply a member follows, while it runs. */
    private static final class Reply {
        /** Its frames are no longer kept for the member: an error frame took their place. */
        private boolean abandoned;
        /** Its frames are no longer kept for the member: they reach its waiting polls directly. */
        private boolean bypassed;
        /** Bytes and frames of it waiting for the member. */
        private long bytes;
        private int frames;
        private long touchedNanos = System.nanoTime();
    }

    /**
     * The frame a delivery thread of the room's broadcaster is at, and the members
     * it was kept for, or ended a reply for, so that it is not written to a poll of
     * theirs.
     */
    private record Withheld(String json, Set<String> trackingIds) {
    }

    /** A broadcaster's long-polling members, fed by its broadcasts. */
    private static final class Room extends BroadcasterListenerAdapter {

        private final ConcurrentHashMap<String, Member> members = new ConcurrentHashMap<>();
        private final Suppressor suppressor = new Suppressor(this);
        /**
         * Set by {@link #onMessage} for the {@link Suppressor}: the broadcaster
         * runs its listeners for a frame, then its per-request filters for each
         * subscriber, on one thread, and with out-of-order broadcast several
         * threads each deliver a frame of their own at once.
         */
        private final ThreadLocal<Withheld> withheld = new ThreadLocal<>();

        @Override
        public void onAddAtmosphereResource(Broadcaster b, AtmosphereResource r) {
            if (isLongPolling(r)) {
                join(r);
            }
        }

        @Override
        public void onRemoveAtmosphereResource(Broadcaster b, AtmosphereResource r) {
            if (isLongPolling(r)) {
                var member = members.get(r.uuid());
                if (member != null) {
                    member.seen();
                }
            }
        }

        @Override
        public void onPreDestroy(Broadcaster b) {
            ROOMS.remove(b, this);
            for (var member : members.values()) {
                if (members.remove(member.trackingId, member)) {
                    member.leave();
                }
            }
        }

        /**
         * Keeps a frame of an AI reply broadcast to the whole room for every
         * member. Runs where the broadcaster delivers the frame, after its
         * filters and before it is written to any subscriber; a frame kept for a
         * member is then not written to a poll of it ({@link Suppressor}), nor
         * kept for it by the broadcaster's cache.
         */
        @Override
        public void onMessage(Broadcaster b, Deliver deliver) {
            withheld.remove();
            if (members.isEmpty() || deliver.getType() != Deliver.TYPE.ALL || !deliver.isWriteLocally()
                    || !(deliver.getMessage() instanceof RawMessage raw) || !(raw.message() instanceof String json)) {
                return;
            }
            var frame = parse(json);
            if (frame == null) {
                return;
            }
            var kept = new Kept(json, frame.sessionId());
            var cache = cacheOf(b);
            var now = System.nanoTime();
            var withheldFor = new HashSet<String>();
            for (var member : members.values()) {
                if (member.expired(now)) {
                    if (members.remove(member.trackingId, member)) {
                        member.leave();
                        logger.debug("Long-polling client {} left room {}: no poll of it for its time to live",
                                member.trackingId, b.getID());
                    }
                    continue;
                }
                if (member.keep(frame, kept) == Outcome.NOT_KEPT) {
                    continue;
                }
                withheldFor.add(member.trackingId);
                if (cache != null && deliver.getCache() != null) {
                    try {
                        cache.clearCache(b.getID(), member.trackingId, deliver.getCache());
                    } catch (RuntimeException e) {
                        logger.debug("Unable to remove a room frame kept for {} from the cache of {}",
                                member.trackingId, b.getID(), e);
                    }
                }
            }
            if (!withheldFor.isEmpty()) {
                withheld.set(new Withheld(json, Set.copyOf(withheldFor)));
            }
        }

        /** Whether the frame this thread delivers, {@code json}, was kept for, or withheld from, {@code trackingId}. */
        boolean withholds(String json, String trackingId) {
            var current = withheld.get();
            return current != null && trackingId != null && current.trackingIds().contains(trackingId)
                    && current.json().equals(json);
        }

        void join(AtmosphereResource r) {
            var trackingId = r.uuid();
            if (trackingId == null) {
                return;
            }
            AtmosphereConfig config;
            try {
                config = r.getAtmosphereConfig();
            } catch (RuntimeException e) {
                logger.trace("Unable to read the framework of {}", trackingId, e);
                return;
            }
            var limits = LongPollingReplies.Limits.of(config);
            var owner = DefaultStreamingSession.connectionOwner(r);
            var member = members.get(trackingId);
            if (member == null) {
                if (members.size() >= limits.maxParkedReplies()) {
                    var now = System.nanoTime();
                    for (var m : members.values()) {
                        if (m.expired(now) && members.remove(m.trackingId, m)) {
                            m.leave();
                        }
                    }
                }
                if (members.size() >= limits.maxParkedReplies()) {
                    logger.warn("A room already has {} long-polling members; {} receives its replies only while"
                            + " a poll of it waits", limits.maxParkedReplies(), trackingId);
                    return;
                }
                // Listed under its tracking id before any other thread can see it, so a
                // removal, which comes after, always finds it listed.
                member = members.computeIfAbsent(trackingId, id -> {
                    var created = new Member(id);
                    created.index();
                    return created;
                });
            }
            member.polled(owner, config, limits);
        }

        /** The broadcaster's cache, or {@code null} when it keeps nothing. */
        private static BroadcasterCache cacheOf(Broadcaster b) {
            try {
                var cache = b.getBroadcasterConfig().getBroadcasterCache();
                return cache != null && !cache.getClass().equals(BroadcasterCache.DEFAULT.getClass()) ? cache : null;
            } catch (RuntimeException e) {
                logger.trace("Unable to read the cache of {}", b.getID(), e);
                return null;
            }
        }
    }

    /** One long-polling client of a room, and its mailbox: the room frames kept for it. */
    static final class Member {
        private final String trackingId;
        private final long order = MEMBER_SEQUENCE.incrementAndGet();
        /**
         * Guards the mailbox, only for as long as it takes to change it: the
         * room's broadcaster waits for it on every frame. Nothing run under it
         * writes to a client, takes the delivery lock or resumes a poll.
         */
        private final ReentrantLock lock = new ReentrantLock();
        /**
         * Held by {@link LongPollingReplies#deliver} while a poll of this client
         * takes the mailbox, the write of the poll's response included, after the
         * lock of the client's parked reply; and by
         * {@link LongPollingReplies#hasParked}. Only this client's own polls wait
         * for it, never the room's broadcaster.
         */
        private final ReentrantLock delivery = new ReentrantLock();
        /** The frames a poll is writing; guarded by {@link #delivery}. */
        private List<Kept> taking = List.of();
        private volatile Object owner;
        private volatile AtmosphereConfig config;
        private volatile LongPollingReplies.Limits limits = LongPollingReplies.Limits.DEFAULTS;
        private volatile long seenNanos = System.nanoTime();
        private final ArrayList<Kept> mailbox = new ArrayList<>();
        /** The replies this member follows while they run, at most {@value #MAX_REPLIES_PER_MEMBER}. */
        private final LinkedHashMap<String, Reply> replies = new LinkedHashMap<>();
        /** Left its room: nothing more is kept for it. */
        private boolean gone;

        private Member(String trackingId) {
            this.trackingId = trackingId;
        }

        void polled(Object owner, AtmosphereConfig config, LongPollingReplies.Limits limits) {
            this.owner = owner;
            this.config = config;
            this.limits = limits;
            seen();
        }

        void seen() {
            seenNanos = System.nanoTime();
        }

        /**
         * No poll of it was seen for its time to live, and none waits: a poll
         * that waits longer than that, under a long suspend timeout, still counts.
         */
        boolean expired(long now) {
            if (now - seenNanos <= limits.parkedTtlNanos()) {
                return false;
            }
            try {
                var factory = config != null ? config.resourcesFactory() : null;
                var poll = factory != null ? factory.findResource(trackingId).orElse(null) : null;
                if (poll != null && isLongPolling(poll) && poll.isSuspended()) {
                    seen();
                    return false;
                }
            } catch (RuntimeException e) {
                logger.trace("Unable to find the poll of {}", trackingId, e);
            }
            return true;
        }

        long order() {
            return order;
        }

        Object owner() {
            return owner;
        }

        void lockDelivery() {
            delivery.lock();
        }

        void unlockDelivery() {
            delivery.unlock();
        }

        /** Whether a frame is kept for this member; called holding {@link #lockDelivery}. */
        boolean hasFrames() {
            lock.lock();
            try {
                return !mailbox.isEmpty();
            } finally {
                lock.unlock();
            }
        }

        /**
         * The frames kept for this member so far, which a poll is about to write;
         * they stay in the mailbox until {@link #taken}. Called holding
         * {@link #lockDelivery}.
         */
        List<String> take() {
            lock.lock();
            try {
                taking = List.copyOf(mailbox);
            } finally {
                lock.unlock();
            }
            var frames = new ArrayList<String>(taking.size());
            for (var k : taking) {
                frames.add(k.json);
            }
            return frames;
        }

        /** The poll did not write the frames {@link #take} returned: they stay kept. */
        void untaken() {
            taking = List.of();
        }

        /**
         * The poll wrote the frames {@link #take} returned: they leave the
         * mailbox, and the frames kept while it wrote stay for the next poll.
         * Called holding {@link #lockDelivery}.
         */
        void taken() {
            var written = Collections.newSetFromMap(new IdentityHashMap<Kept, Boolean>());
            written.addAll(taking);
            taking = List.of();
            lock.lock();
            try {
                // A frame an outgrown reply or a departure dropped while the poll wrote was released then.
                mailbox.removeIf(k -> {
                    if (!written.contains(k)) {
                        return false;
                    }
                    k.release();
                    LongPollingReplies.release(MEMBER_FRAME_BYTES);
                    var reply = replies.get(k.sessionId);
                    if (reply != null && !reply.abandoned) {
                        reply.bytes = Math.max(0, reply.bytes - k.size);
                        reply.frames = Math.max(0, reply.frames - 1);
                    }
                    return true;
                });
            } finally {
                lock.unlock();
            }
        }

        /**
         * Offers {@code frame} to this member; runs on a delivery thread of the
         * room's broadcaster, one frame at a time per member under its lock.
         */
        Outcome keep(Frame frame, Kept k) {
            var handOver = new boolean[1];
            try {
                return offer(frame, k, handOver);
            } finally {
                if (handOver[0]) {
                    // Outside the lock: nothing run under the mailbox lock resumes a poll. A
                    // poll deliver writes is not suspended, so this never resumes it in the
                    // middle of the write.
                    wake();
                }
            }
        }

        private Outcome offer(Frame frame, Kept k, boolean[] handOver) {
            lock.lock();
            try {
                if (gone) {
                    return Outcome.NOT_KEPT;
                }
                var now = System.nanoTime();
                var reply = follow(frame.sessionId(), now);
                if (reply == null) {
                    return Outcome.NOT_KEPT;
                }
                reply.touchedNanos = now;
                if (frame.terminal()) {
                    // Its last frame: nothing of the reply is left to tell apart.
                    replies.remove(frame.sessionId(), reply);
                }
                if (reply.bypassed) {
                    return Outcome.NOT_KEPT;
                }
                if (reply.abandoned) {
                    return Outcome.SUPPRESSED;
                }
                var limits = this.limits;
                if (reply.frames + 1 > limits.maxReplyFrames() || reply.bytes + k.size > limits.maxReplyBytes()) {
                    abandon(reply, frame);
                    handOver[0] = true;
                    return Outcome.SUPPRESSED;
                }
                if (!k.hold(limits.maxBufferedBytes())) {
                    return bypass(reply, frame);
                }
                if (!LongPollingReplies.reserve(MEMBER_FRAME_BYTES, limits.maxBufferedBytes())) {
                    k.release();
                    return bypass(reply, frame);
                }
                mailbox.add(k);
                reply.bytes += k.size;
                reply.frames++;
                if (frame.handOver()) {
                    logger.debug("Kept {} room frame(s) for the next poll of {}", mailbox.size(), trackingId);
                    handOver[0] = true;
                }
                return Outcome.KEPT;
            } finally {
                lock.unlock();
            }
        }

        /** The reply {@code sessionId} as this member follows it, or {@code null} when it follows too many. */
        private Reply follow(String sessionId, long now) {
            var reply = replies.get(sessionId);
            if (reply != null) {
                return reply;
            }
            if (replies.size() >= MAX_REPLIES_PER_MEMBER) {
                // A reply that sent nothing for the time to live is not running any more.
                replies.values().removeIf(r -> now - r.touchedNanos > limits.parkedTtlNanos());
            }
            if (replies.size() >= MAX_REPLIES_PER_MEMBER) {
                logger.debug("{} already follows {} room replies; the frames of {} reach it only while a poll"
                        + " of it waits", trackingId, MAX_REPLIES_PER_MEMBER, sessionId);
                return null;
            }
            reply = new Reply();
            replies.put(sessionId, reply);
            return reply;
        }

        private Outcome bypass(Reply reply, Frame frame) {
            reply.bypassed = true;
            logger.warn("Long-polling replies already hold {} bytes; the rest of room reply {} reaches {} only while"
                    + " a poll of it waits", limits.maxBufferedBytes(), frame.sessionId(), trackingId);
            return Outcome.NOT_KEPT;
        }

        /** Drops the frames of {@code reply} kept for this member and keeps an error frame in their place. */
        private void abandon(Reply reply, Frame frame) {
            reply.abandoned = true;
            logger.warn("The room reply {} outgrew the bounds of a long-polling reply for {}; sending it an error"
                    + " frame in its place", frame.sessionId(), trackingId);
            var it = mailbox.iterator();
            while (it.hasNext()) {
                var k = it.next();
                if (k.sessionId.equals(frame.sessionId())) {
                    it.remove();
                    k.release();
                    LongPollingReplies.release(MEMBER_FRAME_BYTES);
                }
            }
            var json = errorFrame(frame);
            if (json == null) {
                return;
            }
            // Always kept, past the bounds: the member is told why the rest of the reply does not come.
            var error = new Kept(json, frame.sessionId());
            error.hold(Long.MAX_VALUE);
            LongPollingReplies.reserve(MEMBER_FRAME_BYTES, Long.MAX_VALUE);
            mailbox.add(error);
        }

        /** Left its room: what was kept for it is dropped, and nothing more is. */
        void leave() {
            lock.lock();
            try {
                gone = true;
                empty();
                replies.clear();
            } finally {
                lock.unlock();
            }
            MEMBERSHIPS.computeIfPresent(trackingId, (id, list) -> {
                var rest = list.stream().filter(m -> m != this).toList();
                return rest.isEmpty() ? null : rest;
            });
        }

        /** Listed under its tracking id, after the memberships made before it. */
        void index() {
            MEMBERSHIPS.compute(trackingId, (id, list) -> {
                if (list == null) {
                    return List.of(this);
                }
                var all = new ArrayList<>(list);
                all.add(this);
                return List.copyOf(all);
            });
        }

        private void empty() {
            for (var k : mailbox) {
                k.release();
                LongPollingReplies.release(MEMBER_FRAME_BYTES);
            }
            mailbox.clear();
        }

        /** Resumes the member's waiting poll, empty, so its next poll takes the mailbox. */
        private void wake() {
            LongPollingReplies.wake(config, trackingId);
        }

        private static String errorFrame(Frame frame) {
            var msg = new LinkedHashMap<String, Object>();
            msg.put("type", "error");
            msg.put("data", TOO_LARGE_MESSAGE);
            msg.put("sessionId", frame.sessionId());
            msg.put("seq", frame.seq());
            try {
                return MAPPER.writeValueAsString(msg);
            } catch (JacksonException e) {
                logger.error("Failed to serialize an error frame", e);
                return null;
            }
        }
    }

    /**
     * Keeps a frame offered to a long-polling member off that member's waiting
     * poll: its next poll receives it with the rest of the reply.
     */
    private static final class Suppressor implements PerRequestBroadcastFilter {

        private final Room room;

        private Suppressor(Room room) {
            this.room = room;
        }

        @Override
        public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
            return new BroadcastAction(message);
        }

        @Override
        public BroadcastAction filter(String broadcasterId, AtmosphereResource r, Object originalMessage,
                                      Object message) {
            if (message instanceof RawMessage raw && raw.message() instanceof String json && isLongPolling(r)
                    && room.withholds(json, r.uuid())) {
                return new BroadcastAction(BroadcastAction.ACTION.ABORT, message);
            }
            return new BroadcastAction(message);
        }
    }
}
