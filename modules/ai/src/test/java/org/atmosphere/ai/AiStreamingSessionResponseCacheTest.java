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
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the response-cache gate on the {@code @AiEndpoint} websocket path.
 *
 * <p>The gate previously existed only in {@link AiPipeline}, so an application
 * that configured {@code atmosphere.ai.cache.*} got a cache on its
 * {@code @Coordinator} / channel traffic and silently none on its
 * {@code @AiEndpoint} traffic — the same configuration key meaning two different
 * things depending on which entry the request came in on (Correctness Invariant
 * #7). These tests drive a real {@link InMemoryResponseCache} through
 * {@link AiStreamingSession#stream} and assert both that the gate serves a
 * second identical prompt without touching the runtime, and — just as important
 * — that each of the five safety preconditions keeps it shut, because
 * {@code CachingStreamingSession} captures only text and would silently drop
 * tool, structured-output and RAG frames on replay.</p>
 */
class AiStreamingSessionResponseCacheTest {

    private RecordingSession delegate;
    private AtmosphereResource resource;
    private CountingRuntime runtime;
    private InMemoryResponseCache cache;

    @BeforeEach
    void setUp() {
        delegate = new RecordingSession();
        resource = mock(AtmosphereResource.class);
        when(resource.getRequest()).thenReturn(mock(org.atmosphere.cpr.AtmosphereRequest.class));
        when(resource.uuid()).thenReturn("session-uuid");
        runtime = new CountingRuntime();
        cache = new InMemoryResponseCache(16);
    }

    private AiStreamingSession session(ToolRegistry registry,
                                       List<AiGuardrail> guardrails,
                                       List<ContextProvider> providers,
                                       Class<?> responseType) {
        var session = new AiStreamingSession(delegate, runtime, "sys", "model",
                List.of(), resource, null, registry, guardrails, providers,
                AiMetrics.NOOP, responseType);
        session.setCachePolicy(CacheHint.CachePolicy.CONSERVATIVE);
        session.setResponseCache(cache, Duration.ofMinutes(5));
        return session;
    }

    private AiStreamingSession cacheableSession() {
        return session(null, List.of(), List.of(), null);
    }

    @Test
    void secondIdenticalPromptIsServedFromCacheWithoutCallingTheRuntime() {
        cacheableSession().stream("what is the weather in Paris?");

        assertEquals(1, runtime.calls.get(), "runtime fires on the miss");
        assertEquals(Boolean.FALSE, delegate.metadata.get(AiPipeline.CACHE_HIT_METADATA_KEY),
                "a miss reports ai.cache.hit=false so a client can tell miss from skip");
        var firstText = delegate.fullText();
        assertFalse(firstText.isEmpty(), "runtime response streamed on the miss");

        delegate = new RecordingSession();
        cacheableSession().stream("what is the weather in Paris?");

        assertEquals(1, runtime.calls.get(),
                "the second identical prompt must be served from cache, not the runtime");
        assertEquals(Boolean.TRUE, delegate.metadata.get(AiPipeline.CACHE_HIT_METADATA_KEY),
                "a hit reports ai.cache.hit=true");
        assertEquals(firstText, delegate.fullText(), "the replayed text matches the captured text");
        assertTrue(delegate.completed, "the hit path completes the session");
    }

    @Test
    void noCachePolicyMeansTheGateIsNeverConsulted() {
        var session = new AiStreamingSession(delegate, runtime, "sys", "model",
                List.of(), resource, null, null, List.of(), List.of(), AiMetrics.NOOP, null);
        session.setResponseCache(cache, Duration.ofMinutes(5));

        session.stream("hello");
        session.stream("hello");

        assertEquals(2, runtime.calls.get(), "without a CacheHint the runtime runs every time");
        assertFalse(delegate.metadata.containsKey(AiPipeline.CACHE_HIT_METADATA_KEY),
                "no cache signal is emitted when the gate is not consulted");
    }

    @Test
    void noCacheInstalledLeavesDispatchUnchanged() {
        var session = new AiStreamingSession(delegate, runtime, "sys", "model",
                List.of(), resource, null, null, List.of(), List.of(), AiMetrics.NOOP, null);
        session.setCachePolicy(CacheHint.CachePolicy.CONSERVATIVE);

        session.stream("hello");
        session.stream("hello");

        assertEquals(2, runtime.calls.get(), "no cache source means no caching");
        assertFalse(delegate.metadata.containsKey(AiPipeline.CACHE_HIT_METADATA_KEY));
    }

    // --- the five safety preconditions: each must keep the gate shut ---

    @Test
    void latentRegistryToolsKeepTheGateShut() {
        var registry = new DefaultToolRegistry();
        registry.register(ToolDefinition.builder("lookup", "Look something up")
                .executor(args -> "ok").build());

        var session = session(registry, List.of(), List.of(), null);
        session.stream("hello");
        session.stream("hello");

        assertEquals(2, runtime.calls.get(),
                "a registry holding tools means a future turn could call one — "
                        + "a text-only cached replay would drop the tool frames");
    }

    @Test
    void structuredOutputKeepsTheGateShut() {
        var session = session(null, List.of(), List.of(), Map.class);
        session.stream("hello");
        session.stream("hello");

        assertEquals(2, runtime.calls.get(),
                "structured output emits field events, not plain text — not replayable");
    }

    @Test
    void ragProvidersKeepTheGateShut() {
        List<ContextProvider> providers = List.of(new StubContextProvider());

        var session = session(null, List.of(), providers, null);
        session.stream("hello");
        session.stream("hello");

        assertEquals(2, runtime.calls.get(),
                "RAG augments the prompt at runtime, so identical messages may map "
                        + "to different retrieved context and different answers");
    }

    @Test
    void guardrailsKeepTheGateShut() {
        List<AiGuardrail> guardrails = List.of(new PassThroughGuardrail());

        var session = session(null, guardrails, List.of(), null);
        session.stream("hello");
        session.stream("hello");

        assertEquals(2, runtime.calls.get(),
                "guardrails may mutate the request, so a cached answer would bypass filtering");
    }

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

    private static final class StubContextProvider implements ContextProvider {
        @Override
        public List<Document> retrieve(String query, int maxResults) {
            return List.of();
        }
    }

    private static final class PassThroughGuardrail implements AiGuardrail {
        @Override
        public GuardrailResult inspectRequest(AiRequest request) {
            return GuardrailResult.pass();
        }
    }

    private static final class RecordingSession implements StreamingSession {
        final List<String> sentTexts = new ArrayList<>();
        final Map<String, Object> metadata = new HashMap<>();
        boolean completed;

        String fullText() {
            return String.join("", sentTexts);
        }

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
            return false;
        }
    }
}
