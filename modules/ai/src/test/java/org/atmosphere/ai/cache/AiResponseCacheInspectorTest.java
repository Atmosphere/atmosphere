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
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiResponseCacheInspectorTest {

    @Test
    void nonRawMessagePayloadReturnsTrue() {
        var inspector = new AiResponseCacheInspector();
        var message = new BroadcastMessage("plain string payload");
        assertTrue(inspector.inspect(message));
    }

    @Test
    void rawMessageWithNonStringReturnsFalse() {
        var inspector = new AiResponseCacheInspector();
        var message = new BroadcastMessage(new RawMessage(42));
        assertFalse(inspector.inspect(message));
    }

    @Test
    void progressMessageSkippedByDefault() {
        var inspector = new AiResponseCacheInspector();
        var json = "{\"type\":\"progress\",\"message\":\"Thinking...\"}";
        var message = new BroadcastMessage(new RawMessage(json));
        assertFalse(inspector.inspect(message));
    }

    @Test
    void progressMessageCachedWhenEnabled() {
        var inspector = new AiResponseCacheInspector(true);
        var json = "{\"type\":\"progress\",\"message\":\"Thinking...\"}";
        var message = new BroadcastMessage(new RawMessage(json));
        assertTrue(inspector.inspect(message));
    }

    @Test
    void streamingTextMessageAlwaysCached() {
        var inspector = new AiResponseCacheInspector();
        var json = "{\"type\":\"streaming-text\",\"data\":\"Hello\",\"sessionId\":\"s1\",\"seq\":1}";
        var message = new BroadcastMessage(new RawMessage(json));
        assertTrue(inspector.inspect(message));
    }

    @Test
    void streamingTextMessageCachedWithFlagEnabled() {
        var inspector = new AiResponseCacheInspector(true);
        var json = "{\"type\":\"streaming-text\",\"data\":\"Hello\",\"sessionId\":\"s1\",\"seq\":1}";
        var message = new BroadcastMessage(new RawMessage(json));
        assertTrue(inspector.inspect(message));
    }

    @Test
    void presenceControlFrameIsNotCacheable() {
        var inspector = new AiResponseCacheInspector();
        var json = "{\"type\":\"presence\",\"action\":\"join\",\"memberId\":\"u-1\",\"count\":3}";
        var message = new BroadcastMessage(new RawMessage(json));
        assertFalse(inspector.inspect(message));
    }
}
