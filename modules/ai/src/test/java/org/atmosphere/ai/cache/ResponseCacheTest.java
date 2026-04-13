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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResponseCacheTest {

    @Test
    void putAndGetRoundTrips() {
        var cache = new InMemoryResponseCache(8);
        var entry = new CachedResponse("hello world", null, Instant.now(), Duration.ofMinutes(5));
        cache.put("k1", entry);

        assertEquals(1, cache.size());
        assertEquals("hello world", cache.get("k1").orElseThrow().text());
    }

    @Test
    void expiredEntryIsEvictedOnGet() {
        var cache = new InMemoryResponseCache(8);
        var expired = new CachedResponse("stale", null,
                Instant.now().minusSeconds(3600), Duration.ofSeconds(60));
        cache.put("k1", expired);

        assertTrue(cache.get("k1").isEmpty());
        assertEquals(0, cache.size());
    }

    @Test
    void lruEvictsOldestBeyondMaxEntries() {
        var cache = new InMemoryResponseCache(2);
        var fresh = Instant.now();
        var ttl = Duration.ofMinutes(10);
        cache.put("a", new CachedResponse("a-text", null, fresh, ttl));
        cache.put("b", new CachedResponse("b-text", null, fresh, ttl));
        cache.put("c", new CachedResponse("c-text", null, fresh, ttl));

        assertEquals(2, cache.size());
        assertTrue(cache.get("a").isEmpty(), "LRU eldest should be evicted");
        assertTrue(cache.get("b").isPresent());
        assertTrue(cache.get("c").isPresent());
    }

    @Test
    void cacheKeyIsDeterministicAcrossIdenticalContexts() {
        var ctx1 = newContext("Hello", "You are helpful", "gpt-4", "sess-1");
        var ctx2 = newContext("Hello", "You are helpful", "gpt-4", "sess-2"); // different session

        var k1 = CacheKey.compute(ctx1);
        var k2 = CacheKey.compute(ctx2);

        assertEquals(k1, k2, "CacheKey must not depend on sessionId");
        assertEquals(64, k1.length(), "SHA-256 hex is 64 chars");
    }

    @Test
    void cacheKeyDiffersForDifferentMessage() {
        var ctx1 = newContext("Hello", "You are helpful", "gpt-4", "sess-1");
        var ctx2 = newContext("Goodbye", "You are helpful", "gpt-4", "sess-1");

        assertNotEquals(CacheKey.compute(ctx1), CacheKey.compute(ctx2));
    }

    @Test
    void cacheKeyDiffersForDifferentSystemPrompt() {
        var ctx1 = newContext("Hello", "You are helpful", "gpt-4", "sess-1");
        var ctx2 = newContext("Hello", "You are curt", "gpt-4", "sess-1");

        assertNotEquals(CacheKey.compute(ctx1), CacheKey.compute(ctx2));
    }

    @Test
    void cacheKeyDiffersForDifferentFileParts() {
        // F-C2: Content.File parts must be included in the key by
        // mime-type + length. Two files of different sizes must produce
        // different keys.
        var file1 = new org.atmosphere.ai.Content.File(
                new byte[]{1, 2, 3}, "application/pdf", "doc1.pdf");
        var file2 = new org.atmosphere.ai.Content.File(
                new byte[]{1, 2, 3, 4, 5}, "application/pdf", "doc2.pdf");
        var ctx1 = new AgentExecutionContext(
                "Summarize", "You are helpful", "gpt-4",
                null, "sess-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(), List.<org.atmosphere.ai.Content>of(file1),
                ToolApprovalPolicy.annotated());
        var ctx2 = new AgentExecutionContext(
                "Summarize", "You are helpful", "gpt-4",
                null, "sess-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(), List.<org.atmosphere.ai.Content>of(file2),
                ToolApprovalPolicy.annotated());
        assertNotEquals(CacheKey.compute(ctx1), CacheKey.compute(ctx2),
                "Different file lengths must produce different cache keys");
    }

    @Test
    void cacheKeyDiffersForFileContentOfSameLength() {
        // Nit #17: Two files with identical mime type and identical byte
        // length but different content must not collide. The mime+length
        // shortcut (Image/Audio) is not safe for semantic File parts where
        // a user might upload "doc1.pdf" and "doc2.pdf" that happen to be
        // the same size.
        var file1 = new org.atmosphere.ai.Content.File(
                new byte[]{1, 2, 3, 4, 5}, "application/pdf", "doc1.pdf");
        var file2 = new org.atmosphere.ai.Content.File(
                new byte[]{5, 4, 3, 2, 1}, "application/pdf", "doc2.pdf");
        var ctx1 = new AgentExecutionContext(
                "Summarize", "You are helpful", "gpt-4",
                null, "sess-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(), List.<org.atmosphere.ai.Content>of(file1),
                ToolApprovalPolicy.annotated());
        var ctx2 = new AgentExecutionContext(
                "Summarize", "You are helpful", "gpt-4",
                null, "sess-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(), List.<org.atmosphere.ai.Content>of(file2),
                ToolApprovalPolicy.annotated());
        assertNotEquals(CacheKey.compute(ctx1), CacheKey.compute(ctx2),
                "Files with distinct bytes must produce distinct cache keys even when size matches");
    }

    @Test
    void cacheKeyDiffersForDifferentResponseType() {
        // Blocker #1: responseType is an output discriminator — a plain-text
        // request and a structured-JSON request with the same prompt must not
        // collide or the structured caller silently replays plain text.
        var ctxText = new AgentExecutionContext(
                "Summarize", "You are helpful", "gpt-4",
                null, "sess-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(), List.of(),
                ToolApprovalPolicy.annotated());
        var ctxJson = new AgentExecutionContext(
                "Summarize", "You are helpful", "gpt-4",
                null, "sess-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), java.util.Map.class, null, List.of(), List.of(),
                ToolApprovalPolicy.annotated());
        assertNotEquals(CacheKey.compute(ctxText), CacheKey.compute(ctxJson),
                "Plain-text vs structured-output requests must produce different keys");
    }

    @Test
    void purgeExpiredRemovesOnlyExpiredEntries() {
        var cache = new InMemoryResponseCache(16);
        cache.put("fresh", new CachedResponse("a", null, Instant.now(), Duration.ofMinutes(5)));
        cache.put("stale",
                new CachedResponse("b", null, Instant.now().minusSeconds(3600), Duration.ofSeconds(60)));
        cache.put("stale2",
                new CachedResponse("c", null, Instant.now().minusSeconds(7200), Duration.ofSeconds(60)));
        assertEquals(3, cache.size());
        assertEquals(2, cache.purgeExpired());
        assertEquals(1, cache.size());
        assertTrue(cache.get("fresh").isPresent());
    }

    @Test
    void invalidateRemovesEntry() {
        var cache = new InMemoryResponseCache();
        cache.put("k", new CachedResponse("x", null, Instant.now(), Duration.ofMinutes(1)));
        assertEquals(1, cache.size());
        cache.invalidate("k");
        assertEquals(0, cache.size());
    }

    @Test
    void autoCloseableContract() throws Exception {
        // ResponseCache extends AutoCloseable so pipelines can wrap any impl
        // in try-with-resources regardless of whether the backing store holds
        // external resources. InMemoryResponseCache inherits the default
        // no-op body and must be safe to close (idempotent, exception-free)
        // while still serving reads afterwards — the default body does not
        // invalidate the in-memory state.
        try (ResponseCache cache = new InMemoryResponseCache()) {
            cache.put("k", new CachedResponse("hello", null, Instant.now(), Duration.ofMinutes(1)));
            assertEquals(1, cache.size());
        }

        // Double-close (e.g., a nested try-with-resources) must not throw.
        var again = new InMemoryResponseCache();
        again.close();
        again.close();
    }

    private static AgentExecutionContext newContext(String message, String systemPrompt,
                                                    String model, String sessionId) {
        return new AgentExecutionContext(
                message, systemPrompt, model,
                null, sessionId, "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null, List.of(), List.of(),
                ToolApprovalPolicy.annotated());
    }
}
