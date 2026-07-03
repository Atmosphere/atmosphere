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
package org.atmosphere.harnesse2e;

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
 * HTTP e2e for the {@code @Agent} batteries-on default. Boots a full Spring
 * context whose only annotated class is {@link DefaultOnHarnessAgent} — a
 * bare {@code @Agent} with no {@code harness} attribute and no app-wide
 * harness config — and asserts {@code /api/console/info} reports the
 * harness primitives genuinely ACTIVE.
 *
 * <p>What this closes that the unit tests cannot: the default-on truth
 * through the whole stack — Spring auto-configuration, the Atmosphere
 * annotation scan discovering the agent, {@code AgentProcessor} resolving
 * {@code featuresFor(path, {ALL}, true)}, the per-feature attach, and the
 * console endpoint publishing the runtime state a browser sees. The
 * processor-level precedence table itself is pinned by
 * {@code AgentProcessorHarnessTest} (modules/agent); the kill-switch
 * counterpart boot is {@link HarnessKillSwitchHttpE2eTest}.</p>
 *
 * <p>The test lives in its own package (scanned via
 * {@code atmosphere.packages=org.atmosphere.harnesse2e}) so its fixture and
 * the fixtures of other HTTP e2e tests in {@code org.atmosphere.spring.boot}
 * can never leak into each other's frameworks through the recursive
 * annotation scan.</p>
 *
 * <p>Assertions match raw JSON key/value pairs — the payload is a Jackson
 * map serialization, so a single {@code "key":"value"} pair is a stable,
 * order-independent probe without coupling the test to a JSON parser
 * flavor.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = HarnessRuntimeTruthHttpE2eTest.TestApp.class,
        properties = {
                "atmosphere.packages=org.atmosphere.harnesse2e"
        })
class HarnessRuntimeTruthHttpE2eTest {

    @LocalServerPort
    private int port;

    @Test
    void bareAgentActivatesTheFullHarnessByDefault() throws Exception {
        var body = consoleInfo(port);

        assertTrue(body.contains("\"harness\":{"),
                "console info must expose the harness runtime-truth block, got: " + body);
        assertTrue(body.contains("\"conversation-memory\":\"ACTIVE\""),
                "a bare @Agent must have conversation memory ACTIVE by default, got: " + body);
        assertTrue(body.contains("\"prompt-cache-default\":\"conservative\""),
                "the ALL default must seed the conservative prompt-cache policy, got: " + body);
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
