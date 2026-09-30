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
package org.atmosphere.room;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.interceptor.InvokationOrder;
import org.atmosphere.room.auth.RoomAuth;
import org.atmosphere.room.auth.RoomAuthorizer;
import org.atmosphere.room.protocol.RoomProtocolCodec;
import org.atmosphere.room.protocol.RoomProtocolMessage;
import org.atmosphere.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bridges the atmosphere.js client room protocol to the server-side
 * {@link Room} API. Intercepts JSON messages from clients, decodes them
 * via {@link RoomProtocolCodec}, and routes them to the appropriate
 * {@link Room} operations.
 *
 * <p>This interceptor runs with {@link InvokationOrder.PRIORITY#BEFORE_DEFAULT}
 * priority so it processes messages before
 * {@link org.atmosphere.interceptor.BroadcastOnPostAtmosphereInterceptor}.</p>
 *
 * <p>Register manually or annotate with
 * {@link org.atmosphere.config.service.AtmosphereInterceptorService} for
 * auto-scanning:</p>
 * <pre>{@code
 * framework.interceptor(new RoomProtocolInterceptor());
 * }</pre>
 *
 * @since 4.0
 */
public class RoomProtocolInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(RoomProtocolInterceptor.class);

    private RoomManager roomManager;
    private RoomAuthorizer authorizer;

    /**
     * One {@link DropAnnouncer} per (resource, room) membership, keyed by
     * {@link #membershipKey}. Bounded by live memberships: an entry is removed
     * on explicit leave (with its listener) and when the connection drops.
     */
    private final ConcurrentMap<String, DropAnnouncer> dropAnnouncers = new ConcurrentHashMap<>();

    /**
     * Rooms whose {@link Room#onPresence} LEAVE events this interceptor already
     * hears. Weak, so a destroyed room is not pinned by this set.
     */
    private final Set<Room> watchedRooms = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    @Override
    public void configure(AtmosphereConfig config) {
        this.roomManager = RoomManager.getOrCreate(config.framework());

        // Scan for @RoomAuth on registered AtmosphereHandler classes
        scanAuthorizer(config);

        logger.info("RoomProtocolInterceptor configured");
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        var request = r.getRequest();
        var body = readBody(r, request);

        if (body == null || body.isBlank()) {
            return Action.CONTINUE;
        }

        // Only handle JSON that looks like a room protocol message
        var trimmed = body.trim();
        if (!trimmed.startsWith("{")) {
            return Action.CONTINUE;
        }

        RoomProtocolMessage message;
        try {
            message = RoomProtocolCodec.decode(trimmed);
        } catch (Exception e) {
            logger.debug("Not a room protocol message: {}", e.getMessage());
            return Action.CONTINUE;
        }

        switch (message) {
            case RoomProtocolMessage.Join join -> handleJoin(r, join);
            case RoomProtocolMessage.Leave leave -> handleLeave(r, leave);
            case RoomProtocolMessage.Broadcast broadcast -> handleBroadcast(r, broadcast);
            case RoomProtocolMessage.Direct direct -> handleDirect(r, direct);
            case RoomProtocolMessage.Typing typing -> handleTyping(r, typing);
        }

        // Consume the message — don't let downstream interceptors re-broadcast
        return Action.CANCELLED;
    }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.BEFORE_DEFAULT;
    }

    private void handleJoin(AtmosphereResource r, RoomProtocolMessage.Join join) {
        var room = roomManager.room(join.room());

        if (!authorize(r, join.room(), RoomAction.JOIN)) {
            sendError(r, join.room(), "Unauthorized");
            return;
        }

        var member = join.memberId() != null
                ? new RoomMember(join.memberId(), join.metadata())
                : null;

        // Registered before room.join(): DefaultRoom's own auto-leave listener
        // is added by join(), and listeners run in registration order, so the
        // announcer still sees the membership it is announcing the end of.
        announceDropOf(r, join.room(), room, member);

        if (member != null) {
            room.join(r, member);
        } else {
            room.join(r);
        }

        // Send join ack with current member list
        var members = new ArrayList<>(room.memberInfo().values());
        var ack = RoomProtocolCodec.encodeJoinAck(join.room(), members);
        sendToResource(r, ack);

        // Broadcast presence to other members
        var presence = RoomProtocolCodec.encodePresence(join.room(), "join", member);
        room.broadcast(presence, r);

        // Replay history. If the client supplied a sinceId cursor and the
        // room supports it, send only entries with id > sinceId (avoids the
        // duplicate-message-on-reconnect problem). Otherwise fall back to
        // the legacy BroadcasterCache-driven replay.
        if (join.sinceId() != null && room instanceof DefaultRoom defaultRoom
                && defaultRoom.historySize() > 0) {
            replayHistorySince(r, defaultRoom, join);
        } else {
            replayCachedMessages(r, room);
        }

        logger.debug("Handled JOIN for {} in room '{}'", r.uuid(), join.room());
    }

    private void replayHistorySince(AtmosphereResource r, DefaultRoom room,
                                    RoomProtocolMessage.Join join) {
        var entries = room.historySince(join.sinceId());
        for (var entry : entries) {
            var encoded = RoomProtocolCodec.encodeMessage(
                    join.room(), entry.id(), entry.fromMemberId(), entry.data());
            sendToResource(r, encoded);
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Replayed {} entries to {} (sinceId={})",
                    entries.size(), r.uuid(), join.sinceId());
        }
    }

    private void handleLeave(AtmosphereResource r, RoomProtocolMessage.Leave leave) {
        var room = roomManager.room(leave.room());

        // Look up member info before leaving (for presence broadcast)
        var member = room.memberOf(r).orElse(null);

        // An explicit leave is announced below; the disconnect that follows
        // must not announce it a second time.
        var announcer = dropAnnouncers.remove(membershipKey(r, leave.room()));
        if (announcer != null) {
            r.removeEventListener(announcer);
        }

        room.leave(r);

        // Broadcast leave presence to remaining members
        var presence = RoomProtocolCodec.encodePresence(leave.room(), "leave", member);
        room.broadcast(presence);

        logger.debug("Handled LEAVE for {} in room '{}'", r.uuid(), leave.room());
    }

    private void handleBroadcast(AtmosphereResource r, RoomProtocolMessage.Broadcast broadcast) {
        var room = roomManager.room(broadcast.room());

        if (!authorize(r, broadcast.room(), RoomAction.BROADCAST)) {
            sendError(r, broadcast.room(), "Unauthorized");
            return;
        }

        // Look up sender's member ID for the "from" field
        var fromId = room.memberOf(r).map(RoomMember::id).orElse(null);
        long id = (room instanceof DefaultRoom defaultRoom) ? defaultRoom.nextMessageId() : 0L;
        var encoded = RoomProtocolCodec.encodeMessage(broadcast.room(), id, fromId, broadcast.data());
        if (room instanceof DefaultRoom defaultRoom) {
            defaultRoom.recordHistory(id, fromId, broadcast.data());
        }
        room.broadcast(encoded, r);

        logger.debug("Handled BROADCAST from {} in room '{}'", r.uuid(), broadcast.room());
    }

    private void handleDirect(AtmosphereResource r, RoomProtocolMessage.Direct direct) {
        var room = roomManager.room(direct.room());

        if (!authorize(r, direct.room(), RoomAction.SEND_TO)) {
            sendError(r, direct.room(), "Unauthorized");
            return;
        }

        // Resolve member ID to resource UUID
        var targetUuid = resolveTargetUuid(room, direct.targetId());
        if (targetUuid.isEmpty()) {
            sendError(r, direct.room(), "Member not found: " + direct.targetId());
            return;
        }

        var fromId = room.memberOf(r).map(RoomMember::id).orElse(null);
        var encoded = RoomProtocolCodec.encodeMessage(direct.room(), fromId, direct.data());
        room.sendTo(encoded, targetUuid.get());

        logger.debug("Handled DIRECT from {} to {} in room '{}'",
                r.uuid(), direct.targetId(), direct.room());
    }

    private void handleTyping(AtmosphereResource r, RoomProtocolMessage.Typing typing) {
        var room = roomManager.room(typing.room());
        var memberId = room.memberOf(r).map(RoomMember::id).orElse(null);
        var encoded = RoomProtocolCodec.encodeTyping(typing.room(), memberId, typing.typing());
        room.broadcast(encoded, r);

        logger.debug("Handled TYPING ({}) from {} in room '{}'",
                typing.typing(), r.uuid(), typing.room());
    }

    /**
     * Arrange for the remaining members to receive a wire {@code presence/leave}
     * when {@code r}'s connection drops without a leave frame — a closed tab or
     * a lost network, i.e. most real departures. DefaultRoom already untracks
     * the resource on disconnect, but only server-side {@link PresenceEvent}
     * listeners heard about it, so every other member's presence view (the
     * Console's "N online" chip, atmosphere.js {@code AtmosphereRooms}) kept
     * counting a member that was gone. A re-join on the same connection keeps
     * the one announcer and refreshes the member identity it announces.
     */
    private void announceDropOf(AtmosphereResource r, String roomName, Room room, RoomMember member) {
        // Any other way the resource leaves the room (the broadcaster dropping
        // it, a server-side room.leave()) announces too, and retires the
        // entry, so a connection whose lifecycle never reports a disconnect
        // does not strand one here.
        if (watchedRooms.add(room)) {
            room.onPresence(event -> {
                if (event.type() == PresenceEvent.Type.LEAVE && event.member() != null) {
                    var pending = dropAnnouncers.get(membershipKey(event.member(), roomName));
                    if (pending != null) {
                        pending.announce();
                    }
                }
            });
        }
        var key = membershipKey(r, roomName);
        var announcer = dropAnnouncers.computeIfAbsent(key, k -> {
            var created = new DropAnnouncer(k, roomName, room, r);
            r.addEventListener(created);
            return created;
        });
        announcer.member = member;
    }

    private static String membershipKey(AtmosphereResource r, String roomName) {
        return r.uuid() + '\u0000' + roomName;
    }

    /** Announces a dropped connection's departure; fires at most once. */
    private final class DropAnnouncer extends AtmosphereResourceEventListenerAdapter {
        private final String key;
        private final String roomName;
        private final Room room;
        private final AtmosphereResource resource;
        private volatile RoomMember member;

        DropAnnouncer(String key, String roomName, Room room, AtmosphereResource resource) {
            this.key = key;
            this.roomName = roomName;
            this.room = room;
            this.resource = resource;
        }

        @Override
        public void onDisconnect(AtmosphereResourceEvent event) {
            announce();
        }

        @Override
        public void onClose(AtmosphereResourceEvent event) {
            announce();
        }

        void announce() {
            // remove(key, this) is the close-once guard: onDisconnect, onClose and
            // the room's LEAVE event can each fire, and an explicit leave
            // already removed the entry.
            if (!dropAnnouncers.remove(key, this)) {
                return;
            }
            resource.removeEventListener(this);
            try {
                room.broadcast(RoomProtocolCodec.encodePresence(roomName, "leave", member), resource);
                logger.debug("Announced dropped connection {} leaving room '{}'", resource.uuid(), roomName);
            } catch (RuntimeException e) {
                // A room destroyed under the connection has nobody left to tell.
                logger.debug("Could not announce {} leaving room '{}'", resource.uuid(), roomName, e);
            }
        }
    }

    private boolean authorize(AtmosphereResource r, String roomName, RoomAction action) {
        if (authorizer == null) {
            return true;
        }
        try {
            return authorizer.authorize(r, roomName, action);
        } catch (Exception e) {
            logger.warn("Authorization error for {} in room '{}': {}",
                    r.uuid(), roomName, e.getMessage());
            return false;
        }
    }

    private Optional<String> resolveTargetUuid(Room room, String memberId) {
        for (var entry : room.memberInfo().entrySet()) {
            if (entry.getValue().id().equals(memberId)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    private void replayCachedMessages(AtmosphereResource r, Room room) {
        if (!(room instanceof DefaultRoom defaultRoom) || defaultRoom.historySize() <= 0) {
            return;
        }
        var cache = defaultRoom.broadcaster().getBroadcasterConfig().getBroadcasterCache();
        if (cache == null) {
            return;
        }
        var cached = cache.retrieveFromCache(defaultRoom.broadcaster().getID(), r.uuid());
        if (cached != null) {
            for (var msg : cached) {
                sendToResource(r, msg.toString());
            }
        }
    }

    private void sendToResource(AtmosphereResource r, String message) {
        try {
            r.getResponse().write(message);
            r.getResponse().flushBuffer();
        } catch (IOException e) {
            logger.warn("Failed to send message to {}: {}", r.uuid(), e.getMessage());
        }
    }

    private void sendError(AtmosphereResource r, String room, String message) {
        sendToResource(r, RoomProtocolCodec.encodeError(room, message));
    }

    private String readBody(AtmosphereResource r, AtmosphereRequest request) {
        // Try WebSocket body first
        var body = request.body();
        if (body != null && body.hasString()) {
            return body.asString();
        }

        // Fall back to reading from input stream (HTTP).
        //
        // Reading consumes the stream, so the content has to be written back for
        // whoever runs next. Without that, this interceptor drains the body and a
        // downstream @Message handler re-reads a closed stream and fails with
        // "Stream closed" — which means a chat message sent over long-polling or
        // SSE never reaches the annotated method at all, while the same message
        // over WebSocket works because there the body is already cached.
        // HeartbeatInterceptor does the same read-then-restore for the same reason.
        try {
            var sb = IOUtils.readEntirelyAsString(r);
            if (sb.length() == 0) {
                return null;
            }
            var content = sb.toString();
            request.body(content);
            return content;
        } catch (IOException e) {
            logger.debug("Failed to read request body: {}", e.getMessage());
            return null;
        }
    }

    private void scanAuthorizer(AtmosphereConfig config) {
        for (var entry : config.handlers().entrySet()) {
            var handlerClass = entry.getValue().atmosphereHandler().getClass();
            var auth = handlerClass.getAnnotation(RoomAuth.class);
            if (auth != null) {
                try {
                    this.authorizer = config.framework()
                            .newClassInstance(RoomAuthorizer.class, auth.authorizer());
                    logger.info("Using RoomAuthorizer: {}", auth.authorizer().getName());
                } catch (Exception e) {
                    logger.error("Failed to instantiate RoomAuthorizer: {}",
                            auth.authorizer().getName(), e);
                }
                break;
            }
        }
    }

    @Override
    public String toString() {
        return "RoomProtocolInterceptor";
    }
}
