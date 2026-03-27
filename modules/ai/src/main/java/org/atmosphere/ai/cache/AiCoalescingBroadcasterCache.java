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

import com.fasterxml.jackson.core.JsonProcessingException;
import org.atmosphere.ai.filter.AiStreamMessage;
import org.atmosphere.cache.BroadcastMessage;
import org.atmosphere.cache.BroadcasterCacheInspector;
import org.atmosphere.cache.CacheMessage;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.cpr.BroadcasterCacheListener;
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * {@link BroadcasterCache} decorator that coalesces cached AI streaming text messages
 * into a single replay message per session on {@link #retrieveFromCache}. Non-AI and
 * terminal messages (complete, error) are preserved as-is.
 */
public class AiCoalescingBroadcasterCache implements BroadcasterCache {

    private static final Logger logger = LoggerFactory.getLogger(AiCoalescingBroadcasterCache.class);

    private final BroadcasterCache delegate;

    public AiCoalescingBroadcasterCache(BroadcasterCache delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<Object> retrieveFromCache(String id, String uuid) {
        var messages = delegate.retrieveFromCache(id, uuid);
        return coalesceAiStreamingTexts(messages);
    }

    /**
     * Coalesce consecutive AI streaming text messages per session into single messages.
     * Non-AI messages are passed through unchanged.
     */
    List<Object> coalesceAiStreamingTexts(List<Object> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        // Group by sessionId: accumulate streaming text data, pass others through
        var sessionBuffers = new LinkedHashMap<String, StringBuilder>();
        var result = new ArrayList<>();
        var seqCounter = new LinkedHashMap<String, Long>();

        for (var message : messages) {
            var parsed = tryParse(message);
            if (parsed == null) {
                // Non-AI message — pass through
                result.add(message);
                continue;
            }

            var sessionId = parsed.sessionId();
            if (sessionId == null) {
                result.add(message);
                continue;
            }

            if (parsed.isStreamingText() && parsed.data() != null) {
                sessionBuffers.computeIfAbsent(sessionId, k -> new StringBuilder())
                        .append(parsed.data());
                // Track that we need to assign a seq later
                seqCounter.putIfAbsent(sessionId, 0L);
            } else {
                // Non-streaming-text AI message (complete, error, metadata, progress)
                // First, flush any accumulated streaming texts for this session
                flushBuffer(sessionId, sessionBuffers, seqCounter, result);
                // Renumber this message's seq to follow the coalesced streaming text
                var nextSeq = seqCounter.getOrDefault(sessionId, 0L) + 1;
                seqCounter.put(sessionId, nextSeq);
                var renumbered = parsed.withSeq(nextSeq);
                result.add(new RawMessage(renumbered.toJson()));
            }
        }

        // Flush any remaining buffers (sessions that had streaming texts but no terminal)
        for (var sessionId : new ArrayList<>(sessionBuffers.keySet())) {
            flushBuffer(sessionId, sessionBuffers, seqCounter, result);
        }

        return result;
    }

    private void flushBuffer(String sessionId,
                             LinkedHashMap<String, StringBuilder> sessionBuffers,
                             LinkedHashMap<String, Long> seqCounter,
                             List<Object> result) {
        var buffer = sessionBuffers.remove(sessionId);
        if (buffer != null && !buffer.isEmpty()) {
            var nextSeq = seqCounter.getOrDefault(sessionId, 0L) + 1;
            seqCounter.put(sessionId, nextSeq);
            var coalesced = new AiStreamMessage("streaming-text", buffer.toString(), sessionId, nextSeq, null, null);
            result.add(new RawMessage(coalesced.toJson()));
        }
    }

    private AiStreamMessage tryParse(Object message) {
        if (!(message instanceof RawMessage raw)) {
            return null;
        }
        var inner = raw.message();
        if (!(inner instanceof String json)) {
            return null;
        }
        try {
            return AiStreamMessage.parse(json);
        } catch (JsonProcessingException e) {
            logger.debug("Failed to parse cached message as AI stream message: {}", e.getMessage());
            return null;
        }
    }

    // ── Delegate all other BroadcasterCache methods ──

    @Override
    public void configure(AtmosphereConfig config) {
        delegate.configure(config);
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    @Override
    public void cleanup() {
        delegate.cleanup();
    }

    @Override
    public CacheMessage addToCache(String broadcasterId, String uuid, BroadcastMessage message) {
        return delegate.addToCache(broadcasterId, uuid, message);
    }

    @Override
    public BroadcasterCache clearCache(String broadcasterId, String uuid, CacheMessage cache) {
        return delegate.clearCache(broadcasterId, uuid, cache);
    }

    @Override
    public BroadcasterCache excludeFromCache(String broadcasterId, AtmosphereResource r) {
        return delegate.excludeFromCache(broadcasterId, r);
    }

    @Override
    public BroadcasterCache cacheCandidate(String broadcasterId, String uuid) {
        return delegate.cacheCandidate(broadcasterId, uuid);
    }

    @Override
    public BroadcasterCache inspector(BroadcasterCacheInspector interceptor) {
        return delegate.inspector(interceptor);
    }

    @Override
    public BroadcasterCache addBroadcasterCacheListener(BroadcasterCacheListener l) {
        return delegate.addBroadcasterCacheListener(l);
    }

    @Override
    public BroadcasterCache removeBroadcasterCacheListener(BroadcasterCacheListener l) {
        return delegate.removeBroadcasterCacheListener(l);
    }
}
