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
package org.atmosphere.session;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.interceptor.InvokationOrder;
import org.atmosphere.room.Room;
import org.atmosphere.room.RoomManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Interceptor that saves and restores session state across server restarts.
 *
 * <p>On each new connection, if the client sends an
 * {@code X-Atmosphere-Session-Token} header, this interceptor looks up the
 * stored session and re-joins the resource to its previous rooms and
 * broadcasters. If no token is present, a new durable session is created
 * and the token is sent back in the response header.</p>
 *
 * <p>Session state is periodically saved and expired sessions are cleaned
 * up on a background thread.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // In application.properties (Spring Boot)
 * atmosphere.durable-sessions.enabled=true
 *
 * // Or register programmatically
 * framework.interceptor(new DurableSessionInterceptor(store));
 * }</pre>
 */
public class DurableSessionInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(DurableSessionInterceptor.class);

    /** Header sent by the client to identify a previous session. */
    public static final String SESSION_TOKEN_HEADER = "X-Atmosphere-Session-Token";

    /** Header sent by the server with the assigned session token. */
    public static final String SESSION_TOKEN_RESPONSE_HEADER = "X-Atmosphere-Session-Token";

    private final SessionStore store;
    private final Duration sessionTtl;
    private final Duration saveInterval;
    private ScheduledExecutorService scheduler;
    private AtmosphereConfig config;

    public DurableSessionInterceptor(SessionStore store) {
        this(store, Duration.ofHours(24), Duration.ofMinutes(1));
    }

    public DurableSessionInterceptor(SessionStore store, Duration sessionTtl, Duration saveInterval) {
        this.store = store;
        this.sessionTtl = sessionTtl;
        this.saveInterval = saveInterval;
    }

    @Override
    public void configure(AtmosphereConfig config) {
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = Thread.ofVirtual().unstarted(r);
            t.setName("atmosphere-durable-session-cleanup");
            return t;
        });

        // Periodically clean up expired sessions
        scheduler.scheduleAtFixedRate(() -> {
            try {
                var expired = store.removeExpired(sessionTtl);
                if (!expired.isEmpty()) {
                    logger.debug("Cleaned up {} expired durable sessions", expired.size());
                }
            } catch (Exception e) {
                logger.warn("Failed to clean up expired sessions", e);
            }
        }, saveInterval.toSeconds(), saveInterval.toSeconds(), TimeUnit.SECONDS);

        logger.info("Durable sessions enabled (ttl={}, cleanup={})", sessionTtl, saveInterval);
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        var request = r.getRequest();
        var response = r.getResponse();

        var token = request.getHeader(SESSION_TOKEN_HEADER);
        if (token == null) {
            token = request.getParameter("X-Atmosphere-Session-Token");
        }

        if (token != null) {
            // Attempt to restore a previous session
            var restored = store.restore(token);
            if (restored.isPresent()) {
                var session = restored.get();
                logger.info("Restoring durable session {} for resource {} (was {})",
                        token, r.uuid(), session.resourceId());

                restoreSession(r, session);

                // Update the session with the new resource ID
                store.save(session.withResourceId(r.uuid()));
                response.setHeader(SESSION_TOKEN_RESPONSE_HEADER, token);
                registerSaveOnDisconnect(r, token);
                return Action.CONTINUE;
            }
            logger.debug("Session token {} not found or expired, creating new session", token);
        }

        // Create a new durable session
        var newToken = UUID.randomUUID().toString();
        var session = DurableSession.create(newToken, r.uuid());
        store.save(session);
        response.setHeader(SESSION_TOKEN_RESPONSE_HEADER, newToken);

        logger.debug("Created new durable session {} for resource {}", newToken, r.uuid());

        registerSaveOnDisconnect(r, newToken);
        return Action.CONTINUE;
    }

    /**
     * Restore room and broadcaster memberships from a saved session.
     */
    private void restoreSession(AtmosphereResource r, DurableSession session) {
        // Restore broadcaster subscriptions
        var factory = config.getBroadcasterFactory();
        for (var broadcasterId : session.broadcasters()) {
            var broadcaster = factory.lookup(broadcasterId, false);
            if (broadcaster != null) {
                broadcaster.addAtmosphereResource(r);
                logger.debug("Restored resource {} to broadcaster {}", r.uuid(), broadcasterId);
            }
        }

        // Restore room memberships
        var roomManager = config.framework().getAtmosphereConfig()
                .getServletContext().getAttribute(RoomManager.class.getName());
        if (roomManager instanceof RoomManager rm) {
            for (var roomName : session.rooms()) {
                rm.room(roomName).join(r);
                logger.debug("Restored resource {} to room {}", r.uuid(), roomName);
            }
        }
    }

    /**
     * Save the current session state when the resource disconnects,
     * so it can be restored on reconnect.
     */
    private void registerSaveOnDisconnect(AtmosphereResource r, String token) {
        r.addEventListener(new AtmosphereResourceEventListenerAdapter() {
            @Override
            public void onDisconnect(org.atmosphere.cpr.AtmosphereResourceEvent event) {
                saveCurrentState(event.getResource(), token);
            }

            @Override
            public void onClose(org.atmosphere.cpr.AtmosphereResourceEvent event) {
                saveCurrentState(event.getResource(), token);
            }
        });
    }

    /**
     * Capture current rooms and broadcasters into the session store.
     */
    void saveCurrentState(AtmosphereResource r, String token) {
        try {
            var session = store.restore(token);
            if (session.isEmpty()) {
                return;
            }

            var current = session.get();

            // Capture broadcaster IDs
            Set<String> broadcasterIds = new LinkedHashSet<>();
            for (var broadcaster : r.broadcasters()) {
                broadcasterIds.add(broadcaster.getID());
            }

            // Capture room names
            Set<String> roomNames = new LinkedHashSet<>();
            var roomManager = config.framework().getAtmosphereConfig()
                    .getServletContext().getAttribute(RoomManager.class.getName());
            if (roomManager instanceof RoomManager rm) {
                for (var room : rm.all()) {
                    if (room.members().contains(r)) {
                        roomNames.add(room.name());
                    }
                }
            }

            var updated = current
                    .withBroadcasters(broadcasterIds)
                    .withRooms(roomNames);

            store.save(updated);
            logger.debug("Saved durable session {} (rooms={}, broadcasters={})",
                    token, roomNames, broadcasterIds);
        } catch (Exception e) {
            logger.warn("Failed to save durable session state for {}", token, e);
        }
    }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.BEFORE_DEFAULT;
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        store.close();
    }
}
