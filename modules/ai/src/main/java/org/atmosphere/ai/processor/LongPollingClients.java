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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
 * ({@code org.atmosphere.*}, {@code jakarta.*}, {@code javax.*}) are not kept.</p>
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
 * request and the attributes {@code @Ready} set. A full set drops its departed
 * clients, at most once a second; a client that still finds no room is not
 * tracked, and {@code @Ready} runs on each of its polls, as it did before
 * clients were tracked.</p>
 */
final class LongPollingClients {

    /** How long after its last poll ended a client with no poll suspended counts as gone, in ms. */
    static final String DEPARTURE_GRACE_MS_PARAM = "org.atmosphere.ai.longPolling.departureGraceMs";

    /** How many long-polling clients one endpoint tracks at once. */
    static final String MAX_CLIENTS_PARAM = "org.atmosphere.ai.longPolling.maxTrackedClients";

    static final long DEFAULT_DEPARTURE_GRACE_MS = 30_000;
    static final int DEFAULT_MAX_CLIENTS = 10_000;

    private static final long SWEEP_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

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
        /** The client's latest poll; only its departure ends the connection. */
        private volatile AtmosphereResource poll;
        /** When a poll of the client last arrived or ended. */
        private volatile long seenNanos;

        private Client(Object owner, byte[] request, Map<String, Object> readyAttributes,
                       AtmosphereResource poll, long seenNanos) {
            this.owner = owner;
            this.request = request;
            this.readyAttributes = readyAttributes;
            this.poll = poll;
            this.seenNanos = seenNanos;
        }
    }

    /** Tracking id -> its open connection. Entries change only inside the map's per-key compute. */
    private final ConcurrentHashMap<String, Client> clients = new ConcurrentHashMap<>();
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
                client.poll = poll;
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

    /** Record a connection {@code client} opened, within the bound. */
    private boolean open(String trackingId, Client client, Limits l) {
        if (clients.size() >= l.maxClients() && !clients.containsKey(trackingId)) {
            var now = System.nanoTime();
            if (now - lastSweepNanos >= SWEEP_INTERVAL_NANOS) {
                lastSweepNanos = now;
                clients.entrySet().removeIf(e -> !isOpen(e.getValue(), now, l));
            }
        }
        if (clients.size() >= l.maxClients() && !clients.containsKey(trackingId)) {
            logger.debug("{} long-polling clients already tracked; @Ready runs on each poll of {}",
                    l.maxClients(), trackingId);
            return false;
        }
        clients.put(trackingId, client);
        return true;
    }

    /** End the connection on the departure of its latest poll; note when a poll of it ended. */
    private void watch(String trackingId, AtmosphereResource poll) {
        poll.addEventListener(new AtmosphereResourceEventListenerAdapter() {
            @Override
            public void onResume(AtmosphereResourceEvent event) {
                clients.computeIfPresent(trackingId, (id, client) -> {
                    if (client.poll == poll) {
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
        clients.computeIfPresent(trackingId, (id, client) -> client.poll == poll ? null : client);
    }

    /** Whether {@code client}'s connection is still open: a poll of it is suspended, or one ended within the grace. */
    private static boolean isOpen(Client client, long now, Limits l) {
        var poll = client.poll;
        if (poll.isSuspended() && !poll.isCancelled()) {
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
