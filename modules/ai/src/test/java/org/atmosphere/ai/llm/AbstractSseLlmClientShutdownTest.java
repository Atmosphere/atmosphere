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

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AbstractSseLlmClient#shutdown()} shuts down the HTTP client the SSE
 * client built, and leaves one it was given to its owner.
 */
class AbstractSseLlmClientShutdownTest {

    private static final class Probe extends AbstractSseLlmClient {
        Probe(HttpClient httpClient) {
            super(new AbstractSseLlmClient.SseClientConfig("http://127.0.0.1:1/", "k", httpClient,
                    Duration.ofSeconds(5), 16, Map.of()));
        }

        @Override
        protected String providerName() {
            return "probe";
        }
    }

    @Test
    void shutdownStopsTheHttpClientTheClientBuilt() throws Exception {
        var client = new Probe(null);

        client.shutdown();
        client.shutdown();

        assertTrue(client.httpClient.awaitTermination(Duration.ofSeconds(5)),
                "the built HTTP client terminates");
    }

    @Test
    void shutdownLeavesAGivenHttpClientRunning() {
        var given = HttpClient.newHttpClient();
        try {
            new Probe(given).shutdown();

            assertFalse(given.isTerminated(), "a given HTTP client belongs to the caller");
        } finally {
            given.shutdownNow();
        }
    }
}
