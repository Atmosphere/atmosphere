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
package org.atmosphere.ai;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link AiStreamingAdapter} interface, focusing on the
 * default {@code capabilities()} method and basic contract.
 */
class AiStreamingAdapterTest {

    @Test
    void defaultCapabilitiesContainsTextStreaming() {
        AiStreamingAdapter<String> adapter = new AiStreamingAdapter<>() {
            @Override
            public String name() { return "test-adapter"; }

            @Override
            public void stream(String request, StreamingSession session) { }
        };
        var caps = adapter.capabilities();
        assertEquals(1, caps.size());
        assertTrue(caps.contains(AiCapability.TEXT_STREAMING));
    }

    @Test
    void nameReturnsConfiguredValue() {
        AiStreamingAdapter<String> adapter = new AiStreamingAdapter<>() {
            @Override
            public String name() { return "my-adapter"; }

            @Override
            public void stream(String request, StreamingSession session) { }
        };
        assertEquals("my-adapter", adapter.name());
    }

    @Test
    void overriddenCapabilitiesAreRespected() {
        AiStreamingAdapter<String> adapter = new AiStreamingAdapter<>() {
            @Override
            public String name() { return "multi-cap"; }

            @Override
            public void stream(String request, StreamingSession session) { }

            @Override
            public Set<AiCapability> capabilities() {
                return Set.of(AiCapability.TEXT_STREAMING, AiCapability.TOOL_CALLING);
            }
        };
        var caps = adapter.capabilities();
        assertEquals(2, caps.size());
        assertTrue(caps.contains(AiCapability.TOOL_CALLING));
    }

    @Test
    void streamDelegatesToSession() {
        AiStreamingAdapter<String> adapter = new AiStreamingAdapter<>() {
            @Override
            public String name() { return "delegator"; }

            @Override
            public void stream(String request, StreamingSession session) {
                session.send(request);
                session.complete();
            }
        };
        var session = new CollectingSession("stream-test");
        adapter.stream("hello", session);
        assertEquals("hello", session.text());
    }
}
