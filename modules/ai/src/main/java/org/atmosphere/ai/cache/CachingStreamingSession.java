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

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BiConsumer;

/**
 * Delegating {@link StreamingSession} that tees every {@code send(...)}
 * call into a {@link StringBuilder}. Pipeline-level response caching wraps
 * the user-facing session in this captor so the downstream runtime streams
 * normally while the pipeline captures the full response for later replay.
 *
 * <p><b>Cache commit is explicit, not auto.</b> {@code complete()} is NOT a
 * valid cache commit signal because runtime bridges (Koog, LC4j, ADK) call
 * {@code session.complete()} on caller-initiated cancel as a clean
 * termination. Auto-caching there would store a partial response and
 * silently replay it as authoritative. Instead the pipeline calls
 * {@link #commit()} only after {@code runtime.execute()} returns without
 * throwing and the session is not in an errored/cancelled state — a
 * committed capture is guaranteed to be the result of a fully-drained
 * successful run.</p>
 *
 * <p>Error paths discard the partial capture: if the runtime calls
 * {@link #error(Throwable)}, the captor is poisoned and {@link #commit()}
 * becomes a no-op so the next request re-executes and may succeed.</p>
 */
public class CachingStreamingSession implements StreamingSession {

    private final StreamingSession delegate;
    private final BiConsumer<String, CachedResponse> cacheSink;
    private final Duration ttl;
    private final StringBuilder captured = new StringBuilder();
    private final String cacheKey;
    private volatile TokenUsage capturedUsage;
    private volatile boolean errored;
    private volatile boolean committed;

    public CachingStreamingSession(StreamingSession delegate, String cacheKey,
                                   Duration ttl, BiConsumer<String, CachedResponse> cacheSink) {
        this.delegate = delegate;
        this.cacheKey = cacheKey;
        this.ttl = ttl;
        this.cacheSink = cacheSink;
    }

    @Override
    public String sessionId() {
        return delegate.sessionId();
    }

    @Override
    public synchronized void send(String text) {
        if (text != null) {
            captured.append(text);
        }
        delegate.send(text);
    }

    @Override
    public void sendMetadata(String key, Object value) {
        delegate.sendMetadata(key, value);
    }

    @Override
    public void progress(String message) {
        delegate.progress(message);
    }

    @Override
    public void usage(TokenUsage usage) {
        this.capturedUsage = usage;
        delegate.usage(usage);
    }

    @Override
    public synchronized void complete() {
        // Intentionally does NOT persist. A cancel path may call complete()
        // to drain the client cleanly; persisting here would cache a
        // partial response. The pipeline calls commit() after runtime.execute
        // returns successfully instead.
        delegate.complete();
    }

    @Override
    public synchronized void complete(String summary) {
        delegate.complete(summary);
    }

    @Override
    public synchronized void error(Throwable t) {
        errored = true;
        delegate.error(t);
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public boolean hasErrored() {
        return errored || delegate.hasErrored();
    }

    /**
     * Persist the captured text as the cache entry iff the session has not
     * errored. Called by {@link org.atmosphere.ai.AiPipeline#execute} after a
     * successful {@code runtime.execute()} return, so a cancel-induced
     * {@code session.complete()} cannot cache a truncated response. Idempotent:
     * subsequent calls are no-ops.
     */
    public synchronized void commit() {
        if (committed || errored) {
            return;
        }
        var text = captured.toString();
        if (text.isEmpty()) {
            return;
        }
        cacheSink.accept(cacheKey, new CachedResponse(text, capturedUsage, Instant.now(), ttl));
        committed = true;
    }
}
