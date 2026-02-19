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
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Inbound rate-limiting interceptor using a token-bucket algorithm.
 * Limits the number of messages a single client can send per time window,
 * preventing abuse and protecting server resources.
 *
 * <p>The token bucket refills at a steady rate of {@code maxMessages / windowSeconds}
 * tokens per second, with bursts up to {@code maxMessages} allowed. This provides
 * smooth rate limiting that tolerates short bursts while enforcing long-term limits.</p>
 *
 * <h3>Configuration (init-params or ApplicationConfig)</h3>
 * <ul>
 *   <li>{@code org.atmosphere.rateLimit.maxMessages} — max messages per window / burst size (default: 100)</li>
 *   <li>{@code org.atmosphere.rateLimit.windowSeconds} — refill window in seconds (default: 60)</li>
 *   <li>{@code org.atmosphere.rateLimit.policy} — action when exceeded:
 *       {@code drop} (silent), {@code disconnect} (close connection) (default: drop)</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * framework.interceptor(new RateLimitingInterceptor());
 * }</pre>
 *
 * @since 4.0
 */
public class RateLimitingInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitingInterceptor.class);

    /**
     * Rate limit exceeded policy.
     */
    public enum Policy {
        /** Silently drop the message, keeping the connection alive. */
        DROP,
        /** Disconnect the offending client. */
        DISCONNECT
    }

    private int maxMessages = 100;
    private long windowNanos = 60_000_000_000L;
    private Policy policy = Policy.DROP;

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong totalDropped = new AtomicLong();
    private final AtomicLong totalDisconnected = new AtomicLong();

    @Override
    public void configure(AtmosphereConfig config) {
        maxMessages = Integer.parseInt(
                config.getInitParameter(ApplicationConfig.RATE_LIMIT_MAX_MESSAGES, "100"));
        int windowSeconds = Integer.parseInt(
                config.getInitParameter(ApplicationConfig.RATE_LIMIT_WINDOW_SECONDS, "60"));
        windowNanos = windowSeconds * 1_000_000_000L;
        var policyStr = config.getInitParameter(ApplicationConfig.RATE_LIMIT_POLICY, "drop");
        policy = "disconnect".equalsIgnoreCase(policyStr) ? Policy.DISCONNECT : Policy.DROP;
        logger.info("Rate limiting configured: {} messages/{} seconds, policy={}",
                maxMessages, windowSeconds, policy);
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        super.inspect(r);

        var uuid = r.uuid();
        var bucket = buckets.computeIfAbsent(uuid,
                k -> new TokenBucket(maxMessages, windowNanos));

        if (bucket.tryConsume()) {
            return Action.CONTINUE;
        }

        // Rate limit exceeded
        return switch (policy) {
            case DROP -> {
                totalDropped.incrementAndGet();
                logger.debug("Rate limit exceeded for client {}, dropping message", uuid);
                yield Action.SKIP_ATMOSPHEREHANDLER;
            }
            case DISCONNECT -> {
                totalDisconnected.incrementAndGet();
                logger.warn("Rate limit exceeded for client {}, disconnecting", uuid);
                try {
                    r.close();
                } catch (Exception e) {
                    logger.debug("Error closing rate-limited resource {}", uuid, e);
                }
                yield Action.CANCELLED;
            }
        };
    }

    @Override
    public void postInspect(AtmosphereResource r) {
        // Register cleanup listener on first postInspect
        var uuid = r.uuid();
        if (buckets.containsKey(uuid)) {
            r.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onDisconnect(AtmosphereResourceEvent event) {
                    buckets.remove(uuid);
                }

                @Override
                public void onClose(AtmosphereResourceEvent event) {
                    buckets.remove(uuid);
                }
            });
        }
    }

    @Override
    public void destroy() {
        buckets.clear();
    }

    /**
     * @return total messages dropped due to rate limiting
     */
    public long totalDropped() {
        return totalDropped.get();
    }

    /**
     * @return total clients disconnected due to rate limiting
     */
    public long totalDisconnected() {
        return totalDisconnected.get();
    }

    /**
     * @return the configured max messages per window
     */
    public int maxMessages() {
        return maxMessages;
    }

    /**
     * @return the configured policy
     */
    public Policy policy() {
        return policy;
    }

    /**
     * @return the number of currently tracked clients
     */
    public int trackedClients() {
        return buckets.size();
    }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.BEFORE_DEFAULT;
    }

    @Override
    public String toString() {
        return "RateLimitingInterceptor{maxMessages=" + maxMessages
                + ", windowNanos=" + windowNanos + ", policy=" + policy + "}";
    }

    /**
     * Token bucket rate limiter. Each bucket holds up to {@code maxTokens} tokens
     * and refills at a constant rate over the configured window.
     */
    static final class TokenBucket {
        private final int maxTokens;
        private final long windowNanos;
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(int maxTokens, long windowNanos) {
            this.maxTokens = maxTokens;
            this.windowNanos = windowNanos;
            this.tokens = maxTokens;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (elapsed <= 0) {
                return;
            }
            // Add tokens proportional to elapsed time
            double newTokens = ((double) elapsed / windowNanos) * maxTokens;
            tokens = Math.min(maxTokens, tokens + newTokens);
            lastRefillNanos = now;
        }
    }
}
