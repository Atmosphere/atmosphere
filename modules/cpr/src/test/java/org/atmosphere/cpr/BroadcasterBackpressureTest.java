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

import org.atmosphere.interceptor.BackpressureInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Delivery test for the config-gated backpressure drop policy wired into {@link DefaultBroadcaster}'s per-resource
 * write path. Proves that a configured {@code drop-oldest} policy actually evicts the oldest queued message once the
 * per-resource queue saturates (observable side effects: the oldest token leaves the queue and
 * {@link BackpressureInterceptor#totalDrops()} increments), and that with no policy configured no message is dropped.
 */
public class BroadcasterBackpressureTest {

    private AtmosphereConfig config;

    @AfterEach
    public void tearDown() {
        if (config != null) {
            config.getBroadcasterFactory().destroy();
        }
    }

    private DefaultBroadcaster newBroadcaster(String name, String policy, String highWaterMark) {
        AtmosphereFramework framework = new AtmosphereFramework();
        if (policy != null) {
            framework.addInitParameter(ApplicationConfig.BROADCASTER_BACKPRESSURE_POLICY, policy);
        }
        if (highWaterMark != null) {
            framework.addInitParameter(ApplicationConfig.BROADCASTER_BACKPRESSURE_HIGH_WATER_MARK, highWaterMark);
        }
        framework.addInitParameter(ApplicationConfig.WEBSOCKET_SUPPRESS_JSR356, "true");
        config = framework.init().getAtmosphereConfig();
        return config.getBroadcasterFactory().get(DefaultBroadcaster.class, name);
    }

    private AtmosphereResource resource(String uuid) {
        AtmosphereResource r = mock(AtmosphereResource.class);
        when(r.uuid()).thenReturn(uuid);
        return r;
    }

    private DefaultBroadcaster.AsyncWriteToken token(AtmosphereResource r, String msg) {
        return new DefaultBroadcaster.AsyncWriteToken(r, msg, null, msg, new java.util.concurrent.atomic.AtomicInteger(1));
    }

    /**
     * Replicates {@link DefaultBroadcaster#queueWriteIO}'s contract: for each incoming message ask the drop policy
     * whether to enqueue, and enqueue only when allowed. Returns the messages still queued, oldest-first.
     */
    private List<String> feed(DefaultBroadcaster b, DefaultBroadcaster.WriteQueue wq, AtmosphereResource r, String... messages) {
        for (String m : messages) {
            if (b.applyBackpressure(r, wq)) {
                wq.queue.add(token(r, m));
            }
        }
        List<String> remaining = new ArrayList<>();
        for (DefaultBroadcaster.AsyncWriteToken t : wq.queue) {
            remaining.add((String) t.msg);
        }
        return remaining;
    }

    @Test
    public void dropOldestEvictsOldestWhenQueueSaturates() {
        DefaultBroadcaster b = newBroadcaster("bp-drop-oldest", "drop-oldest", "2");
        BackpressureInterceptor bp = b.backpressurePolicy();
        assertNotNull(bp, "a configured policy must resolve a backpressure engine");
        assertEquals(BackpressureInterceptor.Policy.DROP_OLDEST, bp.policy());
        assertEquals(2, bp.highWaterMark());

        AtmosphereResource r = resource("client-1");
        DefaultBroadcaster.WriteQueue wq = new DefaultBroadcaster.WriteQueue("client-1");

        // High water mark is 2. Feeding a third message must evict the oldest (m1), leaving m2, m3.
        List<String> remaining = feed(b, wq, r, "m1", "m2", "m3");

        assertEquals(List.of("m2", "m3"), remaining, "oldest queued message must be dropped");
        assertEquals(2, wq.queue.size(), "queue must stay bounded at the high water mark");
        assertEquals(1, bp.totalDrops(), "exactly one drop must be recorded");
        assertEquals(2, bp.pendingCount("client-1"), "pending count must stay balanced with the live queue depth");
    }

    @Test
    public void dropNewestRejectsIncomingWhenQueueSaturates() {
        DefaultBroadcaster b = newBroadcaster("bp-drop-newest", "drop-newest", "2");
        BackpressureInterceptor bp = b.backpressurePolicy();
        assertEquals(BackpressureInterceptor.Policy.DROP_NEWEST, bp.policy());

        AtmosphereResource r = resource("client-2");
        DefaultBroadcaster.WriteQueue wq = new DefaultBroadcaster.WriteQueue("client-2");

        List<String> remaining = feed(b, wq, r, "m1", "m2", "m3");

        assertEquals(List.of("m1", "m2"), remaining, "incoming (newest) message must be dropped, oldest kept");
        assertEquals(1, bp.totalDrops());
    }

    @Test
    public void disconnectClosesSlowClientAndRejectsMessage() throws Exception {
        DefaultBroadcaster b = newBroadcaster("bp-disconnect", "disconnect", "2");
        BackpressureInterceptor bp = b.backpressurePolicy();
        assertEquals(BackpressureInterceptor.Policy.DISCONNECT, bp.policy());

        AtmosphereResource r = resource("client-3");
        DefaultBroadcaster.WriteQueue wq = new DefaultBroadcaster.WriteQueue("client-3");

        // First two are queued; the third exceeds the mark and must be rejected + trigger a disconnect.
        assertTrue(b.applyBackpressure(r, wq));
        wq.queue.add(token(r, "m1"));
        assertTrue(b.applyBackpressure(r, wq));
        wq.queue.add(token(r, "m2"));
        assertFalse(b.applyBackpressure(r, wq), "over-limit message must be rejected under DISCONNECT");

        org.mockito.Mockito.verify(r).close();
        assertEquals(1, bp.totalDisconnects());
    }

    @Test
    public void noPolicyConfiguredNeverDrops() {
        DefaultBroadcaster b = newBroadcaster("bp-none", null, null);
        assertNull(b.backpressurePolicy(), "no policy configured must leave the drop engine inert");

        AtmosphereResource r = resource("client-4");
        DefaultBroadcaster.WriteQueue wq = new DefaultBroadcaster.WriteQueue("client-4");

        // Feed well past any default high water mark; nothing may be dropped.
        String[] messages = new String[10];
        for (int i = 0; i < messages.length; i++) {
            messages[i] = "m" + i;
        }
        List<String> remaining = feed(b, wq, r, messages);

        assertEquals(10, remaining.size(), "with no policy every message must be retained (no silent drop)");
        assertEquals(List.of(messages), remaining);
    }
}
