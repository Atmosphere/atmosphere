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
package org.atmosphere.cpr;

import org.atmosphere.cache.BroadcastMessage;
import org.atmosphere.cache.UUIDBroadcasterCache;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class UUIDBroadcasterCacheThreadingTest {

    private static final String BROADCASTER_ID = "B1";
    public static final int NUM_MESSAGES = 100000;

    /** Upper bound on how long the producer may take; beyond this it is a real defect. */
    private static final long PRODUCER_JOIN_TIMEOUT_MS = 60000;

    /** Consecutive empty drains that mean the cache has nothing left to give. */
    private static final int MAX_IDLE_DRAINS = 50;
    private final AtomicInteger counter = new AtomicInteger(0);
    private static final String CLIENT_ID = java.util.UUID.randomUUID().toString();
    private final ConcurrentLinkedQueue<Object> retreivedMessages = new ConcurrentLinkedQueue<>();

    @Test
    public void testUuidBroadcasterCacheThreading() throws InterruptedException {
        AtmosphereConfig config = new AtmosphereFramework().getAtmosphereConfig();
        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        UUIDBroadcasterCache cache = new UUIDBroadcasterCache();
        cache.configure(config);
        // Disable per-client eviction for the duration of this thread-safety
        // test. Default maxPerClient=1000 evicts oldest messages once the
        // queue exceeds that bound — under parallel Maven load the consumer
        // falls behind the producer (GC / scheduler stalls), the queue grows
        // past 1000, and old messages get silently dropped, surfacing here as
        // "expected: <100000> but was: <99761>". The test asserts message
        // delivery, not eviction policy, so lift the cap to a value larger
        // than NUM_MESSAGES.
        cache.setMaxPerClient(NUM_MESSAGES * 2);
        // Same reasoning as the eviction cap above, for the other silent-drop
        // path: invalidateExpiredEntries() removes a client idle longer than
        // clientIdleTime (60 s by default) and addMessage() then discards its
        // messages with only a debug log. A heavily loaded run can outlive that
        // window, so lift it well past any plausible runtime. The test asserts
        // delivery, not idle-expiry policy.
        cache.setClientIdleTime(TimeUnit.MINUTES.toMillis(30));

        Thread t = new Thread(() -> {
            for (int i = 0; i < NUM_MESSAGES; i++) {
                BroadcastMessage broadcastMessage = createBroadcastMessage();
                cache.addToCache(BROADCASTER_ID, CLIENT_ID, broadcastMessage);
            }
        });
        t.start();

        // Drain concurrently while the producer is still running — this is
        // the thread-safety check the test was always aiming for.
        long endTime = System.currentTimeMillis() + 15000;
        int totalRetrieved = 0;
        while (totalRetrieved < NUM_MESSAGES && System.currentTimeMillis() < endTime) {
            List<Object> messages = cache.retrieveFromCache(BROADCASTER_ID, CLIENT_ID);
            if (!messages.isEmpty()) {
                retreivedMessages.addAll(messages);
                totalRetrieved += messages.size();
            }
        }
        // Wait for the producer, then drain to quiescence. A single trailing
        // retrieve assumed the whole remainder came back in one batch; when the
        // 15 s window above expires early under load, it does not, and the test
        // failed with e.g. "expected: <100000> but was: <91973>" (2026-08-08,
        // load average ~15 — the same run passed in 7 s on an idle machine).
        //
        // Looping until the cache stops yielding messages removes the wall-clock
        // dependency without weakening what is asserted: a message the cache
        // genuinely dropped never appears, so the count still falls short and
        // the test still fails. Slow is now tolerated; lossy is not.
        t.join(PRODUCER_JOIN_TIMEOUT_MS);
        assertFalse(t.isAlive(), "producer did not finish within "
                + PRODUCER_JOIN_TIMEOUT_MS + "ms — cache is too slow, not merely loaded");

        var idleDrains = 0;
        while (totalRetrieved < NUM_MESSAGES && idleDrains < MAX_IDLE_DRAINS) {
            List<Object> tail = cache.retrieveFromCache(BROADCASTER_ID, CLIENT_ID);
            if (tail.isEmpty()) {
                idleDrains++;
                Thread.sleep(20);
            } else {
                idleDrains = 0;
                retreivedMessages.addAll(tail);
                totalRetrieved += tail.size();
            }
        }
        assertEquals(NUM_MESSAGES, totalRetrieved);
    }

    private BroadcastMessage createBroadcastMessage() {
        counter.addAndGet(1);
        return new BroadcastMessage("" + counter, counter);
    }

}