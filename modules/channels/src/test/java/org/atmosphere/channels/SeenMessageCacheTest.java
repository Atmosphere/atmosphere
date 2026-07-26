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
package org.atmosphere.channels;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SeenMessageCache} — the bound, the TTL, the unkeyed
 * bypass, and the thread-safety the webhook endpoint relies on.
 */
class SeenMessageCacheTest {

    @Test
    void sameIdIsClaimedOnlyOnce() {
        var cache = new SeenMessageCache();
        assertTrue(cache.firstDelivery(ChannelType.SLACK, "m-1"));
        assertFalse(cache.firstDelivery(ChannelType.SLACK, "m-1"));
        assertFalse(cache.firstDelivery(ChannelType.SLACK, "m-1"));
    }

    @Test
    void distinctIdsAreAllClaimed() {
        var cache = new SeenMessageCache();
        assertTrue(cache.firstDelivery(ChannelType.SLACK, "m-1"));
        assertTrue(cache.firstDelivery(ChannelType.SLACK, "m-2"));
        assertEquals(2, cache.size());
    }

    @Test
    void theSameIdOnTwoPlatformsIsTwoDistinctMessages() {
        var cache = new SeenMessageCache();
        assertTrue(cache.firstDelivery(ChannelType.SLACK, "1"));
        assertTrue(cache.firstDelivery(ChannelType.TELEGRAM, "1"),
                "message ids are only unique per platform — the key must include the channel");
    }

    @Test
    void messagesWithoutAnIdAlwaysPassThrough() {
        var cache = new SeenMessageCache();
        assertTrue(cache.firstDelivery(ChannelType.TELEGRAM, null));
        assertTrue(cache.firstDelivery(ChannelType.TELEGRAM, null));
        assertTrue(cache.firstDelivery(ChannelType.TELEGRAM, "  "));
        assertTrue(cache.firstDelivery(ChannelType.TELEGRAM, "  "));
        assertEquals(0, cache.size(), "unkeyed messages must not consume cache capacity");
    }

    @Test
    void evictionRespectsTheConfiguredBound() {
        var cache = new SeenMessageCache(3, Duration.ofMinutes(5));
        for (var i = 0; i < 50; i++) {
            assertTrue(cache.firstDelivery(ChannelType.WHATSAPP, "m-" + i));
        }
        assertEquals(3, cache.size(), "the cache must never grow past its bound");
        // The three most recent ids are still deduplicated...
        assertFalse(cache.firstDelivery(ChannelType.WHATSAPP, "m-49"));
        assertFalse(cache.firstDelivery(ChannelType.WHATSAPP, "m-48"));
        // ...and the evicted head is accepted again (bounded memory is the
        // deliberate trade: an id older than the bound is re-processed rather
        // than growing the cache without limit).
        assertTrue(cache.firstDelivery(ChannelType.WHATSAPP, "m-0"));
    }

    @Test
    void anIdIsForgottenOnceItsTtlElapses() {
        var now = new AtomicLong(1_000L);
        var cache = new SeenMessageCache(100, Duration.ofMinutes(15), true, now::get);
        assertTrue(cache.firstDelivery(ChannelType.MESSENGER, "m-1"));
        now.addAndGet(Duration.ofMinutes(14).toMillis());
        assertFalse(cache.firstDelivery(ChannelType.MESSENGER, "m-1"),
                "still inside the TTL window — the retry is a duplicate");
        now.addAndGet(Duration.ofMinutes(2).toMillis());
        assertTrue(cache.firstDelivery(ChannelType.MESSENGER, "m-1"),
                "past the TTL the id is forgotten and the message is processed again");
        assertEquals(1, cache.size(), "expired entries must be purged, not accumulated");
    }

    @Test
    void forgetReleasesTheClaimSoARetryIsProcessed() {
        var cache = new SeenMessageCache();
        assertTrue(cache.firstDelivery(ChannelType.SLACK, "m-1"));
        cache.forget(ChannelType.SLACK, "m-1");
        assertTrue(cache.firstDelivery(ChannelType.SLACK, "m-1"));
    }

    @Test
    void aDisabledCacheNeverDeduplicates() {
        var cache = SeenMessageCache.disabled();
        assertFalse(cache.isEnabled());
        assertTrue(cache.firstDelivery(ChannelType.SLACK, "m-1"));
        assertTrue(cache.firstDelivery(ChannelType.SLACK, "m-1"));
        assertEquals(0, cache.size());
    }

    @Test
    void propertiesDriveTheBoundAndTheKillSwitch() {
        var props = new ChannelsProperties.DedupProperties();
        props.setMaxEntries(2);
        props.setTtl(Duration.ofSeconds(30));
        var cache = SeenMessageCache.from(props);
        assertTrue(cache.isEnabled());
        cache.firstDelivery(ChannelType.SLACK, "a");
        cache.firstDelivery(ChannelType.SLACK, "b");
        cache.firstDelivery(ChannelType.SLACK, "c");
        assertEquals(2, cache.size());

        props.setEnabled(false);
        assertFalse(SeenMessageCache.from(props).isEnabled());
        assertFalse(SeenMessageCache.from(null).isEnabled());
    }

    @Test
    void concurrentDeliveriesOfTheSameIdElectExactlyOneWinner() throws Exception {
        var cache = new SeenMessageCache();
        var threads = 32;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var winners = new AtomicInteger();
        for (var i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    if (cache.firstDelivery(ChannelType.DISCORD, "race")) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "all racing deliveries must finish");
        assertEquals(1, winners.get(),
                "exactly one concurrent delivery may claim the message id");
    }

    @Test
    void invalidBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SeenMessageCache(0, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new SeenMessageCache(10, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new SeenMessageCache(10, Duration.ofMinutes(-1)));
    }
}
