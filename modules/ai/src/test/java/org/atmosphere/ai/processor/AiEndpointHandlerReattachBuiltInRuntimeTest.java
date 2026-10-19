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

import com.sun.net.httpserver.HttpServer;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.llm.BuiltInAgentRuntime;
import org.atmosphere.ai.llm.OpenAiCompatibleClient;
import org.atmosphere.ai.resume.MessageFramingWriter;
import org.atmosphere.ai.resume.RunDisconnectGrace;
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

import java.io.IOException;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A page reloaded while the built-in runtime streams an answer reattaches to
 * its run and must receive the rest of it, through {@code complete}. The
 * built-in client ({@link OpenAiCompatibleClient}) stops reading the model's
 * stream, and ends the turn with no terminal frame, once its session reports
 * itself closed; the disconnect closes the connection's leaf session, and the
 * session the run writes to reported that leaf's state. So the run stopped at
 * the reload: its replay never grew again and never ended, and the reattached
 * page waited forever. The run's session is open while the run is: a run the
 * connection's loss detached keeps streaming into its replay.
 *
 * <p>The model is a local HTTP server speaking the OpenAI streaming wire, which
 * holds the rest of its answer until the test releases it.</p>
 */
// Constructs a real AtmosphereResourceImpl via the deprecated 6-arg
// constructor — the same harness AiEndpointHandlerReattachGraceTest uses.
@SuppressWarnings("deprecation")
class AiEndpointHandlerReattachBuiltInRuntimeTest {

    private static final String GRACE_PROPERTY = "atmosphere.ai.reattach.grace";

    static final AtomicReference<String> RUN_ID = new AtomicReference<>();

    private final CountDownLatch firstTokenSent = new CountDownLatch(1);
    private final CountDownLatch releaseRest = new CountDownLatch(1);
    private HttpServer server;
    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster room;
    private AiEndpointHandler handler;
    private BuiltInAgentRuntime runtime;

