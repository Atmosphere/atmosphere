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
package org.atmosphere.ai.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.atmosphere.ai.AiCapability;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link AiCapability#MODEL_ENUMERATION} on the Built-in
 * OpenAI-compatible client against a local
 * {@code com.sun.net.httpserver.HttpServer} — real HTTP, real JSON parsing, no
 * external network. Also pins the shared {@link ModelListJson} parser against
 * every provider shape the three hand-rolled clients hit (OpenAI/Anthropic
 * {@code data[].id}, Cohere {@code models[].name}) and the
 * {@link CachedModelList} TTL + always-fall-back contract.
 */
class ModelEnumerationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static HttpServer server;
    private static int port;
    private static final AtomicInteger LIST_HITS = new AtomicInteger();
    private static final AtomicReference<String> LAST_AUTH = new AtomicReference<>();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        // OpenAI / Anthropic shape: {"object":"list","data":[{"id":...}]}
        server.createContext("/openai/models", exchange -> {
            LIST_HITS.incrementAndGet();
            LAST_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                    {"object":"list","data":[
                      {"id":"gpt-5-mini","object":"model"},
                      {"id":"gpt-5","object":"model"}
                    ]}""");
        });

        // Non-2xx — the client must return empty, never throw.
        server.createContext("/boom/models", exchange -> respond(exchange, 500, "upstream exploded"));

        // 2xx with a body that is not JSON at all.
        server.createContext("/garbage/models", exchange -> respond(exchange, 200, "<html>nope</html>"));

        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------- live client enumeration

    @Test
    void listModelsParsesLiveOpenAiCompatibleResponse() {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/openai")
                .apiKey("sk-test")
                .build();

        var before = LIST_HITS.get();
        var models = client.listModels();

        assertEquals(List.of("gpt-5-mini", "gpt-5"), models,
                "model ids must be parsed in response order");
        assertEquals(before + 1, LIST_HITS.get(), "exactly one GET /models per call");
        assertEquals("Bearer sk-test", LAST_AUTH.get(),
                "the configured API key must authenticate the enumeration call");
    }

    @Test
    void listModelsReturnsEmptyOnNon2xx() {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/boom")
                .apiKey("sk-test")
                .build();
        assertTrue(client.listModels().isEmpty(),
                "a 500 must degrade to an empty list, never an exception");
    }

    @Test
    void listModelsReturnsEmptyOnUnparseableBody() {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/garbage")
                .apiKey("sk-test")
                .build();
        assertTrue(client.listModels().isEmpty(),
                "a non-JSON 200 body must degrade to an empty list");
    }

    @Test
    void listModelsReturnsEmptyWhenEndpointIsUnreachable() {
        // Port 1 is reserved and refuses instantly — the connection-refused
        // path must be swallowed exactly like a non-2xx.
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://127.0.0.1:1/v1")
                .timeout(Duration.ofSeconds(2))
                .build();
        assertTrue(client.listModels().isEmpty(),
                "an unreachable endpoint must degrade to an empty list");
    }

    // ------------------------------------------------------ runtime seam wiring

    @Test
    void builtInRuntimeReportsLiveModelsAndDeclaresTheCapability() {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/openai")
                .apiKey("sk-test")
                .build();
        var runtime = new BuiltInAgentRuntime();
        runtime.configure(new org.atmosphere.ai.AiConfig.LlmSettings(
                client, "gpt-5-mini", "remote", null, "sk-test",
                PromptCacheKeyMode.AUTO, org.atmosphere.ai.GenerationParams.defaults()));

        assertEquals(List.of("gpt-5-mini", "gpt-5"), runtime.models(),
                "the runtime must surface the provider-resolved list");
        assertTrue(runtime.capabilities().contains(AiCapability.MODEL_ENUMERATION),
                "MODEL_ENUMERATION must be declared now that models() is live");
    }

    @Test
    void builtInRuntimeFallsBackToConfiguredModelWhenEnumerationFails() {
        var client = OpenAiCompatibleClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/boom")
                .apiKey("sk-test")
                .build();
        var runtime = new BuiltInAgentRuntime();
        runtime.configure(new org.atmosphere.ai.AiConfig.LlmSettings(
                client, "gpt-5-mini", "remote", null, "sk-test",
                PromptCacheKeyMode.AUTO, org.atmosphere.ai.GenerationParams.defaults()));

        // A failed enumeration must never leave the admin surface empty-handed
        // — the configured model is always reported.
        assertEquals(List.of("gpt-5-mini"), runtime.models(),
                "a failed enumeration must fall back to the configured model");
    }

    // ------------------------------------------------------------ parser shapes

    @Test
    void parserAcceptsAnthropicDataIdShape() {
        var models = ModelListJson.parse(MAPPER, """
                {"data":[
                  {"type":"model","id":"claude-opus-4-8","display_name":"Claude Opus 4.8"},
                  {"type":"model","id":"claude-sonnet-4-6","display_name":"Claude Sonnet 4.6"}
                ],"has_more":false,"first_id":"claude-opus-4-8"}""");
        assertEquals(List.of("claude-opus-4-8", "claude-sonnet-4-6"), models);
    }

    @Test
    void parserAcceptsCohereModelsNameShape() {
        var models = ModelListJson.parse(MAPPER, """
                {"models":[
                  {"name":"command-a-plus-05-2026","endpoints":["chat"]},
                  {"name":"command-r7b","endpoints":["chat","classify"]}
                ],"next_page_token":null}""");
        assertEquals(List.of("command-a-plus-05-2026", "command-r7b"), models,
                "Cohere keys entries by name, not id");
    }

    @Test
    void parserIsDefensiveAboutMalformedInput() {
        assertTrue(ModelListJson.parse(MAPPER, null).isEmpty());
        assertTrue(ModelListJson.parse(MAPPER, "").isEmpty());
        assertTrue(ModelListJson.parse(MAPPER, "not json").isEmpty());
        assertTrue(ModelListJson.parse(MAPPER, "{\"unexpected\":true}").isEmpty(),
                "neither data nor models array present");
        assertTrue(ModelListJson.parse(MAPPER, "{\"data\":\"scalar\"}").isEmpty(),
                "data must be an array to be consumed");
        // Entries missing both id and name are skipped, not fatal.
        assertEquals(List.of("kept"), ModelListJson.parse(MAPPER,
                "{\"data\":[{\"object\":\"model\"},{\"id\":\"kept\"},{\"id\":\"\"}]}"));
        // Duplicates collapse.
        assertEquals(List.of("dup"), ModelListJson.parse(MAPPER,
                "{\"data\":[{\"id\":\"dup\"},{\"id\":\"dup\"}]}"));
    }

    @Test
    void parserBoundsTheResultSet() {
        var body = new StringBuilder("{\"data\":[");
        for (int i = 0; i < ModelListJson.MAX_MODELS + 50; i++) {
            body.append(i > 0 ? "," : "").append("{\"id\":\"m").append(i).append("\"}");
        }
        body.append("]}");
        assertEquals(ModelListJson.MAX_MODELS, ModelListJson.parse(MAPPER, body.toString()).size(),
                "a hostile provider must not inflate the discovery surface");
    }

    // ------------------------------------------------------------- cache policy

    @Test
    void cacheServesFromMemoryWithinTtlAndRefetchesAfterExpiry() throws Exception {
        var cache = new CachedModelList(Duration.ofMillis(150));
        var fetches = new AtomicInteger();
        java.util.function.Supplier<List<String>> fetcher = () -> {
            fetches.incrementAndGet();
            return List.of("m-" + fetches.get());
        };

        assertEquals(List.of("m-1"), cache.get("T", fetcher, List::of));
        assertEquals(List.of("m-1"), cache.get("T", fetcher, List::of), "served from cache");
        assertEquals(1, fetches.get(), "a fresh entry must not re-hit the provider");

        Thread.sleep(200);
        assertEquals(List.of("m-2"), cache.get("T", fetcher, List::of), "TTL expiry refetches");
        assertEquals(2, fetches.get());
    }

    @Test
    void cacheFallsBackAndDoesNotNegativelyCacheFailures() {
        var cache = new CachedModelList(Duration.ofMinutes(5));
        var attempts = new AtomicInteger();

        // First call: the fetcher throws — the fallback answers and nothing is cached.
        var first = cache.get("T", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("provider down");
        }, () -> List.of("configured"));
        assertEquals(List.of("configured"), first, "a throwing fetcher must fall back");

        // Second call: an empty result is also treated as "no data" and falls back.
        var second = cache.get("T", () -> {
            attempts.incrementAndGet();
            return List.of();
        }, () -> List.of("configured"));
        assertEquals(List.of("configured"), second, "an empty fetch must fall back");

        // Third call: the provider recovers — the failure was not negatively cached.
        var third = cache.get("T", () -> {
            attempts.incrementAndGet();
            return List.of("live");
        }, () -> List.of("configured"));
        assertEquals(List.of("live"), third, "recovery must be visible immediately");
        assertEquals(3, attempts.get(), "every call retried while no success was cached");

        // And the success IS cached.
        assertEquals(List.of("live"), cache.get("T", () -> {
            attempts.incrementAndGet();
            return List.of("never-reached");
        }, () -> List.of("configured")));
        assertEquals(3, attempts.get(), "a cached success suppresses further fetches");
    }

    @Test
    void cacheFallbackIsUsedWhenFetcherReturnsNull() {
        var cache = new CachedModelList(Duration.ofMinutes(5));
        assertFalse(cache.get("T", () -> null, () -> List.of("configured")).isEmpty(),
                "a null fetch result must fall back rather than NPE");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
