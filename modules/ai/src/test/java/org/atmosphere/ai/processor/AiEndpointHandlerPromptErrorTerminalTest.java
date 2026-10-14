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
import org.atmosphere.cpr.RawMessage;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * An {@link Error} thrown from a {@code @Prompt} method ended the run without a
 * terminal frame for the client that sent the prompt: only an {@code Exception}
 * was routed to {@code session.error}, so that client waited on a turn that had
 * already died (Correctness Invariant #2).
 */
// A real AtmosphereResourceImpl through the deprecated 6-arg constructor, with
// a handler that records the frames the broadcaster delivers to it — the
// harness AiEndpointHandlerBroadcastReplyTest uses.
@SuppressWarnings("deprecation")
class AiEndpointHandlerPromptErrorTerminalTest {

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster room;

    @BeforeEach
    void setUp() {
        RunRegistryHolder.reset();
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        room = factory.get(DefaultBroadcaster.class, "/ai/failing");
    }

    @AfterEach
    void tearDown() {
        RunRegistryHolder.reset();
        room.destroy();
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    void anErrorThrownFromThePromptSendsTheOriginalClientATerminalErrorFrame() throws Exception {
        var frames = new CopyOnWriteArrayList<String>();
        var handler = new AiEndpointHandler(new FailingEndpoint(),
                FailingEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class),
                30_000L, "", "/ai/failing", mock(AgentRuntime.class), List.<AiInterceptor>of(),
                null, AnnotatedLifecycle.scan(FailingEndpoint.class));
        var client = new AtmosphereResourceImpl(config, room,
                AtmosphereRequestImpl.newInstance(), AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class), new RecordingHandler(frames));
        room.addAtmosphereResource(client);

        handler.dispatchPrompt(client, "go");

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (frames.stream().noneMatch(f -> f.contains("\"type\":\"error\""))
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(frames.stream().anyMatch(f -> f.contains("\"data\":\"before the failure\"")),
                "precondition: the client receives the prompt's frames: " + frames);
        assertTrue(frames.stream().anyMatch(f -> f.contains("\"type\":\"error\"")
                        && f.contains("prompt invariant broke")),
                "the client that sent the prompt gets the terminal error frame: " + frames);
    }

    /** Records every frame the broadcaster delivers to the resource. */
    private record RecordingHandler(List<String> frames) implements AtmosphereHandler {
        @Override
        public void onRequest(AtmosphereResource resource) {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) {
            var message = event.getMessage();
            frames.add(message instanceof RawMessage raw
                    ? String.valueOf(raw.message()) : String.valueOf(message));
        }

        @Override
        public void destroy() {
        }
    }

    @AiEndpoint(path = "/ai/failing")
    static final class FailingEndpoint {

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            session.send("before the failure");
            throw new AssertionError("prompt invariant broke");
        }
    }
}
