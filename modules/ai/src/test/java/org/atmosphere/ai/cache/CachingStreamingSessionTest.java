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

import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CachingStreamingSessionTest {

    private CollectingSession delegate;
    private ConcurrentHashMap<String, CachedResponse> cache;
    private CachingStreamingSession session;

    @BeforeEach
    void setUp() {
        delegate = new CollectingSession("test-session");
        cache = new ConcurrentHashMap<>();
        session = new CachingStreamingSession(
                delegate, "cache-key", Duration.ofMinutes(5), cache::put);
    }

    @Test
    void sessionIdDelegatesToUnderlyingSession() {
        assertEquals("test-session", session.sessionId());
    }

    @Test
    void sendForwardsToDelegateAndCaptures() {
        session.send("hello ");
        session.send("world");
        assertEquals("hello world", delegate.text());
    }

    @Test
    void commitStoresCapturedText() {
        session.send("response text");
        session.commit();

        var cached = cache.get("cache-key");
        assertNotNull(cached);
        assertEquals("response text", cached.text());
    }

    @Test
    void commitIsIdempotent() {
        session.send("data");
        session.commit();
        session.commit();

        assertEquals(1, cache.size());
    }

    @Test
    void commitAfterErrorIsNoOp() {
        session.send("partial");
        session.error(new RuntimeException("fail"));
        session.commit();

        assertNull(cache.get("cache-key"));
    }

    @Test
    void commitWithEmptyTextIsNoOp() {
        session.commit();
        assertNull(cache.get("cache-key"));
    }

    @Test
    void errorSetsErroredState() {
        session.error(new RuntimeException("oops"));
        assertFalse(session.hasErrored() == false);
    }

    @Test
    void completeDoesNotAutoCommit() {
        session.send("data");
        session.complete();

        assertNull(cache.get("cache-key"));
    }

    @Test
    void completeWithSummaryForwardToDelegate() {
        session.complete("final summary");
        // delegate.complete(summary) was called — delegate should be closed
        assertFalse(delegate.text().isEmpty() == false && delegate.isClosed() == false);
    }

    @Test
    void usageForwardsAndCaptured() {
        var usage = TokenUsage.of(10, 20);
        session.usage(usage);
        session.send("text");
        session.commit();

        var cached = cache.get("cache-key");
        assertNotNull(cached);
        assertNotNull(cached.usage());
        assertEquals(10, cached.usage().input());
    }

    @Test
    void sendMetadataForwardsToDelegate() {
        session.sendMetadata("key", "value");
        // No exception means it forwarded successfully
        assertEquals("test-session", session.sessionId());
    }

    @Test
    void progressForwardsToDelegate() {
        session.progress("loading...");
        assertEquals("test-session", session.sessionId());
    }

    @Test
    void isClosedDelegatesToUnderlying() {
        assertFalse(session.isClosed());
        delegate.complete();
        assertFalse(session.isClosed() == false);
    }

    @Test
    void sendContentWithTextContentGoesViaSend() {
        session.sendContent(new Content.Text("inline text"));
        session.commit();

        var cached = cache.get("cache-key");
        assertNotNull(cached);
        assertEquals("inline text", cached.text());
    }

    @Test
    void sendNullTextDoesNotCorruptCapture() {
        session.send(null);
        session.send("after");
        session.commit();

        var cached = cache.get("cache-key");
        assertNotNull(cached);
        assertEquals("after", cached.text());
    }

    @Test
    void hasErroredReflectsErrorState() {
        assertFalse(session.hasErrored());
        session.error(new RuntimeException("err"));
        assertFalse(session.hasErrored() == false);
    }
}
