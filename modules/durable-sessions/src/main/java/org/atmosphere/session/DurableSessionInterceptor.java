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
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.interceptor.AllowInterceptor;
import org.atmosphere.interceptor.InvokationOrder;
import org.atmosphere.room.RoomManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Interceptor that saves and restores session state across server restarts.
 * Uses the {@code X-Atmosphere-Session-Token} header to identify returning clients
 * and re-join them to their previous rooms and broadcasters.
 */
public class DurableSessionInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(DurableSessionInterceptor.class);

    /** Header sent by the client to identify a previous session. */
    public static final String SESSION_TOKEN_HEADER = "X-Atmosphere-Session-Token";

    /** Header sent by the server with the assigned session token. */
    public static final String SESSION_TOKEN_RESPONSE_HEADER = "X-Atmosphere-Session-Token";

    /**
     * Header sent by a reconnecting client that wants to reattach to a
     * specific in-flight run (AgentResumeHandle — Primitive 8). The
     * interceptor records the value in request attributes so downstream
     * handlers can consult the run registry without re-parsing headers.
     */
    public static final String RUN_ID_HEADER = "X-Atmosphere-Run-Id";

    /** Request attribute where the resolved run id is stashed for downstream handlers. */
    public static final String RUN_ID_ATTRIBUTE = "org.atmosphere.session.runId";

    private final SessionStore store;
    private final Duration sessionTtl;
    private final long sessionTtlNanos;
    private final Duration saveInterval;

    /**
     * The durable session of each live connection, by resource uuid. A long-polling
     * client sends its {@code X-Atmosphere-tracking-id} on every poll, so every poll
     * is a new resource with the same uuid: a poll without the session token finds
     * its connection's session here instead of creating one per poll.
     *
     * <p>An entry names the registration of the resource that holds the connection:
     * the last one suspended. Only that resource's disconnect saves the connection
     * and removes the entry; a request that never suspends (a POST of a message) or
     * an older poll the runtime closes when a fresh one replaces it leaves it. The
     * entry's session is fixed until the connection ends: a request presenting
     * another token for a live connection gets that session for itself, and cannot
     * make the connection's later requests feed it.</p>
     *
     * <p>Bounded by the live connections: an entry goes when its connection's state
     * is saved on disconnect, and {@link #sweep()} drops the entries of connections
     * not seen for {@code sessionTtl} (a long-polling client that stops polling never
     * fires a disconnect callback) and of sessions the store expired.</p>
     */
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final AtomicLong registrations = new AtomicLong();

    /**
     * Serializes the read-modify-write of one session. A lock lives in the map only
     * while a thread holds it or waits on it; see {@link #lockSession(String)}.
     */
    private final ConcurrentHashMap<String, SessionLock> sessionLocks = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private AtmosphereConfig config;

    public DurableSessionInterceptor(SessionStore store) {
        this(store, Duration.ofHours(24), Duration.ofMinutes(1));
    }

    public DurableSessionInterceptor(SessionStore store, Duration sessionTtl, Duration saveInterval) {
        this.store = store;
        this.sessionTtl = sessionTtl;
        this.sessionTtlNanos = saturatedNanos(sessionTtl);
        this.saveInterval = saveInterval;
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            logger.trace("Session TTL {} exceeds the nanosecond range", duration, e);
            return Long.MAX_VALUE;
        }
    }

    @Override
    public void configure(AtmosphereConfig config) {
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = Thread.ofPlatform().daemon().unstarted(r);
            t.setName("atmosphere-durable-session-cleanup");
            return t;
        });

        // Periodically clean up expired sessions
        scheduler.scheduleAtFixedRate(this::sweep,
                saveInterval.toSeconds(), saveInterval.toSeconds(), TimeUnit.SECONDS);

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

        // Capture the run id header if present so downstream handlers can
        // reattach to an in-flight AgentResumeHandle via the RunRegistry.
        // We do not consult the registry here — this interceptor stays
        // free of the atmosphere-ai dependency by stashing the raw id as
        // a request attribute. The ai module's own interceptor / handler
        // consumes it.
        var runId = request.getHeader(RUN_ID_HEADER);
        if (runId == null) {
            runId = request.getParameter("X-Atmosphere-Run-Id");
        }
        if (runId != null && !runId.isBlank()) {
            request.setAttribute(RUN_ID_ATTRIBUTE, runId);
            logger.debug("Reconnect with run id {} for resource {}", runId, r.uuid());
        }

        // The session belongs to the WebSocket connection, created or restored when
        // it opened: a message frame must not create another one, nor register
        // another save-on-disconnect listener on the same resource.
        if (request.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE) != null) {
            return Action.CONTINUE;
        }

        if (token == null) {
            // A request of a live connection that did not send the token: a
            // long-polling client polling before it read the token, or a client
            // that never reads it. It keeps its connection's session. The uuid
            // is the client's X-Atmosphere-tracking-id, which the runtime already
            // trusts to name the connection, so this path only goes as far as
            // that trust does: it neither sends the token back nor restores the
            // session's rooms and broadcasters, both of which need the token.
            var known = connections.get(r.uuid());
            if (known != null) {
                try {
                    store.touch(known.token());
                } catch (Exception e) {
                    logger.warn("Failed to touch durable session {}", known.token(), e);
                }
                logger.trace("Request of resource {} keeps durable session {}", r.uuid(), known.token());
                registerSaveOnDisconnect(r, known.token());
                return Action.CONTINUE;
            }
        } else {
            // Attempt to restore a previous session
            var lock = lockSession(token);
            try {
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
            } catch (Exception e) {
                logger.warn("Failed to restore durable session {}, creating new session", token, e);
            } finally {
                unlockSession(token, lock);
            }
            logger.debug("Session token {} not found or expired, creating new session", token);
        }

        // Create a new durable session
        var newToken = UUID.randomUUID().toString();
        var session = DurableSession.create(newToken, r.uuid());
        try {
            store.save(session);
        } catch (Exception e) {
            logger.warn("Failed to save new durable session {}", newToken, e);
        }
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
                try {
                    rm.room(roomName).join(r);
                    logger.debug("Restored resource {} to room {}", r.uuid(), roomName);
                } catch (Exception e) {
                    logger.warn("Failed to restore resource {} to room {}", r.uuid(), roomName, e);
                }
            }
        }
    }

    /**
     * Save the current session state when the resource disconnects,
     * so it can be restored on reconnect.
     */
    private void registerSaveOnDisconnect(AtmosphereResource r, String token) {
        var registration = registrations.incrementAndGet();
        var now = System.nanoTime();
        // A connection without an entry is held by this resource. Otherwise the
        // resource holding it keeps it until this one suspends, and a request
        // presenting another session's token never takes it over.
        connections.compute(r.uuid(), (uuid, connection) -> {
            if (connection == null) {
                return new Connection(token, registration, now);
            }
            if (connection.token().equals(token)) {
                return connection.seen(now);
            }
            logger.debug("Resource {} presented session {} but its connection uses another session",
                    uuid, token);
            return connection;
        });
        r.addEventListener(new SaveOnDisconnect(token, registration));
    }

    /**
     * Makes its resource hold the connection when it suspends, and saves the
     * connection when it goes. It is an {@link AllowInterceptor}: the
     * {@code SuspendTrackerInterceptor} hides onSuspend from the other listeners
     * of every request of a connection after the first, and each long-poll is a
     * new resource that must take the connection over.
     */
    private final class SaveOnDisconnect extends AtmosphereResourceEventListenerAdapter
            implements AllowInterceptor {

        private final String token;
        private final long registration;

        SaveOnDisconnect(String token, long registration) {
            this.token = token;
            this.registration = registration;
        }

        @Override
        public void onSuspend(AtmosphereResourceEvent event) {
            connections.compute(event.getResource().uuid(), (uuid, connection) ->
                    connection == null || connection.token().equals(token)
                            ? new Connection(token, registration, System.nanoTime())
                            : connection);
        }

        @Override
        public void onDisconnect(AtmosphereResourceEvent event) {
            saveOnDisconnect(event.getResource(), token, registration);
        }

        @Override
        public void onClose(AtmosphereResourceEvent event) {
            saveOnDisconnect(event.getResource(), token, registration);
        }
    }

    /**
     * Save the connection's state once, when the resource holding it goes. Both
     * onDisconnect and onClose can fire for a connection: only the callback that
     * removes the connection's entry saves, so the connection is saved once. A
     * client that reconnects with the same tracking id is a new entry, saved again.
     */
    private void saveOnDisconnect(AtmosphereResource r, String token, long registration) {
        var removed = new AtomicBoolean();
        connections.computeIfPresent(r.uuid(), (uuid, connection) -> {
            if (connection.registration() != registration) {
                return connection;
            }
            removed.set(true);
            return null;
        });
        if (removed.get()) {
            saveCurrentState(r, token);
        }
    }

    /**
     * Capture current rooms and broadcasters into the session store.
     */
    void saveCurrentState(AtmosphereResource r, String token) {
        var lock = lockSession(token);
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
        } finally {
            unlockSession(token, lock);
        }
    }

    /**
     * Lock one session. The lock is reference counted rather than kept per token
     * forever, or shared through a fixed array of stripes: the critical section
     * runs store I/O (SQLite, Redis), and stripes would serialize unrelated
     * sessions during a reconnect storm after a restart. The count changes only
     * inside {@code compute}/{@code computeIfPresent} on the token's key, which
     * are atomic per key, and it is taken before locking and released after
     * unlocking: while any thread holds or waits on the lock the entry stays, so
     * every thread on a token gets the same lock, and the last one removes it.
     */
    private SessionLock lockSession(String token) {
        var lock = sessionLocks.compute(token, (k, existing) -> {
            var l = existing == null ? new SessionLock() : existing;
            l.users++;
            return l;
        });
        lock.lock.lock();
        return lock;
    }

    private void unlockSession(String token, SessionLock lock) {
        try {
            lock.lock.unlock();
        } finally {
            sessionLocks.computeIfPresent(token, (k, l) -> --l.users == 0 ? null : l);
        }
    }

    /**
     * Remove the sessions the store expired, and forget the connections not seen
     * for the session TTL along with those of the expired sessions.
     */
    void sweep() {
        try {
            var expired = store.removeExpired(sessionTtl);
            if (!expired.isEmpty()) {
                logger.debug("Cleaned up {} expired durable sessions", expired.size());
                var tokens = expired.stream().map(DurableSession::token).collect(Collectors.toSet());
                connections.values().removeIf(c -> tokens.contains(c.token()));
            }
        } catch (Exception e) {
            logger.warn("Failed to clean up expired sessions", e);
        }
        var now = System.nanoTime();
        connections.values().removeIf(c -> now - c.seenNanos() > sessionTtlNanos);
    }

    /** Number of session locks held or waited on, for tests. */
    int sessionLockCount() {
        return sessionLocks.size();
    }

    /** Number of connections whose session is tracked, for tests. */
    int trackedConnectionCount() {
        return connections.size();
    }

    /**
     * The session a live connection uses, the registration of the resource that
     * holds the connection, and when a request of the connection was last seen.
     */
    private record Connection(String token, long registration, long seenNanos) {

        Connection seen(long nanos) {
            return new Connection(token, registration, nanos);
        }
    }

    private static final class SessionLock {
        private final ReentrantLock lock = new ReentrantLock();
        /** Threads holding or waiting on {@link #lock}; changed only under the map's per-key lock. */
        private int users;
    }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.BEFORE_DEFAULT;
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        connections.clear();
        store.close();
    }
}
