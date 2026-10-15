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
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.HeaderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The long-polling clients of one {@code @AiEndpoint} whose connection is open,
 * so that {@code @Ready} runs once per client, as it runs once per WebSocket or
 * SSE connection, and not once per poll.
 *
 * <p>Every poll of a long-polling client is a request of its own under the
 * client's tracking id. The first poll of a tracking id opens the client's
 * connection: {@code @Ready} runs on it, and the request attributes it sets
 * there (an {@code ai.userId} read off the request, say) are kept. A later poll
 * continues that connection when it carries the same identity and presents the
 * same request (path, query parameters, {@code Authorization},
 * {@code X-Atmosphere-Auth} and {@code Cookie} headers): {@code @Ready} does not
 * run again, and the kept attributes are set on the poll's request, where it
 * does not carry them already, so a prompt dispatched to it reads them as it
 * would on a WebSocket connection's one request. A poll that differs in any of
 * these opens a connection of its own instead: the tracking id is a
 * client-presented bearer token, and what {@code @Ready} derived from one
 * request is never handed to another one. Framework and container attributes
 * ({@code org.atmosphere.*}, {@code jakarta.*}, {@code javax.*}) are not kept.
 * The headers are compared verbatim: a client whose {@code Cookie} header
 * changes from poll to poll (a cookie re-issued with a new value on every
 * response) opens a connection, and runs {@code @Ready}, on each such poll.</p>
 *
 * <p>Concurrent first polls of one tracking id open one connection: one runs
 * {@code @Ready}, the others wait for it (at most
 * {@value #OPENING_WAIT_MS} ms, after which they run {@code @Ready} untracked)
 * and then continue its connection, or open their own when they present
 * another request.</p>
 *
 * <p>A connection ends when the client departs: a poll of it is cancelled or
 * closed (the client closed it, or the application did) while it is the
 * client's latest poll — the late departure of a poll a newer one replaced
 * leaves the connection alone — or no poll of it is suspended and none arrived
 * within {@value #DEPARTURE_GRACE_MS_PARAM} (30 s) of the last one ending. A
 * client that comes back after that opens a new connection, and {@code @Ready}
 * runs again, as for a WebSocket client that reconnects.</p>
 *
 * <p>Bounded (Correctness Invariant #3): at most {@value #MAX_CLIENTS_PARAM}
 * (10,000) clients are tracked per endpoint, each with a digest of its first
 * request and the attributes {@code @Ready} set; only {@link #open} adds one,
 * under a lock, so the bound is never overshot. Opening a connection first
 * drops the clients that are gone, at most once a second; a client that still
 * finds no room is not tracked, and {@code @Ready} runs on each of its polls,
 * as it did before clients were tracked. A client's polls are held weakly: a
 * client that stops polling does not keep its last request in memory.</p>
 */
final class LongPollingClients {

    /** How long after its last poll ended a client with no poll suspended counts as gone, in ms. */
    static final String DEPARTURE_GRACE_MS_PARAM = "org.atmosphere.ai.longPolling.departureGraceMs";

    /** How many long-polling clients one endpoint tracks at once. */
    static final String MAX_CLIENTS_PARAM = "org.atmosphere.ai.longPolling.maxTrackedClients";

    static final long DEFAULT_DEPARTURE_GRACE_MS = 30_000;
    static final int DEFAULT_MAX_CLIENTS = 10_000;

    private static final long SWEEP_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** How long a first poll waits for a concurrent one of its tracking id to open the connection, in ms. */
    static final long OPENING_WAIT_MS = 5_000;

    /** Query parameters a client's polls may carry or not without being another request. */
    private static final String RUN_ID_PARAMETER = "X-Atmosphere-Run-Id";

    private static final Logger logger = LoggerFactory.getLogger(LongPollingClients.class);

    private record Limits(long graceNanos, int maxClients) {
    }

    /** A client's open connection: who opened it, from which request, and what {@code @Ready} set there. */
    private static final class Client {
        private final Object owner;
        private final byte[] request;
        private final Map<String, Object> readyAttributes;
        /** The client's latest poll, held weakly; only its departure ends the connection. */
        private volatile WeakReference<AtmosphereResource> poll;
        /** When a poll of the client last arrived or ended. */
        private volatile long seenNanos;

        private Client(Object owner, byte[] request, Map<String, Object> readyAttributes,
                       AtmosphereResource poll, long seenNanos) {
            this.owner = owner;
            this.request = request;
            this.readyAttributes = readyAttributes;
            this.poll = new WeakReference<>(poll);
            this.seenNanos = seenNanos;
        }

        private boolean isLatest(AtmosphereResource candidate) {
            return poll.get() == candidate;
        }
    }

    /**
     * Tracking id -> its open connection. Entries are added only by {@link #open},
     * under {@link #openLock}; they change inside the map's per-key compute and
     * leave there or in the sweep.
     */
    private final ConcurrentHashMap<String, Client> clients = new ConcurrentHashMap<>();
    /** Tracking id -> released once the poll opening its connection is done; one per poll in progress. */
    private final ConcurrentHashMap<String, CountDownLatch> opening = new ConcurrentHashMap<>();
    private final ReentrantLock openLock = new ReentrantLock();
    private volatile Limits limits;
    private volatile long lastSweepNanos = System.nanoTime() - SWEEP_INTERVAL_NANOS;

    /**
     * Run {@code onReady} for {@code poll}, a suspended long-polling poll of an
     * identity resolved as {@code owner}, when it opens its client's connection;
     * when it continues one, set the attributes {@code @Ready} kept on its
     * request instead.
     */
    void ready(AtmosphereResource poll, Object owner, Runnable onReady) {
        var trackingId = poll.uuid();
        var request = poll.getRequest();
        var fingerprint = trackingId != null ? fingerprint(request) : null;
        if (fingerprint == null) {
            onReady.run();
            return;
        }
        var l = limits(poll.getAtmosphereConfig());
        if (continues(trackingId, poll, owner, fingerprint, request, l)) {
            watch(trackingId, poll);
            return;
        }
        // One poll at a time opens a tracking id's connection: a concurrent
        // first poll waits for it, then continues that connection.
        var latch = new CountDownLatch(1);
        CountDownLatch prior;
        while ((prior = opening.putIfAbsent(trackingId, latch)) != null) {
            if (!awaitOpening(prior, trackingId)) {
                onReady.run();
                return;
            }
            if (continues(trackingId, poll, owner, fingerprint, request, l)) {
                watch(trackingId, poll);
                return;
            }
        }
        try {
            if (continues(trackingId, poll, owner, fingerprint, request, l)) {
                watch(trackingId, poll);
                return;
            }
            openConnection(trackingId, poll, owner, fingerprint, request, l, onReady);
        } finally {
            opening.remove(trackingId, latch);
            latch.countDown();
        }
    }

    /** Wait for the poll opening {@code trackingId}'s connection; {@code false} when it took too long. */
    private static boolean awaitOpening(CountDownLatch prior, String trackingId) {
        try {
            if (prior.await(OPENING_WAIT_MS, TimeUnit.MILLISECONDS)) {
                return true;
            }
            logger.debug("Another poll of {} is still opening its connection; @Ready runs on this one", trackingId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted waiting for another poll of {}; @Ready runs on this one", trackingId, e);
        }
        return false;
    }

    /** Run {@code onReady} on the poll opening its client's connection, and track the connection. */
    private void openConnection(String trackingId, AtmosphereResource poll, Object owner, byte[] fingerprint,
                                AtmosphereRequest request, Limits l, Runnable onReady) {
        var before = attributes(request);
        onReady.run();
        var after = before != null ? attributes(request) : null;
        if (after == null) {
            return;
        }
        var kept = new HashMap<String, Object>();
        after.forEach((name, value) -> {
            if (!Objects.equals(before.get(name), value) && !frameworkAttribute(name)) {
                kept.put(name, value);
            }
        });
        if (open(trackingId, new Client(owner, fingerprint, Map.copyOf(kept), poll, System.nanoTime()), l)) {
            watch(trackingId, poll);
        }
    }

    /** Whether {@code poll} continues its client's open connection; its kept attributes are then set. */
    private boolean continues(String trackingId, AtmosphereResource poll, Object owner, byte[] fingerprint,
                              AtmosphereRequest request, Limits l) {
        var restore = new AtomicReference<Map<String, Object>>();
        clients.computeIfPresent(trackingId, (id, client) -> {
            var now = System.nanoTime();
            if (!isOpen(client, now, l)) {
                return null;
            }
            if (Objects.equals(client.owner, owner) && Arrays.equals(client.request, fingerprint)) {
                client.poll = new WeakReference<>(poll);
                client.seenNanos = now;
                restore.set(client.readyAttributes);
            }
            return client;
        });
        var kept = restore.get();
        if (kept == null) {
            return false;
        }
        kept.forEach((name, value) -> {
            if (request.getAttribute(name) == null) {
                request.setAttribute(name, value);
            }
        });
        logger.debug("Poll of {} continues its connection; @Ready not run again", trackingId);
        return true;
    }

    /**
     * Record a connection {@code client} opened, within the bound, first dropping
     * the clients that are gone, at most once a second. The only place an entry
     * is added, under {@link #openLock}: removals elsewhere only make room.
     */
    private boolean open(String trackingId, Client client, Limits l) {
        openLock.lock();
        try {
            var now = System.nanoTime();
            if (now - lastSweepNanos >= SWEEP_INTERVAL_NANOS) {
                lastSweepNanos = now;
                clients.entrySet().removeIf(e -> !isOpen(e.getValue(), now, l));
            }
            if (clients.size() >= l.maxClients() && !clients.containsKey(trackingId)) {
                logger.debug("{} long-polling clients already tracked; @Ready runs on each poll of {}",
                        l.maxClients(), trackingId);
                return false;
            }
            clients.put(trackingId, client);
            return true;
        } finally {
            openLock.unlock();
        }
    }

    /** End the connection on the departure of its latest poll; note when a poll of it ended. */
    private void watch(String trackingId, AtmosphereResource poll) {
        poll.addEventListener(new AtmosphereResourceEventListenerAdapter() {
            @Override
            public void onResume(AtmosphereResourceEvent event) {
                clients.computeIfPresent(trackingId, (id, client) -> {
                    if (client.isLatest(poll)) {
                        client.seenNanos = System.nanoTime();
                    }
                    return client;
                });
            }

            @Override
            public void onDisconnect(AtmosphereResourceEvent event) {
                departed(trackingId, poll);
            }

            @Override
            public void onClose(AtmosphereResourceEvent event) {
                departed(trackingId, poll);
            }
        });
    }

    private void departed(String trackingId, AtmosphereResource poll) {
        clients.computeIfPresent(trackingId, (id, client) -> client.isLatest(poll) ? null : client);
    }

    /**
     * Whether {@code client}'s connection is still open: a poll of it is
     * suspended, or one ended within the grace. A poll no longer reachable is
     * not suspended.
     */
    private static boolean isOpen(Client client, long now, Limits l) {
        var poll = client.poll.get();
        if (poll != null && poll.isSuspended() && !poll.isCancelled()) {
            return true;
        }
        return now - client.seenNanos <= l.graceNanos();
    }

    /** Clients tracked; for tests. */
    int size() {
        return clients.size();
    }

    private static boolean frameworkAttribute(String name) {
        return name.startsWith("org.atmosphere.") || name.startsWith("jakarta.") || name.startsWith("javax.");
    }

    /** The request's attributes, or {@code null} when they cannot be read. */
    private static Map<String, Object> attributes(AtmosphereRequest request) {
        try {
            var names = request.getAttributeNames();
            var attributes = new HashMap<String, Object>();
            if (names == null) {
                return attributes;
            }
            while (names.hasMoreElements()) {
                var name = names.nextElement();
                var value = request.getAttribute(name);
                if (value != null) {
                    attributes.put(name, value);
                }
            }
            return attributes;
        } catch (RuntimeException e) {
            logger.debug("Unable to read the attributes of a poll; its client is not tracked", e);
            return null;
        }
    }

    /**
     * A digest of what a poll presents that {@code @Ready} may derive state
     * from: its path, its query parameters (but the run id a client adds once
     * it holds a reply), and its {@code Authorization}, {@code X-Atmosphere-Auth}
     * and {@code Cookie} headers; {@code null} when the request cannot be read.
     */
    private static byte[] fingerprint(AtmosphereRequest request) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            update(digest, request.getRequestURI());
            var parameters = request.getParameterMap();
            if (parameters != null) {
                var sorted = new TreeMap<String, String[]>(parameters);
                for (var e : sorted.entrySet()) {
                    if (RUN_ID_PARAMETER.equalsIgnoreCase(e.getKey())) {
                        continue;
                    }
                    update(digest, e.getKey());
                    var values = e.getValue();
                    var count = values != null ? values.length : 0;
                    updateLength(digest, count);
                    for (int i = 0; i < count; i++) {
                        update(digest, values[i]);
                    }
                }
            }
            update(digest, request.getHeader("Authorization"));
            update(digest, request.getHeader(HeaderConfig.X_ATMOSPHERE_AUTH));
            update(digest, request.getHeader("Cookie"));
            return digest.digest();
        } catch (NoSuchAlgorithmException | RuntimeException e) {
            logger.debug("Unable to read a poll's request; its client is not tracked", e);
            return null;
        }
    }

    /** Length-prefixed, so no two sequences of values digest alike. */
    private static void update(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) 1);
        updateLength(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateLength(MessageDigest digest, int length) {
        digest.update(new byte[]{(byte) (length >>> 24), (byte) (length >>> 16),
                (byte) (length >>> 8), (byte) length});
    }

    private Limits limits(AtmosphereConfig config) {
        var l = limits;
        if (l == null) {
            synchronized (this) {
                l = limits;
                if (l == null) {
                    l = new Limits(
                            TimeUnit.MILLISECONDS.toNanos(positive(config, DEPARTURE_GRACE_MS_PARAM,
                                    DEFAULT_DEPARTURE_GRACE_MS)),
                            (int) Math.min(Integer.MAX_VALUE,
                                    positive(config, MAX_CLIENTS_PARAM, DEFAULT_MAX_CLIENTS)));
                    limits = l;
                }
            }
        }
        return l;
    }

    private static long positive(AtmosphereConfig config, String name, long defaultValue) {
        String raw;
        try {
            raw = config != null ? config.getInitParameter(name) : null;
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
