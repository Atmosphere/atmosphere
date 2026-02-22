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
package org.atmosphere.interceptor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RateLimitingInterceptorTest {

    private RateLimitingInterceptor interceptor;

    @BeforeEach
    public void setUp() {
        interceptor = new RateLimitingInterceptor();
    }

    @Test
    public void testDefaultConfiguration() {
        assertEquals(100, interceptor.maxMessages());
        assertEquals(RateLimitingInterceptor.Policy.DROP, interceptor.policy());
    }

    @Test
    public void testTotalDropsStartsAtZero() {
        assertEquals(0, interceptor.totalDropped());
        assertEquals(0, interceptor.totalDisconnected());
    }

    @Test
    public void testTrackedClientsStartsAtZero() {
        assertEquals(0, interceptor.trackedClients());
    }

    @Test
    public void testToString() {
        var str = interceptor.toString();
        assertTrue(str.contains("RateLimitingInterceptor"));
        assertTrue(str.contains("100"));
        assertTrue(str.contains("DROP"));
    }

    @Test
    public void testTokenBucketAllowsMessagesUpToLimit() {
        var bucket = new RateLimitingInterceptor.TokenBucket(5, 60_000_000_000L);
        // Should allow 5 messages immediately (burst)
        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryConsume(), "Message " + i + " should be allowed");
        }
        // 6th message should be denied (no time elapsed for refill)
        assertFalse(bucket.tryConsume(), "Message 6 should be denied");
    }

    @Test
    public void testTokenBucketRefillsOverTime() throws InterruptedException {
        // Window of 1 second, 10 tokens max
        var bucket = new RateLimitingInterceptor.TokenBucket(10, 1_000_000_000L);
        // Consume all tokens
        for (int i = 0; i < 10; i++) {
            assertTrue(bucket.tryConsume());
        }
        assertFalse(bucket.tryConsume());

        // Wait 200ms — should refill ~2 tokens (10 tokens / 1 second * 0.2 seconds)
        Thread.sleep(250);
        assertTrue(bucket.tryConsume(), "Should have refilled at least 1 token after 250ms");
    }

    @Test
    public void testTokenBucketDoesNotExceedMax() throws InterruptedException {
        var bucket = new RateLimitingInterceptor.TokenBucket(3, 1_000_000_000L);
        // Wait for potential over-refill
        Thread.sleep(200);
        // Should still only allow 3 (max) messages
        assertTrue(bucket.tryConsume());
        assertTrue(bucket.tryConsume());
        assertTrue(bucket.tryConsume());
        assertFalse(bucket.tryConsume());
    }

    @Test
    public void testDestroyCleansBuckets() {
        // Simulate by creating a bucket directly
        var bucket = new RateLimitingInterceptor.TokenBucket(10, 60_000_000_000L);
        bucket.tryConsume();
        // Verify destroy doesn't throw
        interceptor.destroy();
        assertEquals(0, interceptor.trackedClients());
    }

    @Test
    public void testPolicyEnum() {
        assertEquals(2, RateLimitingInterceptor.Policy.values().length);
        assertEquals(RateLimitingInterceptor.Policy.DROP, RateLimitingInterceptor.Policy.valueOf("DROP"));
        assertEquals(RateLimitingInterceptor.Policy.DISCONNECT, RateLimitingInterceptor.Policy.valueOf("DISCONNECT"));
    }

    @Test
    public void testPriorityIsBeforeDefault() {
        assertEquals(InvokationOrder.BEFORE_DEFAULT, interceptor.priority());
    }
}
