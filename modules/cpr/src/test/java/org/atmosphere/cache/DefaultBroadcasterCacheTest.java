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
package org.atmosphere.cache;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultBroadcasterCacheTest {

    private final DefaultBroadcasterCache cache =
            new DefaultBroadcasterCache();

    @Test
    void startAndStopAreNoOps() {
        cache.start();
        cache.stop();
        // no exception
    }

    @Test
    void cleanupIsNoOp() {
        cache.cleanup();
    }

    @Test
    void addToCacheReturnsNull() {
        var msg = new BroadcastMessage("hello");
        assertNull(cache.addToCache("b1", "u1", msg));
    }

    @Test
    void retrieveFromCacheReturnsEmptyList() {
        List<Object> result = cache.retrieveFromCache("b1", "u1");
        assertTrue(result.isEmpty());
    }

    @Test
    void clearCacheReturnsSelf() {
        assertSame(cache,
                cache.clearCache("b1", "u1", null));
    }

    @Test
    void excludeFromCacheReturnsSelf() {
        assertSame(cache,
                cache.excludeFromCache("b1", null));
    }

    @Test
    void cacheCandidateReturnsSelf() {
        assertSame(cache,
                cache.cacheCandidate("b1", "u1"));
    }

    @Test
    void configureIsNoOp() {
        cache.configure(null);
    }
}
