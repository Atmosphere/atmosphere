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
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.cpr.AtmosphereFramework;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two Spring Boot applications in one JVM, each with its own
 * {@code atmosphere.ai.*} configuration, dispatch with their own values
 * through their own runtime, and closing one leaves the other as it was. Both
 * contexts live in this one class: surefire gives every test class its own JVM
 * ({@code reuseForks=false}), so two classes would never share one.
 */
class TwoApplicationsAiEnvironmentTest {

    private static final Pattern TEMPERATURE = Pattern.compile("\"temperature\"\\s*:\\s*([0-9.]+)");
    private static final Pattern MODEL = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]*)\"");
    private static final String SSE = """
            data: {"choices":[{"delta":{"content":"ok"}}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    private static final List<String> BODIES = new CopyOnWriteArrayList<>();
    private static HttpServer server;
    static String baseUrl;

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

    @BeforeAll
    static void startModel() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            BODIES.add(body);
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
    void eachApplicationDispatchesWithItsOwnConfigurationAndClosingOneLeavesTheOther() {
        var a = start(ApplicationA.class, "0.2", "atmosphere.ai.model=model-a");
        var b = start(ApplicationB.class, "0.9");

        dispatch(a, "to-a");
        dispatch(b, "to-b");
        assertEquals("0.2", value(TEMPERATURE, "to-a"), "application A's temperature");
        assertEquals("model-a", value(MODEL, "to-a"), "application A's model");
        assertEquals("0.9", value(TEMPERATURE, "to-b"), "application B's temperature");
        assertEquals("model-b", value(MODEL, "to-b"), "application B's model");

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

        dispatch(a, "to-a-after-b-closed");
        assertEquals("0.2", value(TEMPERATURE, "to-a-after-b-closed"));
        assertEquals("model-a", value(MODEL, "to-a-after-b-closed"));
    }

    /** Dispatches {@code message} through the runtime the application's endpoints use. */
    private static void dispatch(ConfigurableApplicationContext context, String message) {
        var runtime = AiEnvironment.of(context.getBean(AtmosphereFramework.class).getAtmosphereConfig())
                .runtimes().resolve();
        runtime.generate(new AgentExecutionContext(
                message, "You are helpful", null,
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null), Duration.ofSeconds(15));
    }

    private static String value(Pattern pattern, String message) {
        var body = BODIES.stream().filter(b -> b.contains("\"" + message + "\"")).findFirst().orElse(null);
        assertNotNull(body, "the prompt '" + message + "' must reach the model endpoint");
        var matcher = pattern.matcher(body);
        assertTrue(matcher.find(), pattern + " in " + body);
        return matcher.group(1);
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
