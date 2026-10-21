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

import org.atmosphere.ai.AiInterceptor;
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
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.cpr.RawMessage;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@code @Prompt} turn that outlives the endpoint's timeout (a model host that
 * never answers) ends with the timeout's {@code error} frame, which the client
 * must be able to show: written live, under the session of the run's other
 * frames and a {@code seq} above theirs, and replayed to a page reloaded after
 * it under a {@code (sessionId, seq)} the page does not already hold. The
 * Console drops a frame whose {@code seq} is not above the last one it saw of
 * that session, as {@link #alreadyReceived} does here; the watchdog used to end
 * the run's replay before telling the session, which then counted the run as
 * over and wrote nothing, and the replay wrote the unnumbered error under the
 * run id as {@code seq} 1, the {@code seq} of the frame naming the run to the
 * reloaded page, so the page dropped it too.
 */
// Constructs a real AtmosphereResourceImpl via the deprecated 6-arg
// constructor — the same harness AiEndpointHandlerReattachCancelledRunTest uses.
@SuppressWarnings("deprecation")
class AiEndpointHandlerPromptTimeoutFrameTest {

    private static final long TIMEOUT_MS = 300;
    private static final String TIMEOUT_MESSAGE = "Prompt processing timed out after " + TIMEOUT_MS + "ms";
    private static final ObjectMapper JSON = new ObjectMapper();

    static final AtomicReference<String> RUN_ID = new AtomicReference<>();
    static final CountDownLatch PROMPT_STARTED = new CountDownLatch(1);
    static final AtomicReference<CountDownLatch> RELEASE = new AtomicReference<>();
    static final CountDownLatch PROMPT_RETURNED = new CountDownLatch(1);

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster room;
    private AiEndpointHandler handler;

    @BeforeEach
    void setUp() {
        RunRegistryHolder.reset();
        RUN_ID.set(null);
        RELEASE.set(new CountDownLatch(1));
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        room = factory.get(DefaultBroadcaster.class, "/ai/timeout");
    }

    @AfterEach
    void tearDown() {
        RELEASE.get().countDown();
        if (handler != null) {
            handler.destroy();
        }
        RunRegistryHolder.reset();
        room.destroy();
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    void aTimedOutTurnEndsWithAnErrorFrameTheClientKeepsLiveAndAfterAReload() throws Exception {
        var live = captureFrames();
        handler = new AiEndpointHandler(new BlockingEndpoint(),
                BlockingEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class),
                TIMEOUT_MS, "", "/ai/timeout",
                new AiEndpointHandlerReattachCancelledRunTest.StreamingUntilCancelledRuntime(),
                List.<AiInterceptor>of(), null, AnnotatedLifecycle.scan(BlockingEndpoint.class));
        var original = new AtmosphereResourceImpl(config, room,
                AtmosphereRequestImpl.newInstance(), AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                new org.atmosphere.handler.AbstractReflectorAtmosphereHandler.Default());
        room.addAtmosphereResource(original);

        var clientRunId = UUID.randomUUID().toString();
        assertTrue(handler.dispatchPrompt(original, "explain", clientRunId));
        assertTrue(PROMPT_STARTED.await(10, TimeUnit.SECONDS), "the @Prompt method must be running");
        assertEquals(clientRunId, RUN_ID.get(), "the run takes the id the client minted");

        // Live: the timeout's error frame reaches the client while the @Prompt
        // method, which swallows the interrupt, is still blocked.
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (indexOfType(live, "error") < 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(PROMPT_RETURNED.await(0, TimeUnit.MILLISECONDS),
                "the @Prompt method swallows the interrupt and is still running");
        var at = indexOfType(live, "error");
        assertTrue(at > 0, "the timed-out turn is written its error frame, after its run id: " + live);
        var error = live.get(at);
        assertEquals(TIMEOUT_MESSAGE, error.path("data").asString(), error.toString());
        var previous = live.get(at - 1);
        assertEquals(previous.path("sessionId").asString(), error.path("sessionId").asString(),
                "the error frame belongs to the session of the run's other frames: " + live);
        assertTrue(error.path("seq").asLong() > previous.path("seq").asLong(),
                "the error frame's seq is above the frame before it: " + live);
        var page = new HashMap<String, Long>();
        for (var frame : live) {
            assertFalse(alreadyReceived(page, frame), "the live client keeps every frame: " + live);
        }

        // The page reloads and sends its prompt again under the id it minted:
        // it is named the run, then replayed what it does not have.
        var out = new StringWriter();
        var handle = RunRegistryHolder.get().lookupReplayable(clientRunId).orElseThrow();
        assertTrue(RunReattachSupport.reattachPrompt(reattachingResource(clientRunId, out),
                RunRegistryHolder.get(), handle));
        var reloaded = parse(out.toString());
        assertTrue(reloaded.size() >= 2, "the run's name, then its error: " + out);
        assertEquals(RunReattachSupport.RUN_ID_HEADER, reloaded.get(0).path("key").asString(), out.toString());
        var replayedError = reloaded.get(indexOfType(reloaded, "error"));
        assertEquals(TIMEOUT_MESSAGE, replayedError.path("data").asString(), out.toString());
        var fresh = new HashMap<String, Long>();
        for (var frame : reloaded) {
            assertFalse(alreadyReceived(fresh, frame),
                    "the reloaded page keeps every frame, the error included: " + out);
        }
        assertEquals(error.path("sessionId").asString(), replayedError.path("sessionId").asString(),
                "the replayed error is the frame the live client was written: " + out);
        assertEquals(error.path("seq").asLong(), replayedError.path("seq").asLong(), out.toString());
    }

    /** The Console's dedupe (useAtmosphereChat.ts): a frame not above the last {@code seq} of its session is dropped. */
    private static boolean alreadyReceived(Map<String, Long> lastSeqOfSession, JsonNode frame) {
        if (!frame.path("sessionId").isString() || !frame.path("seq").isNumber()) {
            return false;
        }
        var session = frame.path("sessionId").asString();
        var seq = frame.path("seq").asLong();
        var last = lastSeqOfSession.get(session);
        if (last != null && seq <= last) {
            return true;
        }
        lastSeqOfSession.put(session, seq);
        return false;
    }

    private static int indexOfType(List<JsonNode> frames, String type) {
        for (var i = 0; i < frames.size(); i++) {
            if (type.equals(frames.get(i).path("type").asString())) {
                return i;
            }
        }
        return -1;
    }

    private static List<JsonNode> parse(String lines) {
        var frames = new ArrayList<JsonNode>();
        for (var line : lines.strip().split("\n")) {
            if (!line.isBlank()) {
                frames.add(JSON.readTree(line));
            }
        }
        return frames;
    }

    /** The JSON frames the run's session broadcasts on {@link #room}, in order. */
    private List<JsonNode> captureFrames() {
        var frames = new CopyOnWriteArrayList<JsonNode>();
        room.getBroadcasterConfig().addFilter((BroadcastFilter) (broadcasterId, originalMessage, message) -> {
            if (message instanceof RawMessage raw) {
                frames.add(JSON.readTree(String.valueOf(raw.message())));
            }
            return new BroadcastFilter.BroadcastAction(message);
        });
        return frames;
    }

    /** A reloaded page's WebSocket, each write framed as one message (one line here). */
    private static AtmosphereResource reattachingResource(String runId, StringWriter out) throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.addEventListener(any())).thenReturn(resource);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE)).thenReturn(runId);
        when(resource.uuid()).thenReturn("reloaded");
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.WEBSOCKET);
        when(resource.getResponse()).thenReturn(response);
        when(response.getWriter()).thenReturn(new MessageFramingWriter(out));
        return resource;
    }

    @AiEndpoint(path = "/ai/timeout")
    static final class BlockingEndpoint {

        /** Blocks like a call to a model host that never answers, swallowing the interrupt. */
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            RUN_ID.set(session.runId().orElseThrow());
            PROMPT_STARTED.countDown();
            var release = RELEASE.get();
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException swallowed) {
                    // The blocked call does not notice the interrupt.
                }
            }
            PROMPT_RETURNED.countDown();
        }
    }
}
