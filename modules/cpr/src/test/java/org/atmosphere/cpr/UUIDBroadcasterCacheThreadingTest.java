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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class UUIDBroadcasterCacheThreadingTest {

    private static final String BROADCASTER_ID = "B1";
    public static final int NUM_MESSAGES = 100000;
    private final AtomicInteger counter = new AtomicInteger(0);
    private static final String CLIENT_ID = java.util.UUID.randomUUID().toString();
    private final ConcurrentLinkedQueue<Object> retreivedMessages = new ConcurrentLinkedQueue<>();

    @Test
    public void testUuidBroadcasterCacheThreading() {
        AtmosphereConfig config = new AtmosphereFramework().getAtmosphereConfig();
        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        UUIDBroadcasterCache cache = new UUIDBroadcasterCache();
        cache.configure(config);

        Thread t = new Thread(() -> {
            for (int i = 0; i < NUM_MESSAGES; i++) {
                BroadcastMessage broadcastMessage = createBroadcastMessage();
                cache.addToCache(BROADCASTER_ID, CLIENT_ID, broadcastMessage);
            }
        });
        t.start();

        long endTime = System.currentTimeMillis() + 15000;
        int totalRetrieved = 0;
        while (totalRetrieved < NUM_MESSAGES && System.currentTimeMillis() < endTime) {
            List<Object> messages = cache.retrieveFromCache(BROADCASTER_ID, CLIENT_ID);
            if (!messages.isEmpty()) {
                retreivedMessages.addAll(messages);
                totalRetrieved += messages.size();
            }
        }
        assertEquals(NUM_MESSAGES, totalRetrieved);
    }

    private BroadcastMessage createBroadcastMessage() {
        counter.addAndGet(1);
        return new BroadcastMessage("" + counter, counter);
    }

}