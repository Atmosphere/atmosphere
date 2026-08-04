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
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@link AiInterceptor#beforeCompletion} against a REAL
 * {@link DefaultStreamingSession} whose broadcast frames are captured at the
 * wire. The real leaf matters: runtimes call {@code session.complete()}
 * mid-execute, so the leaf is CLOSED by the time {@code postProcess} runs and
 * silently drops any metadata sent from it — a hand-rolled capturing session
 * with {@code isClosed()==false} can never reproduce that bug. These tests
 * assert frame ORDER on the wire: metadata sent from {@code beforeCompletion}
 * must precede the terminal {@code complete} frame.
 */
class AiInterceptorBeforeCompletionTest {

    /** One wire: a mocked resource whose broadcaster records every frame. */
    private record Wire(AtmosphereResource resource, List<String> frames,
                        StreamingSession delegate) {
    }

    private static Wire wire(String sessionId, String uuid) {
        var frames = new CopyOnWriteArrayList<String>();
        var resource = mock(AtmosphereResource.class);
        var broadcaster = mock(Broadcaster.class);
        when(resource.getRequest()).thenReturn(mock(AtmosphereRequest.class));
        when(resource.uuid()).thenReturn(uuid);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(any(), anySet())).thenAnswer(inv -> {
            frames.add(String.valueOf(((RawMessage) inv.getArgument(0)).message()));
            return null;
        });
        return new Wire(resource, frames, StreamingSessions.start(sessionId, resource));
    }

    private static int indexOfFrame(List<String> frames, String token) {
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).contains(token)) {
                return i;
            }
        }
        return -1;
    }

    /** Runtime shaped like the real bridges: completes the session mid-execute. */
    private static AgentRuntime completingRuntime() {
        return new AgentRuntime() {
            @Override
            public String name() {
                return "completing";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public int priority() {
                return 0;
            }

            @Override
            public void configure(AiConfig.LlmSettings settings) {
            }

            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                session.send("answer");
                session.complete();
            }
        };
    }

    @Test
    void metadataSentFromBeforeCompletionReachesTheWireBeforeTheCompleteFrame() {
        var w = wire("order-session", "order-uuid");
        var interceptor = new AiInterceptor() {
            @Override
            public void beforeCompletion(AiRequest request, StreamingSession session,
                                         AtmosphereResource resource) {
                session.sendMetadata("routing.model", "demo-model");
            }
        };
        var session = new AiStreamingSession(w.delegate(), completingRuntime(),
                "", null, List.of(interceptor), w.resource());

        session.stream("hi");

        var metadataIdx = indexOfFrame(w.frames(), "\"routing.model\"");
        var completeIdx = indexOfFrame(w.frames(), "\"type\":\"complete\"");
        assertTrue(metadataIdx >= 0,
                "beforeCompletion metadata must reach the wire; frames: " + w.frames());
        assertTrue(completeIdx >= 0,
                "terminal complete frame must reach the wire; frames: " + w.frames());
        assertTrue(metadataIdx < completeIdx,
                "beforeCompletion metadata must precede the terminal complete frame "
                        + "(atmosphere.js snapshots routing state AT the complete frame); "
                        + "frames: " + w.frames());
    }

    @Test
    void firesExactlyOncePerStreamInvocationAndRearmsOnTheNextStream() {
        var w = wire("rearm-session", "rearm-uuid");
        var count = new AtomicInteger();
        var interceptor = new AiInterceptor() {
            @Override
            public void beforeCompletion(AiRequest request, StreamingSession session,
                                         AtmosphereResource resource) {
                count.incrementAndGet();
            }
        };
        // A runtime that completes TWICE in one turn: the once-guard must
        // collapse the second terminal call.
        var doubleCompleting = new AgentRuntime() {
            @Override
            public String name() {
                return "double-completing";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public int priority() {
                return 0;
            }

            @Override
            public void configure(AiConfig.LlmSettings settings) {
            }

            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                session.send("x");
                session.complete();
                session.complete();
            }
        };
        var session = new AiStreamingSession(w.delegate(), doubleCompleting,
                "", null, List.of(interceptor), w.resource());

        session.stream("first turn");
        assertEquals(1, count.get(),
                "the hook fires exactly once per stream() even when the runtime completes twice");

        session.stream("second turn");
        assertEquals(2, count.get(),
                "a second stream() on the same session must re-arm the hook (per-turn guard, "
                        + "not per-session)");
    }

    @Test
    void runsLifoMirroringPostProcess() {
        var w = wire("lifo-session", "lifo-uuid");
        var order = new CopyOnWriteArrayList<String>();
        AiInterceptor first = new AiInterceptor() {
            @Override
            public void beforeCompletion(AiRequest request, StreamingSession session,
                                         AtmosphereResource resource) {
                order.add("first");
            }
        };
        AiInterceptor second = new AiInterceptor() {
            @Override
            public void beforeCompletion(AiRequest request, StreamingSession session,
                                         AtmosphereResource resource) {
                order.add("second");
            }
        };
        var session = new AiStreamingSession(w.delegate(), completingRuntime(),
                "", null, List.of(first, second), w.resource());

        session.stream("hi");

        assertEquals(List.of("second", "first"), order,
                "beforeCompletion runs LIFO, mirroring postProcess");
    }

    @Test
    void throwingHookNeverSuppressesTheTerminalFrameAndPostProcessStillRuns() {
        var w = wire("throwing-session", "throwing-uuid");
        var postProcessed = new AtomicInteger();
        var interceptor = new AiInterceptor() {
            @Override
            public void beforeCompletion(AiRequest request, StreamingSession session,
                                         AtmosphereResource resource) {
                throw new IllegalStateException("hook exploded");
            }

            @Override
            public void postProcess(AiRequest request, AtmosphereResource resource) {
                postProcessed.incrementAndGet();
            }
        };
        var session = new AiStreamingSession(w.delegate(), completingRuntime(),
                "", null, List.of(interceptor), w.resource());

        session.stream("hi");

        assertTrue(indexOfFrame(w.frames(), "\"type\":\"complete\"") >= 0,
                "a throwing beforeCompletion must never suppress the terminal frame "
                        + "(Correctness Invariant #2); frames: " + w.frames());
        assertEquals(1, postProcessed.get(),
                "postProcess still runs after a throwing beforeCompletion");
    }

    @Test
    void hookThrowingAnErrorNeverSuppressesTheTerminalFrame() {
        var w = wire("assertion-session", "assertion-uuid");
        var interceptor = new AiInterceptor() {
            @Override
            public void beforeCompletion(AiRequest request, StreamingSession session,
                                         AtmosphereResource resource) {
                // Error, not Exception: an escaping AssertionError would abort
                // complete() before the terminal frame is forwarded — and the
                // endpoint handler rethrows Errors without routing error(), so
                // the client would hang forever.
                throw new AssertionError("hook assertion failed");
            }
        };
        var session = new AiStreamingSession(w.delegate(), completingRuntime(),
                "", null, List.of(interceptor), w.resource());

        session.stream("hi");

        assertTrue(indexOfFrame(w.frames(), "\"type\":\"complete\"") >= 0,
                "a hook throwing an Error (AssertionError) must never suppress the terminal "
                        + "frame (Correctness Invariant #2); frames: " + w.frames());
    }

    @Test
    void errorThenCompleteDoesNotFireBeforeCompletion() {
        var w = wire("error-then-complete-session", "error-then-complete-uuid");
        var count = new AtomicInteger();
        var interceptor = new AiInterceptor() {
            @Override
            public void beforeCompletion(AiRequest request, StreamingSession session,
                                         AtmosphereResource resource) {
                count.incrementAndGet();
            }
        };
        // Runtime bridges complete() after error() on a caller-initiated
        // cancel; the error must have DISARMED the turn so that trailing
        // complete() cannot fire the hook for a turn that did not complete.
        var erroringThenCompleting = new AgentRuntime() {
            @Override
            public String name() {
                return "erroring-then-completing";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public int priority() {
                return 0;
            }

            @Override
            public void configure(AiConfig.LlmSettings settings) {
            }

            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                session.send("partial");
                session.error(new RuntimeException("cancelled upstream"));
                session.complete();
            }
        };
        var session = new AiStreamingSession(w.delegate(), erroringThenCompleting,
                "", null, List.of(interceptor), w.resource());

        session.stream("hi");

        assertEquals(0, count.get(),
                "error() must consume the armed context WITHOUT firing, so a trailing "
                        + "complete() cannot fire the hook for a turn that never completed");
        assertTrue(indexOfFrame(w.frames(), "\"type\":\"error\"") >= 0,
                "the error terminal frame still reaches the wire; frames: " + w.frames());
    }

    @Test
    void reentrantTerminalCallFromAHookIsIgnoredAndTheOuterCallForwardsOneCompleteFrame() {
        var w = wire("reentrant-session", "reentrant-uuid");
        var interceptor = new AiInterceptor() {
            @Override
            public void beforeCompletion(AiRequest request, StreamingSession session,
                                         AtmosphereResource resource) {
                // Illegal per the hook contract: terminal methods are latched
                // while hooks run. The call must be a logged no-op — without
                // the latch it would close the leaf mid-hook and the metadata
                // below would be dropped on a closed session.
                session.complete();
                session.sendMetadata("routing.model", "after-reentrant-complete");
            }
        };
        var session = new AiStreamingSession(w.delegate(), completingRuntime(),
                "", null, List.of(interceptor), w.resource());

        session.stream("hi");

        var completeFrames = 0;
        for (var frame : w.frames()) {
            if (frame.contains("\"type\":\"complete\"")) {
                completeFrames++;
            }
        }
        assertEquals(1, completeFrames,
                "exactly one complete frame reaches the wire despite the reentrant "
                        + "complete(); frames: " + w.frames());
        var metadataIdx = indexOfFrame(w.frames(), "after-reentrant-complete");
        var completeIdx = indexOfFrame(w.frames(), "\"type\":\"complete\"");
        assertTrue(metadataIdx >= 0 && metadataIdx < completeIdx,
                "metadata sent after the ignored reentrant complete() must still reach the "
                        + "wire before the outer terminal frame; frames: " + w.frames());
    }

    @Test
    void errorTerminalPathDoesNotFireBeforeCompletion() {
        var w = wire("error-session", "error-uuid");
        var count = new AtomicInteger();
        var interceptor = new AiInterceptor() {
            @Override
            public void beforeCompletion(AiRequest request, StreamingSession session,
                                         AtmosphereResource resource) {
                count.incrementAndGet();
            }
        };
        var erroring = new AgentRuntime() {
            @Override
            public String name() {
                return "erroring";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public int priority() {
                return 0;
            }

            @Override
            public void configure(AiConfig.LlmSettings settings) {
            }

            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                session.send("partial");
                session.error(new RuntimeException("upstream failed"));
            }
        };
        var session = new AiStreamingSession(w.delegate(), erroring,
                "", null, List.of(interceptor), w.resource());

        session.stream("hi");

        assertEquals(0, count.get(),
                "beforeCompletion is a completion hook — the error() terminal path never fires it");
        assertTrue(indexOfFrame(w.frames(), "\"type\":\"error\"") >= 0,
                "the error terminal frame still reaches the wire; frames: " + w.frames());
    }

    @Test
    void endpointResponseCacheHitFiresBeforeCompletionPreComplete() {
        var cache = new InMemoryResponseCache(16);
        var count = new AtomicInteger();
        var interceptor = new AiInterceptor() {
            @Override
            public void beforeCompletion(AiRequest request, StreamingSession session,
                                         AtmosphereResource resource) {
                count.incrementAndGet();
                session.sendMetadata("routing.model", "metered-model");
            }
        };
        var runtimeCalls = new AtomicInteger();
        var countingRuntime = new AgentRuntime() {
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
                return 0;
            }

            @Override
            public void configure(AiConfig.LlmSettings settings) {
            }

            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                runtimeCalls.incrementAndGet();
                session.send("fresh answer");
                session.complete();
            }
        };

        // Miss turn: runtime runs, response captured into the shared cache.
        var missWire = wire("cache-miss-session", "cache-uuid");
        var missSession = new AiStreamingSession(missWire.delegate(), countingRuntime,
                "sys", "model", List.of(interceptor), missWire.resource(), null,
                null, List.of(), List.of(), AiMetrics.NOOP, null);
        missSession.setCachePolicy(CacheHint.CachePolicy.CONSERVATIVE);
        missSession.setResponseCache(cache, Duration.ofMinutes(5));
        missSession.stream("same question");
        assertEquals(1, runtimeCalls.get());
        assertEquals(1, count.get(), "the miss turn fires the hook once");

        // Hit turn on a fresh session/delegate sharing the cache: the runtime
        // is never consulted, but the turn still completed — the hook fires
        // and its metadata still precedes the replayed complete frame.
        var hitWire = wire("cache-hit-session", "cache-uuid");
        var hitSession = new AiStreamingSession(hitWire.delegate(), countingRuntime,
                "sys", "model", List.of(interceptor), hitWire.resource(), null,
                null, List.of(), List.of(), AiMetrics.NOOP, null);
        hitSession.setCachePolicy(CacheHint.CachePolicy.CONSERVATIVE);
        hitSession.setResponseCache(cache, Duration.ofMinutes(5));
        hitSession.stream("same question");

        assertEquals(1, runtimeCalls.get(), "the second identical prompt is served from cache");
        assertEquals(2, count.get(), "the cache-hit turn fires the hook too — the turn completed");
        var metadataIdx = indexOfFrame(hitWire.frames(), "\"routing.model\"");
        var completeIdx = indexOfFrame(hitWire.frames(), "\"type\":\"complete\"");
        assertTrue(metadataIdx >= 0 && completeIdx >= 0 && metadataIdx < completeIdx,
                "hook metadata must precede the complete frame on the hit path too; frames: "
                        + hitWire.frames());
    }
}
