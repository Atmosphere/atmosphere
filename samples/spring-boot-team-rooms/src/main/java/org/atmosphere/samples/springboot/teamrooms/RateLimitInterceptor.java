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
package org.atmosphere.samples.springboot.teamrooms;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.atmosphere.config.service.AtmosphereInterceptorService;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A flooder gets 429, not a silent drop.
 *
 * <p>{@code @AtmosphereInterceptorService} installs this ahead of every handler, so the
 * limit is enforced before a message reaches any endpoint. Returning
 * {@link Action#CANCELLED} after setting the status is what makes the rejection visible
 * to the client — dropping the message instead would be an ignored backpressure signal.</p>
 *
 * <p>Note the annotation contract: the processor runs this class through
 * {@link AtmosphereConfig#startupHook}, so {@code configure()} must never register a
 * startup hook of its own.</p>
 */
@AtmosphereInterceptorService
public class RateLimitInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitInterceptor.class);

    /** Requests allowed per client per window. */
    static final int LIMIT = 20;
    /** Window length in milliseconds. */
    static final long WINDOW_MS = 1_000L;
    /**
     * Hard cap on tracked clients. An unbounded map keyed by anything a caller controls
     * is a memory-exhaustion vector; past this size the window is reset wholesale rather
     * than allowed to grow.
     */
    static final int MAX_TRACKED = 10_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private static final class Window {
        private long startedAt;
        private int count;

        Window(long now) {
            this.startedAt = now;
            this.count = 0;
        }
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        String key = r.uuid();
        long now = System.currentTimeMillis();

        if (windows.size() > MAX_TRACKED) {
            logger.warn("rate-limit table exceeded {} entries — resetting", MAX_TRACKED);
            windows.clear();
        }

        Window w = windows.computeIfAbsent(key, k -> new Window(now));
        boolean overLimit;
        synchronized (w) {
            if (now - w.startedAt >= WINDOW_MS) {
                w.startedAt = now;
                w.count = 0;
            }
            w.count++;
            overLimit = w.count > LIMIT;
        }

        if (overLimit) {
            logger.info("rate limit hit by {}", key);
            // Servlet 6 dropped setStatus(int, String) — the reason phrase is not on the wire.
            r.getResponse().setStatus(429);
            return Action.CANCELLED;
        }
        return Action.CONTINUE;
    }

    @Override
    public String toString() {
        return "Team rooms rate limiter (" + LIMIT + " req/" + WINDOW_MS + "ms)";
    }
}
