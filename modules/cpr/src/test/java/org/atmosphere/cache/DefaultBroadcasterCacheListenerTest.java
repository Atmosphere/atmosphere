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

import org.atmosphere.cpr.BroadcasterCacheListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Regression (registre#14): {@code addBroadcasterCacheListener} and
 * {@code removeBroadcasterCacheListener} returned {@code null}, so the
 * BroadcasterConfig registration loop — which calls the method for every
 * framework-registered listener — NPE'd on fluent chaining. The no-op
 * cache honors the fluent contract like its three sibling caches.
 */
class DefaultBroadcasterCacheListenerTest {

    private static final BroadcasterCacheListener LISTENER = new BroadcasterCacheListener() {
        @Override public void onAddCache(String broadcasterId, CacheMessage message) { }
        @Override public void onRemoveCache(String broadcasterId, CacheMessage message) { }
    };

    @Test
    void listenerRegistrationHonorsTheFluentContract() {
        var cache = new DefaultBroadcasterCache();
        assertSame(cache, cache.addBroadcasterCacheListener(LISTENER),
                "returning null NPEs the BroadcasterConfig registration loop");
        assertSame(cache, cache.removeBroadcasterCacheListener(LISTENER));
    }
}
