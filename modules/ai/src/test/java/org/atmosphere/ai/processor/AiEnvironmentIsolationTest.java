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
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.llm.LlmClient;
import org.atmosphere.ai.resume.RunRegistryHolder;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.cpr.RawMessage;
import org.atmosphere.util.ExecutorsFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Two applications in one JVM, each with its own {@code atmosphere.ai.*}
 * knobs and LLM settings, each send their own values on the wire through
 * their own runtime instance; closing one leaves the other untouched. Before
 * {@link AiEnvironment}, both endpoints shared the JVM's runtime instances
 * (the first configured client won), the latest host configuration's knobs
 * and the JVM-wide settings.
 */
// A real AtmosphereResourceImpl through the deprecated 6-arg constructor, as
// AiEndpointHandlerPromptErrorTerminalTest uses.
@SuppressWarnings("deprecation")
class AiEnvironmentIsolationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SSE = """
            data: {"choices":[{"delta":{"content":"ok"}}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    private final Map<String, String> bodiesByMessage = new ConcurrentHashMap<>();
    private final List<App> apps = new ArrayList<>();
    private HttpServer server;
    private String baseUrl;

    /** One application: its framework, broadcaster factory and environment. */
    private record App(AtmosphereFramework framework, DefaultBroadcasterFactory factory,
                       AiEnvironment environment) {
    }

    @BeforeEach
    void setUp() throws IOException {
        RunRegistryHolder.reset();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var messages = MAPPER.readTree(body).get("messages");
            bodiesByMessage.put(messages.get(messages.size() - 1).get("content").asString(), body);
            var bytes = SSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        // The process-wide settings an application without its own would use:
        // reachable, so a dispatch that wrongly reads them is seen on the wire.
        AiConfig.configure("remote", "process-model", "k", baseUrl);
    }

    @AfterEach
    void tearDown() {
        for (var app : apps) {
            app.environment().close();
            app.factory().destroy();
            ExecutorsFactory.reset(app.framework().getAtmosphereConfig());
            if (!app.framework().isDestroyed()) {
                app.framework().destroy();
            }
        }
        RunRegistryHolder.reset();
        server.stop(0);
        AiConfig.resetHostConfig();
        org.atmosphere.ai.AiConfigTestSupport.resetSettings();
        AgentRuntimeResolver.reset();
    }

    private App app(String temperature, String model) {
        var framework = new AtmosphereFramework();
        var config = framework.getAtmosphereConfig();
        var factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        framework.setBroadcasterFactory(factory);
        var knobs = Map.of(AiConfig.TEMPERATURE_PROPERTY, temperature,
                AiConfig.RUNTIME_PROPERTY, "built-in");
        var environment = AiEnvironment.create(config, knobs::get);
        config.shutdownHook(environment::close);
        environment.configure("remote", model, "k", baseUrl);
        var app = new App(framework, factory, environment);
        apps.add(app);
        return app;
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // handle takes a raw Class
    private static AiEndpointHandler register(App app, Class<?> endpoint, String path) {
        new AiEndpointProcessor().handle(app.framework(), (Class) endpoint);
        var wrapper = app.framework().getAtmosphereHandlers().get(path);
        assertNotNull(wrapper, "the endpoint registered: " + app.framework().getAtmosphereHandlers().keySet());
        return (AiEndpointHandler) wrapper.atmosphereHandler();
    }

    /** Sends {@code message} to the endpoint and returns the frames the client received. */
    private static List<String> prompt(App app, AiEndpointHandler handler, String path, String message)
            throws InterruptedException {
        var frames = new CopyOnWriteArrayList<String>();
        var broadcaster = app.factory().lookup(DefaultBroadcaster.class, path, true);
        var client = new AtmosphereResourceImpl(app.framework().getAtmosphereConfig(), broadcaster,
                AtmosphereRequestImpl.newInstance(), AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class), new RecordingHandler(frames));
        broadcaster.addAtmosphereResource(client);
        handler.dispatchPrompt(client, message);
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (frames.stream().noneMatch(f -> f.contains("\"type\":\"complete\"")
                || f.contains("\"type\":\"error\"")) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return frames;
    }

    private JsonNode wire(String message) {
        var body = bodiesByMessage.get(message);
        assertNotNull(body, "the prompt '" + message + "' must reach the model endpoint");
        return MAPPER.readTree(body);
    }

    @Test
    void eachApplicationSendsItsOwnKnobsAndSettingsThroughItsOwnRuntime() throws Exception {
        var a = app("0.2", "model-a");
        var b = app("0.9", "model-b");
        var handlerA = register(a, StreamingEndpoint.class, "/ai/isolated");
        var handlerB = register(b, StreamingEndpoint.class, "/ai/isolated");

        prompt(a, handlerA, "/ai/isolated", "to-a");
        prompt(b, handlerB, "/ai/isolated", "to-b");

        assertEquals(0.2, wire("to-a").get("temperature").asDouble(), 1e-9, "application A's temperature");
        assertEquals("model-a", wire("to-a").get("model").asString(), "application A's model");
        assertEquals(0.9, wire("to-b").get("temperature").asDouble(), 1e-9, "application B's temperature");
        assertEquals("model-b", wire("to-b").get("model").asString(), "application B's model");

        // B stops: A keeps sending its own values, and the static API now
        // reads A, the only application left.
        b.environment().close();
        b.framework().destroy();
        prompt(a, handlerA, "/ai/isolated", "to-a-after-b-closed");
        assertEquals(0.2, wire("to-a-after-b-closed").get("temperature").asDouble(), 1e-9,
                "application A's temperature once B closed");
        assertEquals("model-a", wire("to-a-after-b-closed").get("model").asString());
        assertSame(a.environment().settings(), AiConfig.get(),
                "the static API returns the surviving application's settings");
        assertTrue(b.environment().runtimes() != a.environment().runtimes());
    }

    /**
     * A {@code @Prompt} method that reads the static {@code AiConfig.get()}
     * sees its own application's settings while two are running.
     */
    @Test
    void aPromptMethodReadingTheStaticApiSeesItsOwnApplication() throws Exception {
        var a = app("0.2", "model-a");
        var b = app("0.9", "model-b");
        var handlerA = register(a, StaticReadingEndpoint.class, "/ai/static-reader");
        var handlerB = register(b, StaticReadingEndpoint.class, "/ai/static-reader");

        var framesA = prompt(a, handlerA, "/ai/static-reader", "which model");
        var framesB = prompt(b, handlerB, "/ai/static-reader", "which model");

        assertTrue(framesA.stream().anyMatch(f -> f.contains("model=model-a temperature=0.2")),
                "application A's @Prompt reads A: " + framesA);
        assertTrue(framesB.stream().anyMatch(f -> f.contains("model=model-b temperature=0.9")),
                "application B's @Prompt reads B: " + framesB);
    }

    /**
     * {@code execute}, {@code executeWithHandle} and {@code generate} on one
     * application's runtime all dispatch through that application's settings
     * (Correctness Invariant #7), from a thread bound to no application.
     */
    @Test
    void everyDispatchModeOfARuntimeReadsItsOwnEnvironment() {
        var a = app("0.2", "model-a");
        app("0.9", "model-b");
        var runtime = a.environment().runtimes().resolve();

        var sync = new CollectingSession();
        runtime.execute(context("mode-execute"), sync);
        assertTrue(sync.await(Duration.ofSeconds(10)), "execute settles");

        var withHandle = new CollectingSession();
        runtime.executeWithHandle(context("mode-handle"), withHandle).whenDone().join();
        assertTrue(withHandle.await(Duration.ofSeconds(10)), "executeWithHandle settles");

        runtime.generate(context("mode-generate"), Duration.ofSeconds(10));

        for (var message : List.of("mode-execute", "mode-handle", "mode-generate")) {
            assertEquals("model-a", wire(message).get("model").asString(), message);
            assertEquals(0.2, wire(message).get("temperature").asDouble(), 1e-9, message);
        }
    }

    /**
     * Code that runs on the dispatch thread and reads the static API (an
     * installed {@link LlmClient}, an {@code @AiTool} in the tool loop) sees
     * the runtime's application in {@code execute}, {@code executeWithHandle}
     * and {@code generate} alike (Correctness Invariant 7), from a thread
     * bound to no application while two are running.
     */
    @Test
    void everyDispatchModeBindsTheRuntimesApplicationToTheThreadItDispatchesOn() {
        var a = app("0.2", "model-a");
        app("0.9", "model-b");
        var seen = new ConcurrentHashMap<String, AiEnvironment>();
        a.environment().installClient(new LlmClient() {
            @Override
            public void streamChatCompletion(ChatCompletionRequest request, StreamingSession session) {
                var messages = request.messages();
                seen.put(messages.get(messages.size() - 1).content(), AiEnvironment.current());
                session.complete("ok");
            }
        });
        var runtime = a.environment().runtimes().resolve();
        assertNull(AiEnvironment.bound(), "the test thread is bound to no application");

        var sync = new CollectingSession();
        runtime.execute(context("bound-execute"), sync);
        assertTrue(sync.await(Duration.ofSeconds(10)), "execute settles");
        var withHandle = new CollectingSession();
        runtime.executeWithHandle(context("bound-handle"), withHandle).whenDone().join();
        assertTrue(withHandle.await(Duration.ofSeconds(10)), "executeWithHandle settles");
        runtime.generate(context("bound-generate"), Duration.ofSeconds(10));

        for (var message : List.of("bound-execute", "bound-handle", "bound-generate")) {
            assertSame(a.environment(), seen.get(message), message + " sees its runtime's application");
        }
        assertNull(AiEnvironment.bound(), "the dispatch restored the caller's binding");
    }

    private static AgentExecutionContext context(String message) {
        return new AgentExecutionContext(
                message, "You are helpful", null,
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
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

    @AiEndpoint(path = "/ai/isolated")
    public static final class StreamingEndpoint {

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            session.stream(message);
        }
    }

    @AiEndpoint(path = "/ai/static-reader")
    public static final class StaticReadingEndpoint {

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            var settings = AiConfig.get();
            session.complete("model=" + settings.model()
                    + " temperature=" + settings.generation().temperature());
        }
    }
}
