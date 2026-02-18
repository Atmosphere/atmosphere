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

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.util.ExecutorsFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class UUIDBroadcasterCacheHardeningTest {

    private UUIDBroadcasterCache cache;
    private AtmosphereConfig config;

    @BeforeMethod
    public void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        cache = new UUIDBroadcasterCache();
        cache.configure(config);
        cache.start();
    }

    @AfterMethod
    public void tearDown() {
        cache.stop();
        ExecutorsFactory.reset(config);
        config.getBroadcasterFactory().destroy();
    }

    @Test
    public void testMaxPerClientEvictsOldest() {
        cache.setMaxPerClient(3);

        cache.cacheCandidate("b1", "client1");

        for (int i = 1; i <= 5; i++) {
            cache.addToCache("b1", "client1", new BroadcastMessage("msg" + i));
        }

        assertEquals(cache.messages().get("client1").size(), 3);

        List<Object> retrieved = cache.retrieveFromCache("b1", "client1");
        assertEquals(retrieved.size(), 3);
        assertEquals(retrieved.get(0), "msg3");
        assertEquals(retrieved.get(1), "msg4");
        assertEquals(retrieved.get(2), "msg5");
    }

    @Test
    public void testMessageTTLEviction() throws Exception {
        cache.setMessageTTL(50); // 50ms TTL

        cache.cacheCandidate("b1", "client1");
        cache.addToCache("b1", "client1", new BroadcastMessage("old-msg"));

        Thread.sleep(100);

        cache.invalidateExpiredEntries();

        var queue = cache.messages().get("client1");
        assertTrue(queue == null || queue.isEmpty(), "Expired messages should be evicted");
    }

    @Test
    public void testGlobalMaxTotalEviction() {
        cache.setMaxTotal(5);

        cache.cacheCandidate("b1", "c1");
        cache.cacheCandidate("b1", "c2");
        cache.cacheCandidate("b1", "c3");

        for (int i = 1; i <= 3; i++) {
            cache.addToCache("b1", "c1", new BroadcastMessage("c1-msg" + i));
        }
        for (int i = 1; i <= 3; i++) {
            cache.addToCache("b1", "c2", new BroadcastMessage("c2-msg" + i));
        }
        for (int i = 1; i <= 2; i++) {
            cache.addToCache("b1", "c3", new BroadcastMessage("c3-msg" + i));
        }

        // Total = 8, max = 5. After cleanup should be <= 5
        cache.invalidateExpiredEntries();

        int total = cache.messages().values().stream()
                .mapToInt(q -> q.size())
                .sum();
        assertTrue(total <= 5, "Total cached messages should be <= 5, was " + total);
    }

    @Test
    public void testDefaultLimitsDoNotInterfere() {
        var freshCache = new UUIDBroadcasterCache();
        freshCache.configure(config);

        freshCache.cacheCandidate("b1", "client1");
        for (int i = 0; i < 100; i++) {
            freshCache.addToCache("b1", "client1", new BroadcastMessage("msg" + i));
        }

        assertEquals(freshCache.messages().get("client1").size(), 100);
        freshCache.stop();
    }

    @Test
    public void testMaxPerClientWithMultipleClients() {
        cache.setMaxPerClient(2);

        cache.cacheCandidate("b1", "c1");
        cache.cacheCandidate("b1", "c2");

        for (int i = 1; i <= 5; i++) {
            cache.addToCache("b1", "c1", new BroadcastMessage("c1-" + i));
            cache.addToCache("b1", "c2", new BroadcastMessage("c2-" + i));
        }

        assertEquals(cache.messages().get("c1").size(), 2);
        assertEquals(cache.messages().get("c2").size(), 2);
    }
}
