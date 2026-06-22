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
package org.atmosphere.spring.boot;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.Function;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.impl.AtmosphereClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring Boot HTTP e2e for <strong>config-driven</strong> request routing.
 * Boots a full Spring context with the AI auto-config and
 * {@code atmosphere.ai.routing.*} properties set, opens a real WebSocket to a
 * top-level {@code @AiEndpoint} via wasync, fires a prompt, and asserts the
 * {@code routing.model} decision streams back over the socket.
 *
 * <p><strong>What this test closes.</strong> The config path was already
 * covered at two layers, but a seam sat between them:</p>
 * <ul>
 *   <li>{@code AtmosphereRoutingAutoConfigurationTest} proves
 *       {@code atmosphere.ai.routing.*} binds and installs a
 *       {@code RoutingLlmClient} as {@code AiConfig.get().client()} — but
 *       drives that router with a <em>capturing/mock</em> session, never over a
 *       transport.</li>
 *   <li>The {@code ai-routing.spec.ts} Playwright e2e proves a router routes
 *       on a live server — but builds it with the <em>programmatic</em>
 *       {@code RoutingLlmClient.builder(...)} (the plain-Jetty
 *       {@code AiFeatureTestServer} has no Spring auto-config).</li>
 * </ul>
 * <p>Neither proves that a real {@code @AiEndpoint} turn actually
 * <em>consumes</em> the <em>config-installed</em> router and streams its
 * decision to a connected client. This test does: the
 * {@link org.atmosphere.spring.boot.routing.RoutingHttpE2eEndpoint @Prompt}
 * reads {@code AiConfig.get().client()} (the exact client every
 * {@code AgentRuntime} dispatch reads) and drives it over the live wire
 * session, so a config regression — auto-config not installing the router,
 * another bean overwriting {@code AiConfig.client()}, or the dispatch path
 * resolving a different client — fails here.</p>
 *
 * <p>{@code atmosphere.ai.mode=fake} resolves a concrete, no-network client so
 * the turn needs no API key; the content rule and the default fall-through use
 * distinct model names so each assertion is unambiguous. Wire-level driving
 * uses {@link AtmosphereClient} (wasync), the same client production samples
 * use.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = RoutingConfigHttpE2eTest.TestApp.class,
        properties = {
                // Scan ONLY the routing sub-package so the bean-dependent
                // LtmHttpE2eEndpoint in the parent package is not picked up.
                "atmosphere.packages=org.atmosphere.spring.boot.routing",
                // Concrete, no-network client — no API key required.
                "atmosphere.ai.mode=fake",
                "atmosphere.ai.model=default-wire-model",
                // The feature under test: config-driven routing.
                "atmosphere.ai.routing.enabled=true",
                "atmosphere.ai.routing.default-model=default-wire-model",
                "atmosphere.ai.routing.content-rules[0].keywords[0]=refactor",
                "atmosphere.ai.routing.content-rules[0].model=code-wire-model"
        })
class RoutingConfigHttpE2eTest {

    @LocalServerPort
    private int port;

    @AfterAll
    static void resetGlobalClient() {
        // The router is installed into the process-wide AiConfig singleton; the
        // installer's DisposableBean restores it on context close, but reset
        // defensively so a sibling test in the same fork cannot inherit it.
        AiConfig.configure("fake", "default-wire-model", null, null);
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    void contentMatchedPromptRoutesToConfiguredModelOverTheWire() throws Exception {
        // "refactor" matches content-rules[0] -> routes to the configured model.
        assertRoutesTo("Please refactor this method", "code-wire-model");
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    void unmatchedPromptFallsThroughToDefaultModelOverTheWire() throws Exception {
        // No keyword match -> the router falls through to the default model.
        assertRoutesTo("Hello there, how are you?", "default-wire-model");
    }

    /**
     * Opens a real WebSocket to the config-driven {@code @AiEndpoint}, fires
     * {@code prompt}, and asserts the {@code routing.model} metadata frame that
     * streams back carries {@code expectedModel} — proving the
     * config-installed router made (and transmitted) the routing decision.
     */
    private void assertRoutesTo(String prompt, String expectedModel) throws Exception {
        var client = AtmosphereClient.newClient();
        var openLatch = new CountDownLatch(1);
        var routingLatch = new CountDownLatch(1);
        var completeLatch = new CountDownLatch(1);
        var routingFrame = new AtomicReference<String>();
        var lastReceived = new AtomicReference<String>();

        var options = client.newOptionsBuilder().reconnect(false).build();
        var request = client.newRequestBuilder()
                .uri("ws://localhost:" + port + "/atmosphere/routing-e2e")
                .transport(Request.TRANSPORT.WEBSOCKET)
                .enableProtocol(false)
                .build();

        var socket = client.create(options);
        try {
            socket.on(Event.OPEN, (Function<Object>) o -> openLatch.countDown())
                  .on(Event.MESSAGE, (Function<Object>) m -> {
                      var s = m.toString();
                      lastReceived.set(s);
                      // The router emits {"type":"metadata","key":"routing.model",
                      // "value":"<model>",...} before streaming any text.
                      if (s.contains("routing.model")) {
                          routingFrame.set(s);
                          routingLatch.countDown();
                      }
                      // The session ends with {"type":"complete",...}.
                      if (s.contains("\"type\":\"complete\"")) {
                          completeLatch.countDown();
                      }
                  })
                  .open(request);

            assertTrue(openLatch.await(10, TimeUnit.SECONDS),
                    "WebSocket should connect to /atmosphere/routing-e2e");

            socket.fire(prompt);

            assertTrue(routingLatch.await(15, TimeUnit.SECONDS),
                    "A routing.model metadata frame should stream back; last received was: "
                            + lastReceived.get());
            assertTrue(routingFrame.get().contains(expectedModel),
                    "config-driven routing should select '" + expectedModel + "' for prompt '"
                            + prompt + "'; routing frame was: " + routingFrame.get());

            // Let the turn drain to its "complete" frame before closing so the
            // socket shuts down cleanly (no trailing send-on-closed-session
            // noise). Best-effort only — the routing assertions above are the
            // contract; completion timing must never make the test flaky.
            completeLatch.await(10, TimeUnit.SECONDS);
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
                // best-effort close; the test assertions have already run.
            }
        }
    }

    @SpringBootApplication
    static class TestApp {
    }
}
