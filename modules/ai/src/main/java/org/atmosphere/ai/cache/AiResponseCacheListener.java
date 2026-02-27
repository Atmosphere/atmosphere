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
package org.atmosphere.ai.cache;

import org.atmosphere.cache.CacheMessage;
import org.atmosphere.cpr.BroadcasterCacheListener;
import org.atmosphere.cpr.RawMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link BroadcasterCacheListener} that tracks AI streaming sessions in the cache.
 *
 * <p>When token messages are cached, this listener counts them per session.
 * When a "complete" message is cached, it logs the total token count for that
 * session — useful for monitoring cache size and replay cost.</p>
 *
 * <p>This is an observational hook (not a gate). It does not modify cached messages.
 * For controlling what gets cached, use {@link AiResponseCacheInspector}.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var listener = new AiResponseCacheListener();
 * broadcaster.getBroadcasterConfig()
 *     .getBroadcasterCache()
 *     .addBroadcasterCacheListener(listener);
 *
 * // Later: check how many tokens were cached for a session
 * int count = listener.getCachedTokenCount("session-123");
 * }</pre>
 */
public class AiResponseCacheListener implements BroadcasterCacheListener {

    private static final Logger logger = LoggerFactory.getLogger(AiResponseCacheListener.class);

    private final ConcurrentHashMap<String, AtomicInteger> tokenCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> sessionStartTimes = new ConcurrentHashMap<>();
    private final List<CoalescedCacheEventListener> coalescedListeners = new CopyOnWriteArrayList<>();

    @Override
    public void onAddCache(String broadcasterId, CacheMessage cacheMessage) {
        var payload = cacheMessage.getMessage();

        if (!(payload instanceof RawMessage raw)) {
            return;
        }

        var inner = raw.message();
        if (!(inner instanceof String json)) {
            return;
        }

        // Extract sessionId and type with simple string scanning (avoid full JSON parse)
        var sessionId = extractJsonField(json, "sessionId");
        var type = extractJsonField(json, "type");

        if (sessionId == null || type == null) {
            return;
        }

        if ("token".equals(type)) {
            tokenCounts.computeIfAbsent(sessionId, k -> new AtomicInteger()).incrementAndGet();
            sessionStartTimes.computeIfAbsent(sessionId, k -> System.nanoTime());
        } else if ("complete".equals(type) || "error".equals(type)) {
            var count = tokenCounts.remove(sessionId);
            var startNanos = sessionStartTimes.remove(sessionId);
            var totalTokens = count != null ? count.get() : 0;
            var elapsedMs = startNanos != null
                    ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
                    : 0L;

            logger.debug("AI session {} cached: {} tokens (broadcaster: {})",
                    sessionId, totalTokens, broadcasterId);

            if (!coalescedListeners.isEmpty()) {
                var event = new CoalescedCacheEvent(
                        sessionId, broadcasterId, totalTokens, type, elapsedMs);
                fireCoalescedEvent(event);
            }
        }
    }

    @Override
    public void onRemoveCache(String broadcasterId, CacheMessage cacheMessage) {
        // No-op — we track on add, not on removal
    }

    /**
     * Get the number of token messages currently cached for a session.
     *
     * @param sessionId the session ID
     * @return the cached token count, or 0 if no tokens are cached
     */
    public int getCachedTokenCount(String sessionId) {
        var count = tokenCounts.get(sessionId);
        return count != null ? count.get() : 0;
    }

    /**
     * Register a coalesced event listener.
     *
     * @param listener the listener to add
     */
    public void addCoalescedListener(CoalescedCacheEventListener listener) {
        coalescedListeners.add(listener);
    }

    /**
     * Remove a previously registered coalesced event listener.
     *
     * @param listener the listener to remove
     */
    public void removeCoalescedListener(CoalescedCacheEventListener listener) {
        coalescedListeners.remove(listener);
    }

    private void fireCoalescedEvent(CoalescedCacheEvent event) {
        for (var listener : coalescedListeners) {
            try {
                listener.onCoalescedEvent(event);
            } catch (Exception e) {
                logger.warn("Coalesced listener threw exception for session {}",
                        event.sessionId(), e);
            }
        }
    }

    /**
     * Extract a simple string field value from a JSON string without full parsing.
     * Only works for simple string values (not nested objects/arrays).
     */
    static String extractJsonField(String json, String field) {
        var search = "\"" + field + "\":\"";
        var idx = json.indexOf(search);
        if (idx < 0) return null;
        var start = idx + search.length();
        var end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }
}
