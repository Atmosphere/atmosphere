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
package org.atmosphere.ai.processor;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.resume.RunReattachSupport;
import org.atmosphere.ai.resume.RunRegistryHolder;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An {@code @Prompt} that answers with {@code session.stream(...)} loses the
 * rest of its answer when the client that started it leaves on purpose: the
 * close cancels the in-flight runtime call at once (a lost connection is given
 * the reattach grace instead, see {@code AiEndpointHandlerReattachGraceTest})
 * and the runtime completes normally on that cancel. A client that reattaches
 * with the run id must get the tokens the runtime streamed before the close —
 * they are written to the session below {@code AiStreamingSession}, which the
 * replay capture used to sit above — and be told the run was cancelled, with
 * the {@code cancelled} frame, not handed a {@code complete} frame that makes a
 * cut-short run look finished.
 */
// Constructs a real AtmosphereResourceImpl via the deprecated 6-arg
// constructor — the same harness AiEndpointHandlerFactsCachePrefixTest uses.
@SuppressWarnings("deprecation")
class AiEndpointHandlerReattachCancelledRunTest {

    static final AtomicReference<String> RUN_ID = new AtomicReference<>();

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster room;

    @BeforeEach
    void setUp() {
        RunRegistryHolder.reset();
        RUN_ID.set(null);
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        room = factory.get(DefaultBroadcaster.class, "/ai/streamed");
    }

    @AfterEach
    void tearDown() {
        RunRegistryHolder.reset();
        room.destroy();
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    void aReattachToARunTheClientsCloseCancelledEndsInTheCancelledFrame() throws Exception {
        var runtime = new StreamingUntilCancelledRuntime();
        var handler = new AiEndpointHandler(new StreamingEndpoint(),
                StreamingEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class),
                30_000L, "", "/ai/streamed", runtime, List.<AiInterceptor>of(),
                null, AnnotatedLifecycle.scan(StreamingEndpoint.class));
        var original = new AtmosphereResourceImpl(config, room,
                AtmosphereRequestImpl.newInstance(), AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                new org.atmosphere.handler.AbstractReflectorAtmosphereHandler.Default());
        room.addAtmosphereResource(original);

        handler.dispatchPrompt(original, "explain");
        assertTrue(runtime.streaming.await(10, TimeUnit.SECONDS), "the model call must be in flight");

        // The client that started the run closes the connection mid-answer
        // (Atmosphere's close message: it left on purpose).
        handler.onStateChange(new AtmosphereResourceEventImpl(original, false, false, true, null));
        assertTrue(runtime.cancelled.await(10, TimeUnit.SECONDS),
                "the close cancels the in-flight model call at once");
        var registry = RunRegistryHolder.get();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (registry.lookup(RUN_ID.get()).isPresent() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        var out = new StringWriter();
        RunReattachSupport.replayPendingRun(reattachingResource(RUN_ID.get(), out), registry);

        var frames = out.toString().strip().split("\n");
        assertEquals(2, frames.length, "the streamed token, then the terminal frame: " + out);
        assertTrue(frames[0].contains("\"type\":\"streaming-text\"")
                        && frames[0].contains("\"data\":\"partial answer\""),
                "the tokens the runtime streamed before the disconnect replay: " + frames[0]);
        assertTrue(frames[1].contains("\"type\":\"cancelled\"") && frames[1].contains("\"data\":\"disconnect\""),
                "a run the close cut short replays its cancelled frame, not complete: " + frames[1]);
    }

    private static AtmosphereResource reattachingResource(String runId, StringWriter out)
            throws Exception {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE)).thenReturn(runId);
        when(resource.uuid()).thenReturn("reattached");
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.WEBSOCKET);
        when(resource.getResponse()).thenReturn(response);
        when(response.getWriter()).thenReturn(new PrintWriter(out));
        return resource;
    }

    /** Streams one token, then holds the call open until it is cancelled, completing on the cancel as runtime bridges do. */
    static final class StreamingUntilCancelledRuntime implements AgentRuntime {
        final CountDownLatch streaming = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);

        @Override
        public String name() { return "streaming-until-cancelled"; }

        @Override
        public boolean isAvailable() { return true; }

        @Override
        public int priority() { return 0; }

        @Override
        public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            throw new UnsupportedOperationException("dispatched through executeWithHandle");
        }

        @Override
        public ExecutionHandle executeWithHandle(AgentExecutionContext context, StreamingSession session) {
            var handle = new ExecutionHandle.Settable(() -> {
                session.complete();
                cancelled.countDown();
            });
            session.send("partial answer");
            streaming.countDown();
            return handle;
        }
    }

    @AiEndpoint(path = "/ai/streamed")
    static final class StreamingEndpoint {

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            RUN_ID.set(session.runId().orElseThrow());
            session.stream(message);
        }
    }
}
