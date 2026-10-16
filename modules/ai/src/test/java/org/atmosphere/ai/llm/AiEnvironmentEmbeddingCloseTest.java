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

import com.sun.net.httpserver.HttpServer;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiConfigTestSupport;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.EmbeddingRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each application has its own {@link BuiltInEmbeddingRuntime}, and with it
 * its own HTTP client. Closing the application shuts that client down, so a
 * stopped application (a redeploy, an evicted test context) leaves no
 * selector thread or pooled connection behind, while the client of an
 * application still running, a request already sent, and a runtime the
 * application created itself are left alone.
 */
// Resets the static AI facade (AiConfig) between tests: deprecated for
// framework code, kept for code that runs with no application.
@SuppressWarnings("deprecation")
class AiEnvironmentEmbeddingCloseTest {

    private static final String REPLY = "{\"data\":[{\"embedding\":[0.1,0.2]}]}";
    /** How long a client left open is watched for terminating: a shut-down idle client terminates at once. */
    private static final Duration OPEN = Duration.ofMillis(500);

    private HttpServer server;
    private final CountDownLatch slowArrived = new CountDownLatch(1);
    private final CountDownLatch releaseSlow = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        AiConfig.resetHostConfig();
        AiConfigTestSupport.resetSettings();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/embeddings", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("slow")) {
                slowArrived.countDown();
                try {
                    releaseSlow.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            var reply = REPLY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            try (var out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        releaseSlow.countDown();
        server.stop(0);
        AiConfig.resetHostConfig();
        AiConfigTestSupport.resetSettings();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private AiEnvironment application() {
        var environment = AiEnvironment.create(
                Map.of(AiConfig.EMBEDDING_MODEL_PROPERTY, "text-embedding-3-small")::get);
        environment.configure("remote", "gpt-4o-mini", "k", baseUrl());
        return environment;
    }

    private static BuiltInEmbeddingRuntime builtIn(AiEnvironment environment) {
        EmbeddingRuntime runtime = environment.embeddings().resolve().orElseThrow();
        return assertInstanceOf(BuiltInEmbeddingRuntime.class, runtime);
    }

    @Test
    void closingOneApplicationShutsDownOnlyItsBuiltInEmbeddingClient() throws Exception {
        var a = application();
        var b = application();
        try {
            var ofA = builtIn(a);
            var ofB = builtIn(b);
            assertNotSame(ofA, ofB, "each application has its own embedding runtime");
            assertEquals(2, ofA.embed("before").length);
            assertEquals(2, ofB.embed("before").length);
            var httpOfA = ofA.httpClient();
            var httpOfB = ofB.httpClient();

            a.close();

            assertTrue(httpOfA.awaitTermination(Duration.ofSeconds(5)),
                    "the closed application's embedding HTTP client is shut down");
            assertFalse(httpOfB.awaitTermination(OPEN),
                    "the running application's embedding HTTP client stays open");
            var refused = assertThrows(RuntimeException.class, () -> ofA.embed("after"),
                    "the closed application's embedding client takes no new request");
            assertTrue(String.valueOf(refused.getMessage()).contains("closed"), refused.toString());
            assertEquals(2, ofB.embed("after").length,
                    "the running application's embedding client still serves");
            assertTrue(a.embeddings().resolveAll().isEmpty(),
                    "a closed application resolves no new embedding runtime");
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    void closingAnApplicationLetsAnEmbeddingRequestAlreadySentFinish() throws Exception {
        var a = application();
        try {
            var ofA = builtIn(a);
            var inFlight = CompletableFuture.supplyAsync(() -> ofA.embed("slow"));
            assertTrue(slowArrived.await(10, TimeUnit.SECONDS), "the request reached the server");

            a.close();
            releaseSlow.countDown();

            assertEquals(2, inFlight.get(10, TimeUnit.SECONDS).length,
                    "a request sent before the close completes");
        } finally {
            a.close();
        }
    }

    @Test
    void closingAnApplicationLeavesAnEmbeddingRuntimeItCreatedItself() throws Exception {
        var a = application();
        var own = new BuiltInEmbeddingRuntime();
        own.attach(a);
        try {
            assertEquals(2, own.embed("before").length);
            builtIn(a).embed("before");
            var httpOfOwn = own.httpClient();

            a.close();

            assertFalse(httpOfOwn.awaitTermination(OPEN),
                    "the HTTP client of a runtime the application created stays open");

            assertEquals(2, own.embed("after").length,
                    "a runtime the application created is the application's to close");
        } finally {
            a.close();
            own.onEnvironmentClose();
        }
    }
}
