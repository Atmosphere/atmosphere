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
package org.atmosphere.channels;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Bounded, TTL-expiring record of the {@code (platform, messageId)} pairs a
 * webhook endpoint has already accepted, used to make inbound delivery
 * idempotent.
 *
 * <p>Every messaging platform Atmosphere integrates with re-delivers a webhook
 * when the endpoint answers non-2xx or times out (Slack retries up to three
 * times, Meta/WhatsApp and Telegram back off and retry for minutes). Without a
 * dedup key, a retry re-runs the agent and re-sends the reply, so the user sees
 * the same answer two or three times and the operator pays for the extra model
 * calls. Platforms make retries identifiable by re-sending the <em>same</em>
 * message identifier, which is exactly what this cache keys on.</p>
 *
 * <p>The cache is bounded on two independent axes so hostile or merely busy
 * inbound traffic can never grow it without limit (Correctness Invariant #3):
 * a hard entry ceiling with least-recently-used eviction, and a time-to-live
 * after which an entry is forgotten regardless of pressure. Both are
 * configurable through {@link ChannelsProperties.DedupProperties}.</p>
 *
 * <p>Thread-safe: webhook deliveries land on arbitrary container threads and
 * the gateway transports (Discord) dispatch from their own event loop, so all
 * mutation runs under a {@link ReentrantLock}.</p>
 */
public final class SeenMessageCache {

    /** Default entry ceiling before least-recently-used eviction kicks in. */
    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    /**
     * Default time-to-live for a remembered message id. Comfortably longer
     * than the retry windows the supported platforms use, short enough that a
     * quiet endpoint releases the memory.
     */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Long> seen;
    private final boolean enabled;
    private final int maxEntries;
    private final long ttlMillis;
    private final LongSupplier clock;

    /**
     * Cache with the default bound and TTL.
     */
    public SeenMessageCache() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL);
    }

    /**
     * @param maxEntries hard entry ceiling; the least recently seen id is
     *                   evicted once exceeded (must be positive)
     * @param ttl        how long an id is remembered (must be positive)
     */
    public SeenMessageCache(int maxEntries, Duration ttl) {
        this(maxEntries, ttl, true, System::currentTimeMillis);
    }

    /**
     * @param maxEntries hard entry ceiling (must be positive)
     * @param ttl        how long an id is remembered (must be positive)
     * @param enabled    when {@code false} every delivery is reported as a
     *                   first delivery and nothing is retained
     * @param clock      millisecond time source; injectable so eviction and
     *                   expiry are testable without sleeping
     */
    public SeenMessageCache(int maxEntries, Duration ttl, boolean enabled, LongSupplier clock) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be > 0, got " + maxEntries);
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be a positive duration, got " + ttl);
        }
        this.maxEntries = maxEntries;
        this.ttlMillis = ttl.toMillis();
        this.enabled = enabled;
        this.clock = clock != null ? clock : System::currentTimeMillis;
        this.seen = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > SeenMessageCache.this.maxEntries;
            }
        };
    }

    /**
     * A cache that never deduplicates — the shape used when
     * {@code atmosphere.channels.dedup.enabled=false}.
     */
    public static SeenMessageCache disabled() {
        return new SeenMessageCache(DEFAULT_MAX_ENTRIES, DEFAULT_TTL, false, System::currentTimeMillis);
    }

    /**
     * Build the cache described by the given properties.
     */
    public static SeenMessageCache from(ChannelsProperties.DedupProperties props) {
        if (props == null || !props.isEnabled()) {
            return disabled();
        }
        return new SeenMessageCache(props.getMaxEntries(), props.getTtl());
    }

    /** Whether this cache actually deduplicates. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Claim a message for processing.
     *
     * <p>Returns {@code true} exactly once per {@code (channelType, messageId)}
     * pair within the configured TTL — the caller that receives {@code true}
     * owns the dispatch. Every concurrent or later re-delivery of the same id
     * gets {@code false} and must be acknowledged without re-processing.</p>
     *
     * <p>Messages with no usable id are never dropped: an absent or blank
     * {@code messageId} always reports a first delivery, because an unkeyed
     * message cannot be proven to be a duplicate and losing real user traffic
     * is worse than answering twice.</p>
     *
     * @param channelType the originating platform (part of the key, so ids
     *                    that collide across platforms stay distinct)
     * @param messageId   the platform's message identifier; may be
     *                    {@code null} or blank
     * @return {@code true} if the caller should process the message
     */
    public boolean firstDelivery(ChannelType channelType, String messageId) {
        if (!enabled || messageId == null || messageId.isBlank()) {
            return true;
        }
        var key = key(channelType, messageId);
        var now = clock.getAsLong();
        lock.lock();
        try {
            purgeExpired(now);
            var previous = seen.get(key);
            if (previous != null && now - previous < ttlMillis) {
                return false;
            }
            seen.put(key, now);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Forget a claimed message so a later re-delivery is processed again.
     *
     * <p>Called when dispatch failed and the endpoint answers 5xx: the platform
     * will retry, and that retry must run the handlers rather than be swallowed
     * as a duplicate of the delivery that never completed (Correctness
     * Invariant #2 — a failed terminal path leaves no claim behind).</p>
     */
    public void forget(ChannelType channelType, String messageId) {
        if (!enabled || messageId == null || messageId.isBlank()) {
            return;
        }
        var key = key(channelType, messageId);
        lock.lock();
        try {
            seen.remove(key);
        } finally {
            lock.unlock();
        }
    }

    /** Number of currently remembered ids. Visible for tests and diagnostics. */
    public int size() {
        lock.lock();
        try {
            purgeExpired(clock.getAsLong());
            return seen.size();
        } finally {
            lock.unlock();
        }
    }

    /** Drop every remembered id. */
    public void clear() {
        lock.lock();
        try {
            seen.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove entries whose TTL elapsed. Runs under the lock on every claim, so
     * an endpoint that keeps receiving traffic keeps releasing memory even when
     * it never reaches the entry ceiling.
     */
    private void purgeExpired(long now) {
        var iterator = seen.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (now - entry.getValue() >= ttlMillis) {
                iterator.remove();
            }
        }
    }

    private static String key(ChannelType channelType, String messageId) {
        var platform = channelType != null ? channelType.id() : "unknown";
        // The platform id comes from the ChannelType enum — a fixed,
        // separator-free token — so an attacker-chosen message id cannot
        // forge another platform's key through the ':' join.
        return platform + ':' + messageId;
    }
}
