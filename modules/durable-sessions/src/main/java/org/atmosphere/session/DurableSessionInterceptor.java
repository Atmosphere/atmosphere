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
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereFrameworkListener;
import org.atmosphere.cpr.AtmosphereFrameworkListenerAdapter;
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

import java.io.IOException;
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
 *
 * <p>The token reaches the client as the {@value #SESSION_TOKEN_RESPONSE_HEADER}
 * response header. A WebSocket client cannot read the headers of the upgrade
 * response, so one that asks for it with {@value #SESSION_TOKEN_INBAND_PARAM}{@code =true}
 * (atmosphere.js does) also gets the token in band, as one
 * {@value #SESSION_TOKEN_FRAME_PREFIX}{@code <token>} frame written once the
 * connection is suspended. A client that does not ask never gets that frame.</p>
 *
 * <p>A restored session re-joins its broadcasters and rooms when the new
 * resource suspends, not while the request is inspected: a broadcaster adds a
 * resource that is not suspended yet all the same, but warns that it is not
 * suspended and that messages its cache holds may then be delivered
 * unexpectedly. Until the restored resource has re-joined, its connection does
 * not save its state: it holds none of the restored broadcasters and rooms yet,
 * and saving would overwrite the stored session with that empty state. The state
 * of the connections still open when the framework stops is saved before the
 * framework closes them, since closing a resource first removes it from its
 * broadcasters.</p>
 */
public class DurableSessionInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(DurableSessionInterceptor.class);

    /** Header sent by the client to identify a previous session. */
    public static final String SESSION_TOKEN_HEADER = "X-Atmosphere-Session-Token";

    /** Header sent by the server with the assigned session token. */
    public static final String SESSION_TOKEN_RESPONSE_HEADER = "X-Atmosphere-Session-Token";

    /**
     * Request header or query parameter a WebSocket client sends, with the value
     * {@code true}, to get the session token in band: it cannot read the upgrade
     * response's {@link #SESSION_TOKEN_RESPONSE_HEADER}.
     */
    public static final String SESSION_TOKEN_INBAND_PARAM = "X-Atmosphere-Session-Token-Inband";

    /**
     * Prefix of the in-band frame carrying the session token: the frame is this
     * prefix followed by the token, and nothing else.
     */
    public static final String SESSION_TOKEN_FRAME_PREFIX = "X-Atmosphere-Session-Token=";

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
     * the last one that started to suspend. Only that resource's disconnect saves the
     * connection and removes the entry; a request that never suspends (a POST of a
     * message) or an older poll the runtime closes when a fresh one replaces it leaves
     * it. The fresh poll takes the connection over before the runtime closes the older
     * one, and whether or not it then suspends: a poll answered straight from the
     * {@code BroadcasterCache} is resumed without ever suspending, and never fires a
     * disconnect. The older poll therefore still saves the connection's state when
     * the runtime closes it, but leaves the entry to the poll that replaced it. The
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
    /**
     * Set when the framework starts to stop: the state of the open connections
     * has been saved, and the close the framework then gives every resource
     * (which first removes it from its broadcasters) must not overwrite it.
     */
    private final AtomicBoolean stopping = new AtomicBoolean();
    /** Saves the open connections before the framework closes them; removed on {@link #destroy()}. */
    private final AtmosphereFrameworkListener saveOnStop = new AtmosphereFrameworkListenerAdapter() {
        @Override
        public void onPreDestroy(AtmosphereFramework f) {
            saveOpenConnections();
        }
    };

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

        if (config.framework() != null) {
            config.framework().frameworkListener(saveOnStop);
        }

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
                registerSaveOnDisconnect(r, known.token(), null);
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

                    // Update the session with the new resource ID
                    store.save(session.withResourceId(r.uuid()));
                    response.setHeader(SESSION_TOKEN_RESPONSE_HEADER, token);
                    // Until the restore ran, the resource holds none of the
                    // session's broadcasters and rooms: a save then would
                    // overwrite the stored session with that empty state.
                    var restorePending = new AtomicBoolean(true);
                    registerSaveOnDisconnect(r, token, restorePending);
                    // Re-join once the resource is suspended: a broadcaster adds
                    // a resource that is not suspended yet, but warns that
                    // messages its cache holds may then be delivered unexpectedly.
                    r.addEventListener(new OnSuspendOnce(r, session, inbandToken(r) ? token : null,
                            restorePending));
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

        registerSaveOnDisconnect(r, newToken, null);
        if (inbandToken(r)) {
            r.addEventListener(new OnSuspendOnce(r, null, newToken, null));
        }
        return Action.CONTINUE;
    }

    /**
     * Whether the token goes in band as well: a WebSocket connection that asked
     * for it. A client that did not ask must not get a frame it would hand to the
     * application as a message.
     */
    private static boolean inbandToken(AtmosphereResource r) {
        if (r.transport() != AtmosphereResource.TRANSPORT.WEBSOCKET) {
            return false;
        }
        var request = r.getRequest();
        var asked = request.getHeader(SESSION_TOKEN_INBAND_PARAM);
        if (asked == null) {
            asked = request.getParameter(SESSION_TOKEN_INBAND_PARAM);
        }
        return "true".equalsIgnoreCase(asked);
    }

    /**
     * Once the resource is suspended: re-joins a restored session's broadcasters
     * and rooms, then writes the in-band token frame when the client asked for
     * it. An {@link AllowInterceptor}, like {@link SaveOnDisconnect}, so the
     * {@code SuspendTrackerInterceptor} lets the suspend callback through.
     */
    private final class OnSuspendOnce extends AtmosphereResourceEventListenerAdapter
            implements AllowInterceptor {

        private final AtmosphereResource resource;
        /** The session to re-join, or {@code null} for a new one. */
        private final DurableSession restored;
        /** The token to write in band, or {@code null} when the client did not ask. */
        private final String inband;
        /** Set until {@link #restored} is re-joined, or {@code null} for a new session. */
        private final AtomicBoolean restorePending;
        private final AtomicBoolean done = new AtomicBoolean();

        OnSuspendOnce(AtmosphereResource resource, DurableSession restored, String inband,
                      AtomicBoolean restorePending) {
            this.resource = resource;
            this.restored = restored;
            this.inband = inband;
            this.restorePending = restorePending;
        }

        @Override
        public void onSuspend(AtmosphereResourceEvent event) {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            try {
                if (restored != null) {
                    restoreSession(resource, restored);
                    if (restorePending != null) {
                        restorePending.set(false);
                    }
                }
                if (inband != null) {
                    writeToken(resource, inband);
                }
            } finally {
                resource.removeEventListener(this);
            }
        }
    }

    private static void writeToken(AtmosphereResource r, String token) {
        try {
            var response = r.getResponse();
            response.write(SESSION_TOKEN_FRAME_PREFIX + token);
            response.flushBuffer();
        } catch (IOException | RuntimeException e) {
            logger.warn("Failed to send durable session token to resource {}", r.uuid(), e);
        }
    }

    /**
     * Save the state of every connection still open, before the framework closes
     * its resources: the close removes a resource from its broadcasters first, so
     * a save on that close would record none (Correctness Invariant #2: the stop
     * path leaves each session as it was).
     */
    void saveOpenConnections() {
        if (!stopping.compareAndSet(false, true) || config == null) {
            return;
        }
        var factory = config.resourcesFactory();
        if (factory != null) {
            for (var entry : connections.entrySet()) {
                var connection = entry.getValue();
                if (connection.restorePending()) {
                    // Not re-joined yet: the stored session is still the whole state.
                    logger.trace("Not saving durable session {}: its restore has not run", connection.token());
                    continue;
                }
                factory.findResource(entry.getKey())
                        .ifPresent(resource -> captureState(resource, connection.token()));
            }
        }
        logger.debug("Saved {} open durable session(s) before stopping", connections.size());
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
     *
     * @param restorePending set while a restored session has not re-joined its
     *                       broadcasters and rooms on {@code r} yet (no save
     *                       then), or {@code null} when there is nothing to restore
     */
    private void registerSaveOnDisconnect(AtmosphereResource r, String token, AtomicBoolean restorePending) {
        var registration = registrations.incrementAndGet();
        var now = System.nanoTime();
        // A connection without an entry is held by this resource. Otherwise the
        // resource holding it keeps it until this one starts to suspend, and a
        // request presenting another session's token never takes it over.
        connections.compute(r.uuid(), (uuid, connection) -> {
            if (connection == null) {
                return new Connection(token, registration, now, restorePending);
            }
            if (connection.token().equals(token)) {
                return connection.seen(now);
            }
            logger.debug("Resource {} presented session {} but its connection uses another session",
                    uuid, token);
            return connection;
        });
        r.addEventListener(new SaveOnDisconnect(token, registration, restorePending));
    }

    /**
     * Makes its resource hold the connection when it starts to suspend, and saves
     * the connection when it goes. It is an {@link AllowInterceptor}: the
     * {@code SuspendTrackerInterceptor} hides the suspend callbacks from the other
     * listeners of every request of a connection after the first, and each
     * long-poll is a new resource that must take the connection over.
     *
     * <p>The take-over happens on onPreSuspend: a fresh poll replaces an older
     * one still held (a dead connection not detected yet) inside its own suspend,
     * before onSuspend, and the runtime closes the older poll right there. A
     * poll answered from the {@code BroadcasterCache} never reaches onSuspend at
     * all. Taking over only on onSuspend let the older poll's close end the
     * connection, and the client's next poll without the token open a second
     * durable session. The replaced poll still saves the connection's state when
     * it goes, once, because the poll that replaced it may never fire a
     * disconnect.</p>
     */
    private final class SaveOnDisconnect extends AtmosphereResourceEventListenerAdapter
            implements AllowInterceptor {

        private final String token;
        private final long registration;
        /** Set until a restored session re-joined on this resource, or {@code null}. */
        private final AtomicBoolean restorePending;
        /** Whether this resource held the connection at some point. */
        private volatile boolean held;
        /** Saves once when this resource goes after a newer one took the connection over. */
        private final AtomicBoolean savedReplaced = new AtomicBoolean();

        SaveOnDisconnect(String token, long registration, AtomicBoolean restorePending) {
            this.token = token;
            this.registration = registration;
            this.restorePending = restorePending;
        }

        @Override
        public void onPreSuspend(AtmosphereResourceEvent event) {
            holdConnection(event);
        }

        @Override
        public void onSuspend(AtmosphereResourceEvent event) {
            holdConnection(event);
        }

        private void holdConnection(AtmosphereResourceEvent event) {
            connections.compute(event.getResource().uuid(), (uuid, connection) -> {
                if (connection == null || connection.token().equals(token)) {
                    held = true;
                    return new Connection(token, registration, System.nanoTime(), restorePending);
                }
                return connection;
            });
        }

        @Override
        public void onDisconnect(AtmosphereResourceEvent event) {
            connectionGoes(event.getResource());
        }

        @Override
        public void onClose(AtmosphereResourceEvent event) {
            connectionGoes(event.getResource());
        }

        private void connectionGoes(AtmosphereResource r) {
            // A restored resource that goes before it re-joined its session holds
            // none of the session's broadcasters and rooms: the stored session is
            // left as it is rather than overwritten with that empty state.
            var save = restorePending == null || !restorePending.get();
            if (!save) {
                logger.debug("Resource {} went before durable session {} was restored: not saving it",
                        r.uuid(), token);
            }
            if (saveOnDisconnect(r, token, registration, save) && held && save
                    && savedReplaced.compareAndSet(false, true)) {
                // The poll that took the connection over may never fire a disconnect
                // (one answered from the BroadcasterCache): save now, and leave it the entry.
                saveCurrentState(r, token);
            }
        }
    }

    /**
     * Save the connection's state once, when the resource holding it goes. Both
     * onDisconnect and onClose can fire for a connection: only the callback that
     * removes the connection's entry saves, so the connection is saved once. A
     * client that reconnects with the same tracking id is a new entry, saved again.
     *
     * @param save whether to save the state when the entry is removed
     * @return whether a newer resource of the same session holds the connection
     */
    private boolean saveOnDisconnect(AtmosphereResource r, String token, long registration, boolean save) {
        var removed = new AtomicBoolean();
        var replaced = new AtomicBoolean();
        connections.computeIfPresent(r.uuid(), (uuid, connection) -> {
            if (connection.registration() != registration) {
                replaced.set(connection.token().equals(token));
                return connection;
            }
            removed.set(true);
            return null;
        });
        if (removed.get() && save) {
            saveCurrentState(r, token);
        }
        return replaced.get();
    }

    /**
     * Capture current rooms and broadcasters into the session store.
     */
    void saveCurrentState(AtmosphereResource r, String token) {
        if (stopping.get()) {
            // The framework is stopping and saved this connection already; the
            // close it now gives the resource has removed it from its broadcasters.
            logger.trace("Not saving durable session {} on the stop path", token);
            return;
        }
        captureState(r, token);
    }

    private void captureState(AtmosphereResource r, String token) {
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
    private record Connection(String token, long registration, long seenNanos, AtomicBoolean restorePendingFlag) {

        Connection seen(long nanos) {
            return new Connection(token, registration, nanos, restorePendingFlag);
        }

        /** Whether the holding resource has not re-joined its restored session yet. */
        boolean restorePending() {
            return restorePendingFlag != null && restorePendingFlag.get();
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
        // A framework that stops without onPreDestroy (an interceptor removed on
        // its own) still saves its open connections, once.
        saveOpenConnections();
        if (config != null && config.framework() != null) {
            config.framework().frameworkListeners().remove(saveOnStop);
        }
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