    @BeforeEach
    void setUp() throws IOException {
        RunRegistryHolder.reset();
        RUN_ID.set(null);
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        room = factory.get(DefaultBroadcaster.class, "/ai/builtin-reattach");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var os = exchange.getResponseBody()) {
                os.write(delta("partial "));
                os.flush();
                firstTokenSent.countDown();
                // The rest of the answer comes after the client's page reloaded.
                if (!releaseRest.await(10, TimeUnit.SECONDS)) {
                    return;
                }
                for (var token : List.of("rest-1 ", "rest-2 ", "rest-3")) {
                    os.write(delta(token));
                    os.flush();
                }
                os.write("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // The client closed the stream: a cancelled call.
            }
        });
        server.start();

        var baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        var client = OpenAiCompatibleClient.builder().baseUrl(baseUrl).apiKey("test-key").build();
        runtime = new BuiltInAgentRuntime();
        runtime.configure(new AiConfig.LlmSettings(client, "test-model", "remote", baseUrl, "test-key", null, null));
    }

    @AfterEach
    void tearDown() {
        releaseRest.countDown();
        System.clearProperty(GRACE_PROPERTY);
        if (handler != null) {
            handler.destroy();
        }
        RunRegistryHolder.reset();
        room.destroy();
        factory.destroy();
        ExecutorsFactory.reset(config);
        server.stop(0);
    }

    @Test
    void aPageReloadedMidAnswerReceivesTheRestOfTheBuiltInModelsAnswerThroughComplete() throws Exception {
        System.setProperty(GRACE_PROPERTY, "5s");
        var original = startRun();

        // The page reloads: its connection is lost mid-answer.
        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));
        assertTrue(RunDisconnectGrace.isDetached(RUN_ID.get()), "a lost connection detaches the run for the grace");

        var out = new StringWriter();
        RunReattachSupport.replayPendingRun(reattachingResource(RUN_ID.get(), out), RunRegistryHolder.get());
        releaseRest.countDown();
        awaitRunEnd();

        var events = RunRegistryHolder.get().lookupReplayable(RUN_ID.get()).orElseThrow()
                .replayBuffer().snapshot().stream()
                .map(event -> "complete".equals(event.type()) ? "complete" : event.payload()).toList();
        assertEquals(List.of("partial ", "rest-1 ", "rest-2 ", "rest-3", "complete"), events,
                "the model call goes on after the reload, into the run's replay, through its end");
        var frames = out.toString().strip().split("\n");
        assertEquals(5, frames.length, "the backlog, the rest of the answer, then complete: " + out);
        assertTrue(frames[0].contains("\"data\":\"partial \""), frames[0]);
        assertTrue(frames[3].contains("\"data\":\"rest-3\""), frames[3]);
        assertTrue(frames[4].contains("\"type\":\"complete\""),
                "the reattached page receives the run's terminal frame: " + frames[4]);
    }

    @Test
    void anExpiredGraceEndsTheBuiltInModelCallWithACancelledFrame() throws Exception {
        System.setProperty(GRACE_PROPERTY, "300ms");
        var original = startRun();

        handler.onStateChange(new AtmosphereResourceEventImpl(original, true, false));
        assertTrue(RunDisconnectGrace.isDetached(RUN_ID.get()));
        awaitRunEnd();
        assertTrue(RunRegistryHolder.get().lookup(RUN_ID.get()).isEmpty(),
                "the cancel at the grace's expiry ends the run");

        var out = new StringWriter();
        RunReattachSupport.replayPendingRun(reattachingResource(RUN_ID.get(), out), RunRegistryHolder.get());
        var frames = out.toString().strip().split("\n");
        assertEquals(2, frames.length, "the streamed token, then one terminal frame: " + out);
        assertTrue(frames[1].contains("\"type\":\"cancelled\"") && frames[1].contains("\"data\":\"disconnect\""),
                "a run the expired grace cut short ends with the cancelled frame: " + frames[1]);
    }

    private static byte[] delta(String token) {
        return ("data: {\"choices\":[{\"delta\":{\"content\":\"" + token + "\"}}]}\n\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private AtmosphereResourceImpl startRun() throws Exception {
        handler = new AiEndpointHandler(new BuiltInEndpoint(),
                BuiltInEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class),
                30_000L, "", "/ai/builtin-reattach", runtime, List.<AiInterceptor>of(),
                null, AnnotatedLifecycle.scan(BuiltInEndpoint.class));
        var original = new AtmosphereResourceImpl(config, room,
                AtmosphereRequestImpl.newInstance(), AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                new org.atmosphere.handler.AbstractReflectorAtmosphereHandler.Default());
        room.addAtmosphereResource(original);
        handler.dispatchPrompt(original, "explain");
        assertTrue(firstTokenSent.await(10, TimeUnit.SECONDS), "the model call must be in flight");
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && (RUN_ID.get() == null
                || RunRegistryHolder.get().lookup(RUN_ID.get())
                        .map(run -> run.replayBuffer().snapshot().isEmpty()).orElse(true))) {
            Thread.sleep(5);
        }
        assertEquals(1, RunRegistryHolder.get().lookup(RUN_ID.get()).orElseThrow().replayBuffer().snapshot().size(),
                "the first token reached the run before the reload");
        return original;
    }

    private static void awaitRunEnd() throws InterruptedException {
        var registry = RunRegistryHolder.get();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (registry.lookup(RUN_ID.get()).isPresent() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /**
     * A reattaching WebSocket, its transport framing each write as one message
     * (rendered here as one line).
     */
    private static AtmosphereResource reattachingResource(String runId, StringWriter out) throws Exception {
        var resource = mock(AtmosphereResource.class);
        when(resource.addEventListener(any())).thenReturn(resource);
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

    @AiEndpoint(path = "/ai/builtin-reattach")
    static final class BuiltInEndpoint {

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            RUN_ID.set(session.runId().orElseThrow());
            session.stream(message);
        }
    }
}
