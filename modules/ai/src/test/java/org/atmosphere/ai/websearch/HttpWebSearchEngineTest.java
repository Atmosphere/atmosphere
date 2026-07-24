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
package org.atmosphere.ai.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Exercises the default {@link HttpWebSearchEngine} against a local
 * {@code com.sun.net.httpserver.HttpServer} — real HTTP + real JSON parsing, no
 * external network. Confirms it URL-encodes the query, parses both the top-level
 * {@code results} and nested {@code web.results} JSON shapes, bounds the result
 * count, and returns fail-closed data on a non-2xx response.
 */
public class HttpWebSearchEngineTest {

    private static HttpServer server;
    private static int port;
    private static final AtomicReference<String> LAST_QUERY = new AtomicReference<>();

    @BeforeAll
    public static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        // Top-level {"results":[{title,url,content}, ...]} shape.
        server.createContext("/flat", exchange -> {
            LAST_QUERY.set(exchange.getRequestURI().getQuery());
            respond(exchange, 200, """
                    {"results":[
                      {"title":"First","url":"https://x.example/1","content":"Snippet one"},
                      {"title":"Second","url":"https://x.example/2","content":"Snippet two"},
                      {"title":"Third","url":"https://x.example/3","content":"Snippet three"}
                    ]}""");
        });

        // Nested {"web":{"results":[{title,url,description}]}} shape.
        server.createContext("/nested", exchange -> respond(exchange, 200, """
                {"web":{"results":[
                  {"title":"Nested hit","url":"https://y.example/1","description":"Nested snippet"}
                ]}}"""));

        // Error response — the engine must return fail-closed data, not throw.
        server.createContext("/boom", exchange -> respond(exchange, 500, "upstream exploded"));

        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    public static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static HttpWebSearchEngine engine(String path) {
        var config = new WebSearchConfig("http://127.0.0.1:" + port + path,
                "", null, null, 5, 4_000);
        return new HttpWebSearchEngine(config);
    }

    @Test
    public void parsesFlatResultsAndUrlEncodesTheQuery() {
        var engine = engine("/flat");
        assertTrue(engine.isConfigured());

        var results = engine.search(WebSearchQuery.of("hello world & friends", 5));
        assertTrue(results.available(), results.message());
        assertEquals(3, results.results().size());
        assertEquals("First", results.results().get(0).title());
        assertEquals("https://x.example/1", results.results().get(0).url());
        assertEquals("Snippet one", results.results().get(0).snippet());

        // The server saw a properly URL-encoded query parameter.
        var seen = LAST_QUERY.get();
        assertTrue(seen != null && seen.startsWith("q="), seen);
        assertFalse(seen.contains(" "), "spaces must be percent-encoded: " + seen);

        // And it formats into the numbered, model-facing block.
        var text = results.toModelText();
        assertTrue(text.contains("[1] First"), text);
        assertTrue(text.contains("[3] Third"), text);
    }

    @Test
    public void boundsTheResultCountToTheRequestedCeiling() {
        var results = engine("/flat").search(WebSearchQuery.of("q", 2));
        assertTrue(results.available());
        assertEquals(2, results.results().size());
    }

    @Test
    public void parsesNestedWebResultsShape() {
        var results = engine("/nested").search(WebSearchQuery.of("q", 5));
        assertTrue(results.available(), results.message());
        assertEquals(1, results.results().size());
        assertEquals("Nested hit", results.results().get(0).title());
        assertEquals("Nested snippet", results.results().get(0).snippet());
    }

    @Test
    public void nonSuccessStatusReturnsFailClosedData() {
        var results = engine("/boom").search(WebSearchQuery.of("q", 5));
        assertFalse(results.available());
        assertTrue(results.message().contains("500"), results.message());
    }
}
