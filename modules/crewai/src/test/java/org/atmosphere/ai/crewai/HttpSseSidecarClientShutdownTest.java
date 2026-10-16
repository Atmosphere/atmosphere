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
package org.atmosphere.ai.crewai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpSseSidecarClient#shutdown()} shuts down the HTTP client the
 * sidecar client built (what {@link CrewAiAgentRuntime} releases when its
 * application closes), and leaves an injected one to its owner.
 */
class HttpSseSidecarClientShutdownTest {

    private HttpServer server;
    private CrewAiSidecarConfig config;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        config = new CrewAiSidecarConfig(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shutdownStopsTheHttpClientTheSidecarClientBuilt() {
        var client = new HttpSseSidecarClient(config);
        assertTrue(client.health(), "the sidecar answers before the shutdown");

        client.shutdown();
        client.shutdown();

        assertFalse(client.health(), "the shut-down client sends no new request");
    }

    @Test
    void shutdownLeavesAnInjectedHttpClientRunning() {
        var injected = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        try {
            var client = new HttpSseSidecarClient(config, injected);

            client.shutdown();

            assertFalse(injected.isTerminated(), "an injected HTTP client belongs to the caller");
            assertTrue(client.health(), "it still serves");
        } finally {
            injected.shutdownNow();
        }
    }
}
