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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 contract: {@link StreamingSession#usage(TokenUsage)} must translate to
 * the legacy {@code ai.tokens.input/output/cached_input/total/model} metadata
 * keys via the default sink so existing {@code MetricsCapturingSession},
 * {@code MicrometerAiMetrics}, and budget interceptors keep working without
 * any change after runtimes migrate to the typed call site.
 */
class TokenUsageTest {

    private static final class RecordingSession implements StreamingSession {
        final Map<String, Object> metadata = new LinkedHashMap<>();

        @Override public String sessionId() { return "test"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { metadata.put(key, value); }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
    }

    @Test
    void hasCountsReturnsFalseForAllZero() {
        assertFalse(new TokenUsage(0, 0, 0, 0, null).hasCounts());
    }

    @Test
    void hasCountsReturnsTrueWhenAnyFieldPositive() {
        assertTrue(new TokenUsage(1, 0, 0, 0, null).hasCounts());
        assertTrue(new TokenUsage(0, 1, 0, 0, null).hasCounts());
        assertTrue(new TokenUsage(0, 0, 1, 0, null).hasCounts());
        assertTrue(new TokenUsage(0, 0, 0, 1, null).hasCounts());
    }

    @Test
    void ofFactoriesProduceExpectedTotal() {
        assertEquals(30L, TokenUsage.of(10L, 20L).total());
        assertEquals(123L, TokenUsage.of(10L, 20L, 123L).total());
        assertEquals("gpt-4o", TokenUsage.of(1L, 2L, 3L, "gpt-4o").model());
    }

    @Test
    void defaultSinkTranslatesToLegacyMetadataKeys() {
        var session = new RecordingSession();
        session.usage(new TokenUsage(10L, 20L, 4L, 30L, "gpt-4o"));

        assertEquals(10L, session.metadata.get("ai.tokens.input"));
        assertEquals(20L, session.metadata.get("ai.tokens.output"));
        assertEquals(4L, session.metadata.get("ai.tokens.cached_input"));
        assertEquals(30L, session.metadata.get("ai.tokens.total"));
        assertEquals("gpt-4o", session.metadata.get("ai.tokens.model"));
    }

    @Test
    void defaultSinkSkipsZeroFields() {
        var session = new RecordingSession();
        session.usage(new TokenUsage(10L, 0L, 0L, 10L, null));

        assertEquals(10L, session.metadata.get("ai.tokens.input"));
        assertEquals(10L, session.metadata.get("ai.tokens.total"));
        assertFalse(session.metadata.containsKey("ai.tokens.output"),
                "zero output tokens must not emit metadata");
        assertFalse(session.metadata.containsKey("ai.tokens.cached_input"),
                "zero cached input tokens must not emit metadata");
        assertFalse(session.metadata.containsKey("ai.tokens.model"),
                "null model must not emit metadata");
    }

    @Test
    void defaultSinkIgnoresNullUsage() {
        var session = new RecordingSession();
        session.usage(null);
        assertTrue(session.metadata.isEmpty());
    }

    @Test
    void toolCallDeltaEmitsKeyedMetadata() {
        var session = new RecordingSession();
        session.toolCallDelta("tc-abc", "{\"city");
        assertEquals("{\"city", session.metadata.get("ai.toolCall.delta.tc-abc"));
    }

    @Test
    void toolCallDeltaIgnoresNullOrEmptyFragments() {
        var session = new RecordingSession();
        session.toolCallDelta(null, "{\"x");
        session.toolCallDelta("tc-1", null);
        session.toolCallDelta("tc-1", "");
        assertTrue(session.metadata.isEmpty());
    }
}
