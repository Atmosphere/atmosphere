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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.cache.InMemoryResponseCache;
import org.atmosphere.ai.llm.CacheHint;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gap #5 — drive the {@link AiPipeline} response-cache gate through its
 * real public entry so every toggle exercised by the e2e spec corresponds to
 * an actual framework execution, not a recomputed white-box formula.
 *
 * <p>Each request selects a toggle that flips exactly one of the five
 * cache-skip gates inside {@link AiPipeline#execute(String, String, StreamingSession, Map)}:
 * {@code tool}, {@code rag}, {@code guardrail}, {@code structured}, or
 * {@code none} (baseline). The handler constructs a per-toggle
 * {@link AiPipeline} seeded with an {@link InMemoryResponseCache} and a
 * shared stub {@link AgentRuntime} that emits a deterministic response, then
 * runs the pipeline TWICE with the same prompt so the test can observe the
 * {@code ai.cache.hit} metadata transition:
 *
 * <ul>
 *   <li>First run → framework emits {@code ai.cache.hit=false} when the gate
 *       opens (baseline) or omits the signal entirely when the gate
 *       short-circuits (tool/rag/guardrail/structured).</li>
 *   <li>Second run with the same prompt → framework emits
 *       {@code ai.cache.hit=true} on the baseline toggle (cache actually
 *       served the replay) and stays silent on the toggles that short-circuit
 *       the gate, proving each toggle flips the expected branch end-to-end.</li>
 * </ul>
 *
 * <p>The handler publishes each run's observed {@code ai.cache.hit} value as
 * metadata on the user-visible WebSocket session under
 * {@code cacheSkip.run1.hit} / {@code cacheSkip.run2.hit} so the Playwright
 * spec can read the transition directly without reaching into framework
 * internals. This replaces the prior white-box formula mirror that pinned the
 * gate variables from outside the pipeline — which was itself a workaround
 * for the pre-fix state where the cache branch was unreachable via the public
 * API (see commit history for Gap #5).
 *
 * <p>Query input: a single line of text, first whitespace-delimited token is
 * the toggle, remainder is the prompt body. Example: {@code tool hello}.
 */
public class CacheSkipTestHandler implements AtmosphereHandler {

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();
        var reader = resource.getRequest().getReader();
        var line = reader.readLine();
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        var trimmed = line.trim();
        var split = trimmed.split("\\s+", 2);
        var toggle = split[0].toLowerCase();
        var promptBody = split.length > 1 ? split[1] : "same-prompt";
        Thread.ofVirtual().name("cache-skip-test").start(() -> handle(toggle, promptBody, resource));
    }

    private void handle(String toggle, String promptBody, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);
        try {
            runHandle(toggle, promptBody, session);
        } catch (RuntimeException e) {
            session.sendMetadata("cacheSkip.error",
                    e.getClass().getName() + ":" + e.getMessage());
            session.error(e);
        }
    }

    private void runHandle(String toggle, String promptBody, StreamingSession session) {

        var toolRegistry = new DefaultToolRegistry();
        List<AiGuardrail> guardrails = List.of();
        List<ContextProvider> contextProviders = List.of();
        Class<?> responseType = null;

        switch (toggle) {
            case "tool" -> toolRegistry.register(
                    ToolDefinition.builder("noop_tool", "no-op")
                            .returnType("string")
                            .executor(args -> "ok")
                            .build());
            case "rag" -> contextProviders = List.of(new NoopContextProvider());
            case "guardrail" -> guardrails = List.of(new NoopGuardrail());
            case "structured" -> responseType = Map.class;
            case "none" -> { /* baseline: all gates false */ }
            default -> toggle = "none";
        }

        session.sendMetadata("cacheSkip.toggle", toggle);

        // Stub runtime that emits a deterministic frame so we can prove the
        // second execute() call replays from cache on the baseline toggle.
        // Any runtime invocation adds an incrementing tag to the session
        // metadata so the spec can assert the runtime actually ran vs. served
        // from cache (cache hit bypasses the runtime entirely).
        var runtimeCallCount = new java.util.concurrent.atomic.AtomicInteger();
        AgentRuntime stubRuntime = new StubRuntime(runtimeCallCount);

        // Fresh pipeline per request so the ResponseCache state stays
        // isolated between toggles (avoids cross-toggle pollution of the
        // test matrix). The pipeline opts into conservative caching via
        // setDefaultCachePolicy so every execute() call rides the fix from
        // Bug #2 — AiRequest metadata is no longer hardcoded to Map.of().
        var cache = new InMemoryResponseCache(16);
        var pipeline = new AiPipeline(stubRuntime, "you are helpful", "cache-skip-model",
                null, toolRegistry, guardrails, contextProviders, AiMetrics.NOOP, responseType);
        pipeline.setResponseCache(cache, java.time.Duration.ofMinutes(1));
        pipeline.setDefaultCachePolicy(CacheHint.CachePolicy.CONSERVATIVE);

        // First execute — cache miss on the baseline toggle, gate-skip on
        // tool/rag/guardrail/structured. Capture the emitted ai.cache.hit
        // value (false on miss, absent on skip) via a tee session.
        var firstObserver = new CacheHitObserverSession(session);
        pipeline.execute("cache-skip-client", promptBody, firstObserver);
        session.sendMetadata("cacheSkip.run1.runtimeCalls", runtimeCallCount.get());
        session.sendMetadata("cacheSkip.run1.hit",
                firstObserver.observedHit != null ? firstObserver.observedHit : "absent");

        // Second execute with the same prompt — baseline toggle should now
        // serve from cache (framework emits ai.cache.hit=true and skips the
        // runtime entirely). tool/rag/guardrail/structured toggles never
        // reach the gate so no cache signal is emitted and the runtime fires
        // again.
        var secondObserver = new CacheHitObserverSession(session);
        pipeline.execute("cache-skip-client", promptBody, secondObserver);
        session.sendMetadata("cacheSkip.run2.runtimeCalls", runtimeCallCount.get());
        session.sendMetadata("cacheSkip.run2.hit",
                secondObserver.observedHit != null ? secondObserver.observedHit : "absent");

        session.complete();
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()
                || event.isClosedByClient() || event.isClosedByApplication()) {
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw && raw.message() instanceof String json) {
            event.getResource().getResponse().write(json);
            event.getResource().getResponse().flushBuffer();
        }
    }

    @Override
    public void destroy() {
    }

    /**
     * Delegating session that tees every frame to the user-visible outer
     * session and separately captures the {@code ai.cache.hit} value so the
     * handler can publish it as a run-scoped metadata key. The outer session
     * never forwards {@code send} text to the wire as the test only asserts
     * against metadata — this keeps the Playwright WsClient's token view
     * focused on cache observability rather than the stub runtime's body.
     */
    private static final class CacheHitObserverSession implements StreamingSession {
        private final StreamingSession outer;
        volatile Boolean observedHit;
        private volatile boolean closed;

        CacheHitObserverSession(StreamingSession outer) {
            this.outer = outer;
        }

        @Override
        public String sessionId() {
            return outer.sessionId();
        }

        @Override
        public void send(String text) {
            // Intentionally swallowed — test asserts metadata only.
        }

        @Override
        public void sendMetadata(String key, Object value) {
            if (AiPipeline.CACHE_HIT_METADATA_KEY.equals(key) && value instanceof Boolean b) {
                observedHit = b;
            }
            // Do not forward stub-runtime metadata to the outer session
            // (would pollute the WsClient view across runs).
        }

        @Override
        public void progress(String message) {
            // no-op
        }

        @Override
        public void complete() {
            closed = true;
        }

        @Override
        public void complete(String summary) {
            closed = true;
        }

        @Override
        public void error(Throwable t) {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }

    private static final class StubRuntime implements AgentRuntime {
        private final java.util.concurrent.atomic.AtomicInteger calls;

        StubRuntime(java.util.concurrent.atomic.AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public String name() {
            return "cache-skip-stub";
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
            session.send("stub-response for: " + context.message());
            session.complete();
        }
    }

    /**
     * No-op {@link ContextProvider} whose mere presence on the pipeline
     * flips the {@code hasRag} gate and causes the cache gate to
     * short-circuit (see {@link AiPipeline} Javadoc).
     */
    private static final class NoopContextProvider implements ContextProvider {
        private final Map<String, List<Document>> empty = new ConcurrentHashMap<>();

        @Override
        public List<Document> retrieve(String query, int maxResults) {
            return empty.computeIfAbsent(query, k -> List.of());
        }
    }

    /**
     * No-op {@link AiGuardrail} whose presence flips {@code hasGuardrails}
     * and short-circuits the cache gate.
     */
    private static final class NoopGuardrail implements AiGuardrail {
        // Inherits default pass() behaviour; mere presence flips the gate.
    }
}
