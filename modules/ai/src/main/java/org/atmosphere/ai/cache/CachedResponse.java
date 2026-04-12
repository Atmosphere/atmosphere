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

import org.atmosphere.ai.TokenUsage;

import java.time.Duration;
import java.time.Instant;

/**
 * A fully-captured runtime response suitable for replay through a new
 * {@link org.atmosphere.ai.StreamingSession}. The pipeline-level
 * {@link ResponseCache} stores these to short-circuit identical
 * subsequent requests.
 *
 * @param text      the complete response text as concatenated by the
 *                  capturing session
 * @param usage     token usage reported by the runtime (may be null if
 *                  the runtime did not report usage)
 * @param cachedAt  wall-clock timestamp of cache insertion
 * @param ttl       time-to-live; entries older than {@code cachedAt + ttl}
 *                  are considered expired and must be re-fetched
 */
public record CachedResponse(String text, TokenUsage usage, Instant cachedAt, Duration ttl) {

    public boolean isExpired(Instant now) {
        return now.isAfter(cachedAt.plus(ttl));
    }
}
