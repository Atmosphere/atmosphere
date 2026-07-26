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
package org.atmosphere.ai.anthropic;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.atmosphere.ai.AiCapability;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-shape tests for {@link AiCapability#MODEL_ENUMERATION} on the Anthropic
 * runtime: {@code GET /v1/models} against a local
 * {@code com.sun.net.httpserver.HttpServer} (real HTTP, real JSON parsing, no
 * external network), plus the always-fall-back contract.
 */
class AnthropicModelEnumerationTest {

    private static HttpServer server;
    private static int port;
    private static final AtomicReference<String> LAST_API_KEY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_VERSION = new AtomicReference<>();
    private static final AtomicReference<String> LAST_PATH = new AtomicReference<>();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        // Anthropic shape: data[] of {"type":"model","id":...,"display_name":...}
        server.createContext("/ok/v1/models", exchange -> {
            LAST_API_KEY.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            LAST_VERSION.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            LAST_PATH.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, """
                    {"data":[
                      {"type":"model","id":"claude-opus-4-8","display_name":"Claude Opus 4.8"},
                      {"type":"model","id":"claude-sonnet-4-6","display_name":"Claude Sonnet 4.6"}
                    ],"has_more":false,"first_id":"claude-opus-4-8"}""");
        });

        server.createContext("/boom/v1/models",
                exchange -> respond(exchange, 401, "{\"error\":\"invalid key\"}"));

        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void listModelsParsesTheAnthropicDataIdShape() {
        var client = AnthropicMessagesClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/ok")
                .apiKey("sk-ant-test")
                .build();

        assertEquals(List.of("claude-opus-4-8", "claude-sonnet-4-6"), client.listModels(),
                "model ids must be parsed in response order");
        assertEquals("/ok/v1/models", LAST_PATH.get(),
                "enumeration must hit GET /v1/models");
        assertEquals("sk-ant-test", LAST_API_KEY.get(),
                "the configured API key must authenticate the enumeration call");
        assertEquals("2023-06-01", LAST_VERSION.get(),
                "the anthropic-version header is required on every endpoint");
    }

    @Test
    void listModelsReturnsEmptyOnNon2xx() {
        var client = AnthropicMessagesClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/boom")
                .apiKey("sk-ant-bad")
                .build();
        assertTrue(client.listModels().isEmpty(),
                "a 401 must degrade to an empty list, never an exception");
    }

    @Test
    void listModelsReturnsEmptyWhenEndpointIsUnreachable() {
        var client = AnthropicMessagesClient.builder()
                .baseUrl("http://127.0.0.1:1")
                .timeout(Duration.ofSeconds(2))
                .build();
        assertTrue(client.listModels().isEmpty(),
                "an unreachable endpoint must degrade to an empty list");
    }

    @Test
    void runtimeReportsLiveModelsAndDeclaresTheCapability() {
        var runtime = new TestableAnthropicRuntime(AnthropicMessagesClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/ok")
                .apiKey("sk-ant-test")
                .build());

        assertEquals(List.of("claude-opus-4-8", "claude-sonnet-4-6"), runtime.models(),
                "the runtime must surface the provider-resolved list");
        assertTrue(runtime.capabilities().contains(AiCapability.MODEL_ENUMERATION),
                "MODEL_ENUMERATION must be declared now that models() is live");
    }

    @Test
    void runtimeFallsBackToTheConfiguredModelWhenEnumerationFails() {
        var runtime = new TestableAnthropicRuntime(AnthropicMessagesClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/boom")
                .apiKey("sk-ant-bad")
                .build());

        var models = runtime.models();
        assertFalse(models.isEmpty(),
                "a failed enumeration must never leave the admin surface empty-handed");
        assertEquals(List.of("claude-sonnet-4-6"), models,
                "the fallback is the model this runtime would actually dispatch with");
    }

    @Test
    void runtimeWithoutAClientStillReportsTheConfiguredModel() {
        assertEquals(List.of("claude-sonnet-4-6"), new AnthropicAgentRuntime().models(),
                "no client wired means no enumeration, but the default model still reports");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Runtime subclass that injects the stub-pointed client directly. */
    private static final class TestableAnthropicRuntime extends AnthropicAgentRuntime {
        TestableAnthropicRuntime(AnthropicMessagesClient client) {
            setNativeClient(client);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
