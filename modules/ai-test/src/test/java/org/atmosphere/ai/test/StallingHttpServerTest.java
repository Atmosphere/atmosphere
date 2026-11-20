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
package org.atmosphere.ai.test;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stalling provider the stream idle contract relies on behaves as documented. */
class StallingHttpServerTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).build();

    private static HttpRequest post(StallingHttpServer server) {
        return HttpRequest.newBuilder(URI.create(server.baseUrl() + "/v1/chat/completions"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"m\"}")).build();
    }

    @Test
    void stallAfterChunksSendsTheChunksThenHoldsTheConnectionUntilTheClientCloses() throws Exception {
        try (var server = new StallingHttpServer(
                StallingHttpServer.Mode.stallAfterChunks(2, StallingHttpServer.Dialect.OPENAI))) {
            var response = CLIENT.send(post(server), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            var body = response.body();
            var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            var dataLines = 0;
            while (dataLines < 2) {
                var line = reader.readLine();
                if (line.startsWith("data:")) {
                    assertTrue(line.contains(StallingHttpServer.CHUNK_TEXT), line);
                    dataLines++;
                }
            }
            assertTrue(server.requests().getFirst().contains("{\"model\":\"m\"}"), "the body was read");
            assertFalse(server.clientClosed(Duration.ofMillis(300)), "the client has not let go yet");
            body.close();
            assertTrue(server.clientClosed(Duration.ofSeconds(3)), "closing the body closes the connection");
        }
    }

    @Test
    void stallBeforeHeadersNeverAnswers() throws Exception {
        try (var server = new StallingHttpServer(
                StallingHttpServer.Mode.stallBeforeHeaders(StallingHttpServer.Dialect.OPENAI))) {
            var pending = CLIENT.sendAsync(post(server), HttpResponse.BodyHandlers.ofInputStream());
            assertTrue(server.awaitRequest(Duration.ofSeconds(5)));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> pending.get(500, TimeUnit.MILLISECONDS));
            pending.cancel(true);
        }
    }

    @Test
    void anAnswerEndsInEachDialect() throws Exception {
        for (var dialect : StallingHttpServer.Dialect.values()) {
            try (var server = new StallingHttpServer(StallingHttpServer.Mode.answer(dialect))) {
                var response = CLIENT.send(post(server), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains(StallingHttpServer.CHUNK_TEXT), dialect + ": " + response.body());
            }
        }
    }

    @Test
    void keepalivesCarryNoTextUntilTheAnswer() throws Exception {
        try (var server = new StallingHttpServer(StallingHttpServer.Mode.keepaliveEvery(
                Duration.ofMillis(50), Duration.ofMillis(200), StallingHttpServer.Dialect.ANTHROPIC))) {
            var body = CLIENT.send(post(server), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(body.contains("event: ping"), body);
            assertEquals(body.indexOf(StallingHttpServer.CHUNK_TEXT), body.lastIndexOf(StallingHttpServer.CHUNK_TEXT),
                    "one text chunk, after the keep-alives");
            assertTrue(body.indexOf("event: ping") < body.indexOf(StallingHttpServer.CHUNK_TEXT));
        }
    }
}
