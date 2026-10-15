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
package org.atmosphere.aienvironment;

import com.sun.net.httpserver.HttpServer;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.Function;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.impl.AtmosphereClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two Spring Boot applications in one JVM, each with its own
 * {@code atmosphere.ai.*} configuration, send their own values on the wire
 * from their own {@code @AiEndpoint}, and closing one leaves the other as it
 * was. Both contexts live in this one class: surefire gives every test class
 * its own JVM ({@code reuseForks=false}), so two classes would never share one.
 */
class TwoApplicationsAiEnvironmentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROBE = "/atmosphere/ai-environment-probe";
    private static final String SSE = """
            data: {"choices":[{"delta":{"content":"ok"}}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    private static final Map<String, String> BODIES = new ConcurrentHashMap<>();
    private static HttpServer server;
    static String baseUrl;

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

    @BeforeAll
    static void startModel() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var messages = MAPPER.readTree(body).get("messages");
            BODIES.put(messages.get(messages.size() - 1).get("content").asString(), body);
            var bytes = SSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stopModel() {
        server.stop(0);
    }

    @AfterEach
    void closeContexts() {
        contexts.forEach(ConfigurableApplicationContext::close);
    }

    private ConfigurableApplicationContext start(Class<?> application, String temperature, String... extra) {
        var properties = new ArrayList<>(List.of(
                "server.port=0",
                "atmosphere.packages=org.atmosphere.aienvironment.endpoint",
                "atmosphere.ai.mode=remote",
                "atmosphere.ai.api-key=k",
                "atmosphere.ai.base-url=" + baseUrl,
                "atmosphere.ai.runtime=built-in",
                "atmosphere.ai.temperature=" + temperature));
        properties.addAll(List.of(extra));
        var context = new SpringApplicationBuilder(application)
                .properties(properties.toArray(String[]::new))
                .run();
        contexts.add(context);
        return context;
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @Test
    void eachApplicationSendsItsOwnConfigurationAndClosingOneLeavesTheOther() throws Exception {
        var a = start(ApplicationA.class, "0.2", "atmosphere.ai.model=model-a");
        var b = start(ApplicationB.class, "0.9");
        prompt(a, "to-a");
        prompt(b, "to-b");
        assertEquals(0.2, wire("to-a").get("temperature").asDouble(), 1e-9, "application A's temperature");
        assertEquals("model-a", wire("to-a").get("model").asString(), "application A's model");
        assertEquals(0.9, wire("to-b").get("temperature").asDouble(), 1e-9, "application B's temperature");
        assertEquals("model-b", wire("to-b").get("model").asString(), "application B's model");

        var environmentA = a.getBean(AiEnvironment.class);
        var environmentB = b.getBean(AiEnvironment.class);
        assertNotSame(environmentA, environmentB, "each context has its own environment");

        // The environment was attached before the endpoint scan ran: the
        // processor saw the context's own, not one it created itself.
        assertSame(environmentA, EnvironmentRecordingDecorator.SEEN.get(a.getBean(AtmosphereFramework.class)));
        assertSame(environmentB, EnvironmentRecordingDecorator.SEEN.get(b.getBean(AtmosphereFramework.class)));

        // B's static @Bean ran while A was live and still baked B's knob.
        assertEquals(0.9, b.getBean(AiConfig.LlmSettings.class).generation().temperature());

        b.close();
        assertTrue(environmentB.isClosed(), "closing the context closes its environment");
        assertTrue(environmentB.runtimes().resolved().isEmpty(), "its runtime instances are dropped");
        assertFalse(AiEnvironment.live().contains(environmentB));
        assertFalse(environmentA.isClosed());

        prompt(a, "to-a-after-b-closed");
        assertEquals(0.2, wire("to-a-after-b-closed").get("temperature").asDouble(), 1e-9);
        assertEquals("model-a", wire("to-a-after-b-closed").get("model").asString());
    }

    private static void prompt(ConfigurableApplicationContext context, String message) throws Exception {
        var port = context.getEnvironment().getProperty("local.server.port");
        var client = AtmosphereClient.newClient();
        var open = new CountDownLatch(1);
        var options = client.newOptionsBuilder().reconnect(false).build();
        var request = client.newRequestBuilder()
                .uri("ws://localhost:" + port + PROBE)
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();
        var socket = client.create(options);
        try {
            socket.on(Event.OPEN, (Function<Object>) o -> open.countDown()).open(request);
            assertTrue(open.await(10, TimeUnit.SECONDS), "the WebSocket opens on port " + port);
            socket.fire(message);
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!BODIES.containsKey(message) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
        } finally {
            socket.close();
        }
    }

    private static JsonNode wire(String message) {
        var body = BODIES.get(message);
        assertNotNull(body, "the prompt '" + message + "' must reach the model endpoint");
        return MAPPER.readTree(body);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ApplicationA {
    }

    /** An application whose own settings bean is built the way the samples build it. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ApplicationB {
        @Bean
        AiConfig.LlmSettings applicationSettings() {
            return AiConfig.configure("remote", "model-b", "k", baseUrl);
        }
    }
}
