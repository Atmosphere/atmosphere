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
package org.atmosphere.harnessoptin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP e2e for the per-endpoint {@code @AiEndpoint} harness opt-in. Boots a
 * full Spring context whose only annotated class is
 * {@link OptInHarnessEndpoint} — a bare {@code @AiEndpoint} declaring
 * {@code harness = {Harness.ALL}} with the app-wide flag left unset — and
 * asserts {@code /api/console/info} reports the harness primitives ACTIVE.
 *
 * <p>What this closes that the unit pins
 * ({@code AiEndpointProcessorHarnessPresetTest}) cannot: the opt-in truth
 * through the whole booted stack. There is deliberately no {@code @Agent} in
 * this package — an agent's batteries-on default would flip the same global
 * runtime-state map ACTIVE and mask a broken per-endpoint opt-in; here the
 * opt-in is the only possible source. The default-on and kill-switch boots
 * are pinned by the {@code org.atmosphere.harnesse2e} tests.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = HarnessOptInHttpE2eTest.TestApp.class,
        properties = {
                "atmosphere.packages=org.atmosphere.harnessoptin"
        })
class HarnessOptInHttpE2eTest {

    @LocalServerPort
    private int port;

    @Test
    void bareEndpointOptsIntoTheFullHarnessViaTheAnnotation() throws Exception {
        var body = consoleInfo(port);

        assertTrue(body.contains("\"harness\":{"),
                "console info must expose the harness runtime-truth block, got: " + body);
        assertTrue(body.contains("\"conversation-memory\":\"ACTIVE\""),
                "harness = {ALL} on a bare @AiEndpoint must flip conversation memory ACTIVE, got: "
                        + body);
        assertTrue(body.contains("\"prompt-cache-default\":\"conservative\""),
                "harness = {ALL} on a bare @AiEndpoint must seed the conservative prompt-cache "
                        + "policy, got: " + body);
    }

    static String consoleInfo(int port) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/console/info")).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), "console info endpoint must respond");
            return response.body();
        }
    }

    @SpringBootApplication
    static class TestApp {
    }
}
