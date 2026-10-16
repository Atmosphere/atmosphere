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
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.resume.RunEventReplayBuffer;
import org.atmosphere.ai.resume.RunRegistryHolder;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * The replay buffer of a run an AI endpoint starts carries the long-polling
 * bounds of the endpoint's application
 * ({@value RunEventReplayBuffer#MAX_REPLAY_POSITIONS_PARAM},
 * {@value RunEventReplayBuffer#MAX_WAITING_POLLS_PARAM}) and the id of the run
 * the registry registers, which its warnings name.
 */
// A real AtmosphereResourceImpl through the deprecated 6-arg constructor — the
// harness AiEndpointHandlerPromptErrorTerminalTest uses.
@SuppressWarnings("deprecation")
class AiEndpointHandlerRunReplayBoundsTest {

    private static volatile CountDownLatch release;

    private final Map<String, String> params = new ConcurrentHashMap<>();
    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster room;

    @BeforeEach
    void setUp() {
        RunRegistryHolder.reset();
        release = new CountDownLatch(1);
        // An application's init parameters, as a servlet container would hand them over.
        config = spy(new AtmosphereFramework().getAtmosphereConfig());
        doAnswer(invocation -> params.get(invocation.<String>getArgument(0)))
                .when(config).getInitParameter(anyString());
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        room = factory.get(DefaultBroadcaster.class, "/ai/bounded");
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        RunRegistryHolder.reset();
        room.destroy();
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    void theRunBufferOfADispatchedPromptHasTheApplicationsBoundsAndTheRegisteredRunId() throws Exception {
        params.put("org.atmosphere.ai.longPolling.maxReplayPositionsPerRun", "2");
        params.put("org.atmosphere.ai.longPolling.maxWaitingPollsPerRun", "3");
        var handler = new AiEndpointHandler(new HoldingEndpoint(),
                HoldingEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class),
                30_000L, "", "/ai/bounded", mock(AgentRuntime.class), List.<AiInterceptor>of(),
                null, AnnotatedLifecycle.scan(HoldingEndpoint.class));
        var client = new AtmosphereResourceImpl(config, room,
                AtmosphereRequestImpl.newInstance(), AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class), new SilentHandler());
        room.addAtmosphereResource(client);

        handler.dispatchPrompt(client, "go");

        // Registered before the @Prompt method runs; it holds the run live until tearDown.
        var runs = RunRegistryHolder.get().all();
        assertEquals(1, runs.size(), "the dispatched prompt registers one run: " + runs);
        var handle = runs.get(0);
        var buffer = handle.replayBuffer();
        assertEquals(2, buffer.maxReplayPositions(),
                "the run remembers the replay positions of as many tracking ids as its application sets");
        assertEquals(3, buffer.maxWaitingPolls(),
                "as many tracking ids' polls wait on the run as its application sets");
        assertEquals(handle.runId(), buffer.runId(), "the buffer names the run the registry registered");
    }

    @Test
    void withoutInitParametersTheRunBufferHasTheDefaultBounds() throws Exception {
        var handler = new AiEndpointHandler(new HoldingEndpoint(),
                HoldingEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class),
                30_000L, "", "/ai/bounded", mock(AgentRuntime.class), List.<AiInterceptor>of(),
                null, AnnotatedLifecycle.scan(HoldingEndpoint.class));
        var client = new AtmosphereResourceImpl(config, room,
                AtmosphereRequestImpl.newInstance(), AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class), new SilentHandler());
        room.addAtmosphereResource(client);

        handler.dispatchPrompt(client, "go");

        var runs = RunRegistryHolder.get().all();
        assertEquals(1, runs.size(), "the dispatched prompt registers one run: " + runs);
        var buffer = runs.get(0).replayBuffer();
        assertEquals(RunEventReplayBuffer.MAX_REPLAY_POSITIONS, buffer.maxReplayPositions());
        assertEquals(RunEventReplayBuffer.MAX_WAITING_POLLS, buffer.maxWaitingPolls());
    }

    /** Ignores the frames the broadcaster delivers to the resource. */
    private static final class SilentHandler implements AtmosphereHandler {
        @Override
        public void onRequest(AtmosphereResource resource) {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) {
        }

        @Override
        public void destroy() {
        }
    }

    @AiEndpoint(path = "/ai/bounded")
    static final class HoldingEndpoint {

        @Prompt
        public void onPrompt(String message, StreamingSession session) throws InterruptedException {
            release.await(30, TimeUnit.SECONDS);
            session.complete();
        }
    }
}
