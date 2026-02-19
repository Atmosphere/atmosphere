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
package org.atmosphere.interceptor;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Backpressure interceptor that tracks per-client pending message counts
 * and applies configurable policies when a client falls behind.
 *
 * <h3>Configuration (init-params or ApplicationConfig)</h3>
 * <ul>
 *   <li>{@code org.atmosphere.backpressure.highWaterMark} — max pending messages per client (default: 1000)</li>
 *   <li>{@code org.atmosphere.backpressure.policy} — what to do when exceeded:
 *       {@code drop-oldest}, {@code drop-newest}, {@code disconnect} (default: drop-oldest)</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * framework.interceptor(new BackpressureInterceptor());
 * }</pre>
 *
 * @since 4.0
 */
public class BackpressureInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(BackpressureInterceptor.class);

    /**
     * Backpressure drop policy.
     */
    public enum Policy {
        /** Drop the oldest pending message to make room. */
        DROP_OLDEST,
        /** Drop the newest (incoming) message. */
        DROP_NEWEST,
        /** Disconnect the slow client. */
        DISCONNECT
    }

    private static final String HIGH_WATER_MARK_PARAM = "org.atmosphere.backpressure.highWaterMark";
    private static final String POLICY_PARAM = "org.atmosphere.backpressure.policy";

    private int highWaterMark = 1000;
    private Policy policy = Policy.DROP_OLDEST;

    // Per-client pending message counts
    private final Map<String, AtomicInteger> pendingCounts = new ConcurrentHashMap<>();
    private final Set<String> registeredListeners = ConcurrentHashMap.newKeySet();
    // Metrics
    private final AtomicLong totalDrops = new AtomicLong();
    private final AtomicLong totalDisconnects = new AtomicLong();

    @Override
    public void configure(AtmosphereConfig config) {
        highWaterMark = Integer.parseInt(
                config.getInitParameter(HIGH_WATER_MARK_PARAM, "1000"));
        String policyStr = config.getInitParameter(POLICY_PARAM, "drop-oldest");
        policy = switch (policyStr.toLowerCase()) {
            case "drop-newest" -> Policy.DROP_NEWEST;
            case "disconnect" -> Policy.DISCONNECT;
            default -> Policy.DROP_OLDEST;
        };
        logger.info("Backpressure interceptor configured: highWaterMark={}, policy={}", highWaterMark, policy);
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        super.inspect(r);

        String uuid = r.uuid();
        pendingCounts.computeIfAbsent(uuid, k -> new AtomicInteger(0));

        // Register cleanup listener only once per resource to avoid growing the listener list
        if (registeredListeners.add(uuid)) {
            r.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onBroadcast(AtmosphereResourceEvent event) {
                    // Message delivered — decrement pending count
                    AtomicInteger count = pendingCounts.get(uuid);
                    if (count != null && count.get() > 0) {
                        count.decrementAndGet();
                    }
                }

                @Override
                public void onDisconnect(AtmosphereResourceEvent event) {
                    pendingCounts.remove(uuid);
                    registeredListeners.remove(uuid);
                }

                @Override
                public void onClose(AtmosphereResourceEvent event) {
                    pendingCounts.remove(uuid);
                    registeredListeners.remove(uuid);
                }
            });
        }

        return Action.CONTINUE;
    }

    /**
     * Check if a message can be delivered to the given resource.
     * Call this before queueing a message for delivery.
     *
     * @param uuid the resource UUID
     * @return true if the message should be delivered, false if dropped
     */
    public boolean allowMessage(String uuid) {
        AtomicInteger count = pendingCounts.computeIfAbsent(uuid, k -> new AtomicInteger(0));

        int pending = count.incrementAndGet();
        if (pending <= highWaterMark) {
            return true;
        }

        // Over high water mark
        return switch (policy) {
            case DROP_NEWEST -> {
                count.decrementAndGet();
                totalDrops.incrementAndGet();
                logger.debug("Backpressure DROP_NEWEST for client {} (pending={})", uuid, pending);
                yield false;
            }
            case DROP_OLDEST -> {
                totalDrops.incrementAndGet();
                logger.debug("Backpressure DROP_OLDEST for client {} (pending={})", uuid, pending);
                yield true;
            }
            case DISCONNECT -> {
                totalDisconnects.incrementAndGet();
                logger.warn("Backpressure DISCONNECT for slow client {} (pending={})", uuid, pending);
                yield false;
            }
        };
    }

    /**
     * @return the number of pending messages for the given resource
     */
    public int pendingCount(String uuid) {
        AtomicInteger count = pendingCounts.get(uuid);
        return count != null ? count.get() : 0;
    }

    /**
     * @return total messages dropped due to backpressure
     */
    public long totalDrops() {
        return totalDrops.get();
    }

    /**
     * @return total clients disconnected due to backpressure
     */
    public long totalDisconnects() {
        return totalDisconnects.get();
    }

    /**
     * @return the configured high water mark
     */
    public int highWaterMark() {
        return highWaterMark;
    }

    /**
     * @return the configured policy
     */
    public Policy policy() {
        return policy;
    }

    @Override
    public String toString() {
        return "BackpressureInterceptor{highWaterMark=" + highWaterMark + ", policy=" + policy + "}";
    }
}
