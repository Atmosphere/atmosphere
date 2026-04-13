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

import org.atmosphere.ai.cache.InMemoryResponseCache;
import org.atmosphere.ai.llm.CacheHint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for Bug #2 (AiPipeline cache branch was dead code on
 * the public entry) and Bug #4 ({@code ai.cache.hit} wire signal).
 *
 * <p>Before the fix, {@link AiPipeline#execute(String, String, StreamingSession)}
 * hardcoded {@code AiRequest.metadata()} to {@code Map.of()}, which meant
 * {@link CacheHint#from(AgentExecutionContext)} always returned
 * {@link CacheHint#none()} and the entire {@code cacheSafe} branch was
 * unreachable via the public entry point. These tests drive the public entry
 * with a real {@link InMemoryResponseCache} and assert the framework now
 * hits the cache and emits the canonical {@code ai.cache.hit} metadata
 * signal.</p>
 */
class AiPipelineResponseCacheTest {

    @Test
    void defaultPolicyPipelineHitsCacheOnSecondIdenticalPrompt() {
        var cache = new InMemoryResponseCache(8);
        var runtime = new CountingRuntime();
        var pipeline = new AiPipeline(runtime, "sys", "test-model",
                null, null, List.of(), List.of(), AiMetrics.NOOP);
        pipeline.setResponseCache(cache, Duration.ofMinutes(1));
        pipeline.setDefaultCachePolicy(CacheHint.CachePolicy.CONSERVATIVE);

        // First execute — cache miss, runtime fires, response stored.
        var first = new RecordingSession();
        pipeline.execute("client-1", "hello world", first);
        assertEquals(1, runtime.calls.get(), "runtime fires on miss");
        assertEquals(Boolean.FALSE, first.metadata.get(AiPipeline.CACHE_HIT_METADATA_KEY),
                "miss emits ai.cache.hit=false");
        assertTrue(first.completed, "miss path completes cleanly");
        assertFalse(first.sentTexts.isEmpty(), "runtime response streamed on miss");

        // Second execute — same prompt → cache hit, runtime NOT called.
        var second = new RecordingSession();
        pipeline.execute("client-1", "hello world", second);
        assertEquals(1, runtime.calls.get(),
                "runtime must NOT be called on cache hit");
        assertEquals(Boolean.TRUE, second.metadata.get(AiPipeline.CACHE_HIT_METADATA_KEY),
                "hit emits ai.cache.hit=true");
        assertEquals(first.fullText(), second.fullText(),
                "cached text must match the miss-path response");
        assertTrue(second.completed, "hit path completes cleanly");
    }

    @Test
    void perCallCacheHintOverridesDefaultPolicyNone() {
        // The pipeline has no default cache policy installed, but the caller
        // passes a per-request CacheHint via the 4-arg execute() overload.
        // Proves the extraMetadata path reaches the gate.
        var cache = new InMemoryResponseCache(8);
        var runtime = new CountingRuntime();
        var pipeline = new AiPipeline(runtime, "sys", "test-model",
                null, null, List.of(), List.of(), AiMetrics.NOOP);
        pipeline.setResponseCache(cache, Duration.ofMinutes(1));

        Map<String, Object> meta = new HashMap<>();
        meta.put(CacheHint.METADATA_KEY, CacheHint.conservative());

        var first = new RecordingSession();
        pipeline.execute("client-2", "hi there", first, meta);
        assertEquals(1, runtime.calls.get());
        assertEquals(Boolean.FALSE, first.metadata.get(AiPipeline.CACHE_HIT_METADATA_KEY));

        var second = new RecordingSession();
        pipeline.execute("client-2", "hi there", second, meta);
        assertEquals(1, runtime.calls.get(),
                "caller-supplied hint reaches the gate and serves from cache");
        assertEquals(Boolean.TRUE, second.metadata.get(AiPipeline.CACHE_HIT_METADATA_KEY));
    }

    @Test
    void noCacheHintMeansNoCacheConsultationNoMetadataFrame() {
        // With no default policy and no per-request hint, the gate is closed
        // (CacheHint.from() returns none()) and no ai.cache.hit frame is
        // emitted on either run.
        var cache = new InMemoryResponseCache(8);
        var runtime = new CountingRuntime();
        var pipeline = new AiPipeline(runtime, "sys", "test-model",
                null, null, List.of(), List.of(), AiMetrics.NOOP);
        pipeline.setResponseCache(cache, Duration.ofMinutes(1));

        var first = new RecordingSession();
        pipeline.execute("client-3", "same", first);
        var second = new RecordingSession();
        pipeline.execute("client-3", "same", second);

        assertEquals(2, runtime.calls.get(),
                "both runs hit the runtime when the cache hint is unset");
        assertFalse(first.metadata.containsKey(AiPipeline.CACHE_HIT_METADATA_KEY),
                "no cache frame emitted without a hint");
        assertFalse(second.metadata.containsKey(AiPipeline.CACHE_HIT_METADATA_KEY),
                "no cache frame emitted without a hint");
    }

    @Test
    void callerCacheHintOverridesDefaultPolicy() {
        // Pipeline default is CONSERVATIVE but caller downgrades to NONE by
        // explicitly passing CacheHint.none() in per-request metadata. The
        // caller's hint must win (putIfAbsent on default merge).
        var cache = new InMemoryResponseCache(8);
        var runtime = new CountingRuntime();
        var pipeline = new AiPipeline(runtime, "sys", "test-model",
                null, null, List.of(), List.of(), AiMetrics.NOOP);
        pipeline.setResponseCache(cache, Duration.ofMinutes(1));
        pipeline.setDefaultCachePolicy(CacheHint.CachePolicy.CONSERVATIVE);

        Map<String, Object> meta = new HashMap<>();
        meta.put(CacheHint.METADATA_KEY, CacheHint.none());

        var first = new RecordingSession();
        pipeline.execute("client-4", "hello", first, meta);
        var second = new RecordingSession();
        pipeline.execute("client-4", "hello", second, meta);

        assertEquals(2, runtime.calls.get(),
                "caller-supplied CacheHint.none() disables the gate for this request");
        assertFalse(first.metadata.containsKey(AiPipeline.CACHE_HIT_METADATA_KEY));
        assertFalse(second.metadata.containsKey(AiPipeline.CACHE_HIT_METADATA_KEY));
    }

    // --- Helpers ---

    private static final class CountingRuntime implements AgentRuntime {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int priority() {
            return -1;
        }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            calls.incrementAndGet();
            session.send("runtime-" + calls.get() + ":" + context.message());
            session.complete();
        }
    }

    private static final class RecordingSession implements StreamingSession {
        final List<String> sentTexts = new ArrayList<>();
        final Map<String, Object> metadata = new HashMap<>();
        boolean completed;

        @Override
        public String sessionId() {
            return "test-session";
        }

        @Override
        public void send(String text) {
            sentTexts.add(text);
        }

        @Override
        public void sendMetadata(String key, Object value) {
            metadata.put(key, value);
        }

        @Override
        public void progress(String message) {
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void complete(String summary) {
            completed = true;
        }

        @Override
        public void error(Throwable t) {
        }

        @Override
        public boolean isClosed() {
            return completed;
        }

        String fullText() {
            return String.join("", sentTexts);
        }
    }
}
