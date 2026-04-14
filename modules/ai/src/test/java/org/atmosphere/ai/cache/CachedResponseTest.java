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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachedResponseTest {

    @Test
    void notExpiredWithinTtl() {
        var now = Instant.now();
        var response = new CachedResponse("text", null, now, Duration.ofMinutes(5));
        assertFalse(response.isExpired(now.plusSeconds(60)));
    }

    @Test
    void expiredAfterTtl() {
        var now = Instant.now();
        var response = new CachedResponse("text", null, now, Duration.ofMinutes(5));
        assertTrue(response.isExpired(now.plus(Duration.ofMinutes(6))));
    }

    @Test
    void expiredExactlyAtBoundary() {
        var now = Instant.now();
        var response = new CachedResponse("text", null, now, Duration.ofSeconds(10));
        // Exactly at cachedAt + ttl → not expired (isAfter, not isAtOrAfter)
        assertFalse(response.isExpired(now.plusSeconds(10)));
    }

    @Test
    void retainsUsage() {
        var usage = TokenUsage.of(10, 20);
        var response = new CachedResponse("result", usage, Instant.now(), Duration.ofHours(1));
        assertEquals(10, response.usage().input());
    }
}
