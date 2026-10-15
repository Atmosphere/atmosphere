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

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * for every member in that member's mailbox ({@link LongPollingReplies}) instead
 * of being written to a waiting poll of it. At a terminal frame and at an
 * {@code approval-required} frame the member's waiting poll is resumed, empty,
 * and its next poll receives every frame kept for it in one response, as a
 * client's own long-polling reply is delivered. A client that becomes a member
 * in the middle of a reply receives that reply from there on.</p>
 *
 * <p>Nothing changes for a room whose broadcaster has a {@link BroadcasterCache}
 * configured: the cache is left to keep those frames for a client between polls
 * and hand them to its next poll, as {@code UUIDBroadcasterCache} and
 * {@code BoundedMemoryCache} do for each client seen recently. A frame that cannot be kept for a member (no
 * room is left for long-polling replies) reaches it the way it did before, only
 * while a poll of it waits; one that would take the frames waiting for a member
 * past the bounds of one reply is replaced, for that member, by an {@code error}
 * frame, and the rest of that reply is not kept for it.</p>
 */
public final class LongPollingRoomReplies {

    private static final Logger logger = LoggerFactory.getLogger(LongPollingRoomReplies.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Error sent to a member, in place of a room reply, that the reply outgrew. */
    static final String TOO_LARGE_MESSAGE = "The reply is too large to be delivered over long-polling";

    /** Broadcaster -> its room; an entry leaves when its broadcaster is destroyed. */
    private static final ConcurrentHashMap<Broadcaster, Room> ROOMS = new ConcurrentHashMap<>();

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

    /** {@code trackingId}'s client left: it is no longer a member of any room. */
    static void forget(String trackingId) {
        if (trackingId == null) {
            return;
        }
        for (var room : ROOMS.values()) {
            room.members.remove(trackingId);
        }
    }

    /** Members of {@code broadcaster}'s room; for tests. */
    static Set<String> members(Broadcaster broadcaster) {
        var room = ROOMS.get(broadcaster);
        return room != null ? Set.copyOf(room.members.keySet()) : Set.of();
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
            var handOver = switch (kind) {
                case "complete", "error", RunCancelledException.FRAME_TYPE, "approval-required" -> true;
                default -> false;
            };
            return new Frame(sessionId.stringValue(), seq != null ? seq.asLong() : 0L, handOver);
        } catch (JacksonException | IllegalArgumentException e) {
            logger.trace("Not an AI frame", e);
            return null;
        }
    }

    /** What the room needs of an AI frame: its session, its sequence and whether it ends a hand-over. */
    private record Frame(String sessionId, long seq, boolean handOver) {
    }

    /** A broadcaster's long-polling members, fed by its broadcasts. */
    private static final class Room extends BroadcasterListenerAdapter {

        private final ConcurrentHashMap<String, Member> members = new ConcurrentHashMap<>();
        private final Suppressor suppressor = new Suppressor(this);

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
            members.clear();
        }

        /**
         * Keeps a frame of an AI reply broadcast to the whole room for every
         * member. Runs where the broadcaster delivers the frame, after its
         * filters and before it is written to any subscriber; a frame kept for a
         * member is then not written to a poll of it ({@link Suppressor}).
         */
        @Override
        public void onMessage(Broadcaster b, Deliver deliver) {
            if (members.isEmpty() || deliver.getType() != Deliver.TYPE.ALL || !deliver.isWriteLocally()
                    || !(deliver.getMessage() instanceof RawMessage raw) || !(raw.message() instanceof String json)
                    || cacheKeepsFrames(b)) {
                return;
            }
            var frame = parse(json);
            if (frame == null) {
                return;
            }
            var now = System.nanoTime();
            for (var member : members.values()) {
                if (member.expired(now)) {
                    members.remove(member.trackingId, member);
                    logger.debug("Long-polling client {} left room {}: no poll of it for its time to live",
                            member.trackingId, b.getID());
                    continue;
                }
                member.keep(frame, json);
            }
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
                    members.values().removeIf(m -> m.expired(now));
                }
                if (members.size() >= limits.maxParkedReplies()) {
                    logger.warn("A room already has {} long-polling members; {} receives its replies only while"
                            + " a poll of it waits", limits.maxParkedReplies(), trackingId);
                    return;
                }
                member = members.computeIfAbsent(trackingId, Member::new);
            }
            member.polled(owner, config, limits);
        }

        private static boolean cacheKeepsFrames(Broadcaster b) {
            try {
                var cache = b.getBroadcasterConfig().getBroadcasterCache();
                return cache != null && !cache.getClass().equals(BroadcasterCache.DEFAULT.getClass());
            } catch (RuntimeException e) {
                logger.trace("Unable to read the cache of {}", b.getID(), e);
                return true;
            }
        }
    }

    /** One long-polling client of a room, and the room frames kept for it. */
    private static final class Member {
        private final String trackingId;
        private volatile Object owner;
        private volatile AtmosphereConfig config;
        private volatile LongPollingReplies.Limits limits = LongPollingReplies.Limits.DEFAULTS;
        private volatile long seenNanos = System.nanoTime();
        /** The last frame kept for this member, which no poll of it is then written. */
        private volatile String kept;
        /** A reply this member outgrew: the rest of it is not kept for it, nor written to its polls. */
        private String abandonedSession;
        /** A reply a frame of which could not be kept: the rest of it reaches this member's polls directly. */
        private String bypassedSession;

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

        /** Whether {@code json} was kept for this member, so a poll of it must not be written it too. */
        boolean kept(String json) {
            return json.equals(kept);
        }

        /** Keeps {@code frame} for this member; runs on the broadcaster's delivery, one frame at a time. */
        synchronized void keep(Frame frame, String json) {
            if (frame.sessionId().equals(bypassedSession)) {
                return;
            }
            if (frame.sessionId().equals(abandonedSession)) {
                kept = json;
                return;
            }
            switch (LongPollingReplies.parkRoomFrame(config, trackingId, owner, json, frame.handOver(), limits)) {
                case PARKED -> kept = json;
                case REFUSED -> bypassedSession = frame.sessionId();
                case TOO_LARGE -> {
                    abandonedSession = frame.sessionId();
                    kept = json;
                    logger.warn("The room reply {} outgrew the bounds of a long-polling reply for {}; sending it"
                            + " an error frame in its place", frame.sessionId(), trackingId);
                    LongPollingReplies.discardParked(trackingId);
                    var error = errorFrame(frame);
                    if (error != null) {
                        LongPollingReplies.parkRoomFrame(config, trackingId, owner, error, true, limits);
                    }
                }
            }
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
     * Keeps a frame kept for a long-polling member off that member's waiting
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
            if (message instanceof RawMessage raw && raw.message() instanceof String json && isLongPolling(r)) {
                var member = room.members.get(r.uuid());
                if (member != null && member.kept(json)) {
                    return new BroadcastAction(BroadcastAction.ACTION.ABORT, message);
                }
            }
            return new BroadcastAction(message);
        }
    }
}
