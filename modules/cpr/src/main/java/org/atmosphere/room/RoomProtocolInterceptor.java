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
import java.util.Map;
import java.util.Optional;

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
    private AtmosphereConfig config;
    private RoomAuthorizer authorizer;

    @Override
    public void configure(AtmosphereConfig config) {
        this.config = config;
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

        // Replay cached messages if history is enabled
        replayCachedMessages(r, room);

        logger.debug("Handled JOIN for {} in room '{}'", r.uuid(), join.room());
    }

    private void handleLeave(AtmosphereResource r, RoomProtocolMessage.Leave leave) {
        var room = roomManager.room(leave.room());

        // Look up member info before leaving (for presence broadcast)
        var member = room.memberOf(r).orElse(null);

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
        var encoded = RoomProtocolCodec.encodeMessage(broadcast.room(), fromId, broadcast.data());
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

        // Fall back to reading from input stream (HTTP)
        try {
            var sb = IOUtils.readEntirelyAsString(r);
            return sb.length() > 0 ? sb.toString() : null;
        } catch (IOException e) {
            logger.debug("Failed to read request body: {}", e.getMessage());
            return null;
        }
    }

    private void scanAuthorizer(AtmosphereConfig config) {
        for (var entry : config.handlers().entrySet()) {
            var handlerClass = entry.getValue().atmosphereHandler.getClass();
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
