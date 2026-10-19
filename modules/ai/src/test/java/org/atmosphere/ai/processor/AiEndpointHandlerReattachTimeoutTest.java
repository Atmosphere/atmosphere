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

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.DefaultStreamingSession;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.resume.MessageFramingWriter;
import org.atmosphere.ai.resume.RunReattachSupport;
import org.atmosphere.ai.resume.RunRegistryHolder;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
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

import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A client that reattached to a run must be told how the run ended when the
 * {@code @Prompt} times out after the original client left. The watchdog only
 * wrote its timeout error when the original session was still open, so once
 * that client had gone the error was never captured; the interrupted
 * {@code @Prompt} then returned and the reattached client waited on a live
 * subscription that no terminal frame ever closed.
 */
// Constructs a real AtmosphereResourceImpl via the deprecated 6-arg
// constructor — the same harness AiEndpointHandlerFactsCachePrefixTest uses.
@SuppressWarnings("deprecation")
class AiEndpointHandlerReattachTimeoutTest {

    static final AtomicReference<String> RUN_ID = new AtomicReference<>();
    static volatile CountDownLatch started;

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster room;

    @BeforeEach
    void setUp() {
        RunRegistryHolder.reset();
        RUN_ID.set(null);
        started = new CountDownLatch(1);
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        room = factory.get(DefaultBroadcaster.class, "/ai/slow");
    }

    @AfterEach
    void tearDown() {
        RunRegistryHolder.reset();
        room.destroy();
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    void aTimeoutAfterTheClientLeftEndsTheReattachedStreamInError() throws Exception {
        // The timeout must fire after the reattach: at 500ms a loaded machine
        // let it fire first, and the reattach replayed the error frame as well.
        var handler = new AiEndpointHandler(new SlowEndpoint(),
                SlowEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class),
                3_000L, "", "/ai/slow", mock(AgentRuntime.class), List.<AiInterceptor>of(),
                null, AnnotatedLifecycle.scan(SlowEndpoint.class));
        var original = new AtmosphereResourceImpl(config, room,
                AtmosphereRequestImpl.newInstance(), AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                new org.atmosphere.handler.AbstractReflectorAtmosphereHandler.Default());
        room.addAtmosphereResource(original);

        handler.dispatchPrompt(original, "go");
        assertTrue(started.await(10, TimeUnit.SECONDS), "the @Prompt must start");
        // The original client goes away: its streaming session is closed.
        DefaultStreamingSession.cleanupResource(original.uuid());

        var out = new StringWriter();
        var reattached = reattachingResource(RUN_ID.get(), out);
        assertEquals(1, RunReattachSupport.replayPendingRun(reattached, RunRegistryHolder.get()));

        var buffer = RunRegistryHolder.get().lookupReplayable(RUN_ID.get()).orElseThrow().replayBuffer();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        // The buffer releases its subscribers before it hands them the terminal
        // frame (outside its lock), so wait for the frame itself too.
        while ((buffer.subscriberCount() > 0 || out.toString().strip().split("\n").length < 2)
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        var frames = out.toString().strip().split("\n");
        assertEquals(2, frames.length, out.toString());
        assertTrue(frames[0].contains("\"data\":\"partial\""), frames[0]);
        assertTrue(frames[1].contains("\"type\":\"error\"") && frames[1].contains("timed out"),
                "the reattached client is told the run timed out: " + frames[1]);
        assertEquals(0, buffer.subscriberCount(), "the terminal frame releases the subscription");
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
        when(response.getWriter()).thenReturn(new MessageFramingWriter(out));
        return resource;
    }

    @AiEndpoint(path = "/ai/slow")
    static final class SlowEndpoint {

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            RUN_ID.set(session.runId().orElseThrow());
            session.send("partial");
            started.countDown();
            try {
                Thread.sleep(30_000L);
            } catch (InterruptedException e) {
                // Swallowed: the @Prompt returns normally, without a terminal frame.
                Thread.currentThread().interrupt();
            }
        }
    }
}
