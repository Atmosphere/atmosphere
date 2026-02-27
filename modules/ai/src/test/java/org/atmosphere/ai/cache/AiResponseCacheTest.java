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

import org.atmosphere.cache.BroadcastMessage;
import org.atmosphere.cache.CacheMessage;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AiResponseCacheTest {

    @Test
    public void testInspectorAllowsTokenMessages() {
        var inspector = new AiResponseCacheInspector();
        var json = "{\"type\":\"token\",\"data\":\"Hello\",\"sessionId\":\"s1\",\"seq\":1}";
        var msg = new BroadcastMessage(new RawMessage(json));

        assertTrue(inspector.inspect(msg));
    }

    @Test
    public void testInspectorAllowsCompleteMessages() {
        var inspector = new AiResponseCacheInspector();
        var json = "{\"type\":\"complete\",\"sessionId\":\"s1\",\"seq\":5}";
        var msg = new BroadcastMessage(new RawMessage(json));

        assertTrue(inspector.inspect(msg));
    }

    @Test
    public void testInspectorSkipsProgressByDefault() {
        var inspector = new AiResponseCacheInspector();
        var json = "{\"type\":\"progress\",\"data\":\"Thinking...\",\"sessionId\":\"s1\",\"seq\":1}";
        var msg = new BroadcastMessage(new RawMessage(json));

        assertFalse(inspector.inspect(msg));
    }

    @Test
    public void testInspectorCachesProgressWhenConfigured() {
        var inspector = new AiResponseCacheInspector(true);
        var json = "{\"type\":\"progress\",\"data\":\"Thinking...\",\"sessionId\":\"s1\",\"seq\":1}";
        var msg = new BroadcastMessage(new RawMessage(json));

        assertTrue(inspector.inspect(msg));
    }

    @Test
    public void testInspectorAllowsNonRawMessages() {
        var inspector = new AiResponseCacheInspector();
        var msg = new BroadcastMessage("plain string");

        assertTrue(inspector.inspect(msg));
    }

    @Test
    public void testListenerCountsTokens() {
        var listener = new AiResponseCacheListener();

        var token1 = cacheMessage("{\"type\":\"token\",\"data\":\"a\",\"sessionId\":\"s1\",\"seq\":1}");
        var token2 = cacheMessage("{\"type\":\"token\",\"data\":\"b\",\"sessionId\":\"s1\",\"seq\":2}");

        listener.onAddCache("b1", token1);
        listener.onAddCache("b1", token2);

        assertEquals(2, listener.getCachedTokenCount("s1"));
    }

    @Test
    public void testListenerCleansUpOnComplete() {
        var listener = new AiResponseCacheListener();

        listener.onAddCache("b1", cacheMessage("{\"type\":\"token\",\"data\":\"a\",\"sessionId\":\"s1\",\"seq\":1}"));
        listener.onAddCache("b1", cacheMessage("{\"type\":\"token\",\"data\":\"b\",\"sessionId\":\"s1\",\"seq\":2}"));
        assertEquals(2, listener.getCachedTokenCount("s1"));

        listener.onAddCache("b1", cacheMessage("{\"type\":\"complete\",\"sessionId\":\"s1\",\"seq\":3}"));
        assertEquals(0, listener.getCachedTokenCount("s1"));
    }

    @Test
    public void testListenerTracksMultipleSessions() {
        var listener = new AiResponseCacheListener();

        listener.onAddCache("b1", cacheMessage("{\"type\":\"token\",\"data\":\"a\",\"sessionId\":\"s1\",\"seq\":1}"));
        listener.onAddCache("b1", cacheMessage("{\"type\":\"token\",\"data\":\"b\",\"sessionId\":\"s2\",\"seq\":1}"));
        listener.onAddCache("b1", cacheMessage("{\"type\":\"token\",\"data\":\"c\",\"sessionId\":\"s2\",\"seq\":2}"));

        assertEquals(1, listener.getCachedTokenCount("s1"));
        assertEquals(2, listener.getCachedTokenCount("s2"));
    }

    @Test
    public void testListenerIgnoresNonRawMessages() {
        var listener = new AiResponseCacheListener();
        listener.onAddCache("b1", new CacheMessage("id1", "plain string", "uuid-1"));
        assertEquals(0, listener.getCachedTokenCount("unknown"));
    }

    @Test
    public void testExtractJsonField() {
        assertEquals("token", AiResponseCacheListener.extractJsonField(
                "{\"type\":\"token\",\"data\":\"Hello\"}", "type"));
        assertEquals("Hello", AiResponseCacheListener.extractJsonField(
                "{\"type\":\"token\",\"data\":\"Hello\"}", "data"));
        assertNull(AiResponseCacheListener.extractJsonField(
                "{\"type\":\"token\"}", "sessionId"));
    }

    private static CacheMessage cacheMessage(String json) {
        return new CacheMessage("id-" + System.nanoTime(), new RawMessage(json), "uuid-1");
    }
}
