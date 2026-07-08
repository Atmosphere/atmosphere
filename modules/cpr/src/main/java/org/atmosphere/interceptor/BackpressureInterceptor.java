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
 * Backpressure interceptor that tracks per-client pending message counts and applies
 * a configurable policy (drop-oldest, drop-newest, or disconnect) when a client
 * exceeds the high water mark.
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
        configure(highWaterMark, policyFromString(policyStr));
    }

    /**
     * Programmatically configure the high water mark and drop policy. Used by consumers (such as the
     * {@link org.atmosphere.cpr.DefaultBroadcaster} write path) that resolve the policy from their own
     * configuration rather than from interceptor init parameters.
     *
     * @param highWaterMark the per-client pending high water mark (values &lt; 1 are clamped to 1)
     * @param policy        the drop policy to apply once the high water mark is exceeded
     */
    public void configure(int highWaterMark, Policy policy) {
        this.highWaterMark = Math.max(1, highWaterMark);
        this.policy = policy;
        logger.info("Backpressure interceptor configured: highWaterMark={}, policy={}", this.highWaterMark, this.policy);
    }

    /**
     * Parse a policy string (case-insensitive) into a {@link Policy}. Unknown values fall back to
     * {@link Policy#DROP_OLDEST}.
     *
     * @param policyStr the policy string, e.g. {@code drop-oldest}, {@code drop-newest}, {@code disconnect}
     * @return the resolved policy
     */
    public static Policy policyFromString(String policyStr) {
        if (policyStr == null) {
            return Policy.DROP_OLDEST;
        }
        return switch (policyStr.toLowerCase()) {
            case "drop-newest" -> Policy.DROP_NEWEST;
            case "disconnect" -> Policy.DISCONNECT;
            default -> Policy.DROP_OLDEST;
        };
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
                    messageDelivered(uuid);
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
     * Signal that a previously counted pending message has left the write queue (delivered to the client or
     * evicted by a drop policy). Decrements the per-client pending count, never below zero. Consumers wiring
     * {@link #allowMessage(String)} into a real write/queue path MUST call this once per message removed from the
     * queue, otherwise the pending count leaks upward and every message is eventually treated as over-limit.
     *
     * @param uuid the resource UUID
     */
    public void messageDelivered(String uuid) {
        AtomicInteger count = pendingCounts.get(uuid);
        if (count != null && count.get() > 0) {
            count.decrementAndGet();
        }
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
