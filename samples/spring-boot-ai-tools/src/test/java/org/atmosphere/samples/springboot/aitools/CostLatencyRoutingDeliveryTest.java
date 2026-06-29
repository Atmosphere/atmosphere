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
package org.atmosphere.samples.springboot.aitools;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.routing.RoutingLlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves the Atmosphere 4 blog §7 claim with the AI-tools sample: an agent
 * "can route across models by cost or latency". The routing engine
 * ({@link RoutingLlmClient}) and the property-driven auto-configuration that
 * installs it are real; this test makes the SAMPLE exercise them and asserts
 * the router actually <em>selects</em> the expected model — not merely that a
 * routing bean exists.
 *
 * <p>Each test boots the real {@link AiToolsApplication} with one of the
 * sample's shipped routing profiles
 * ({@code application-routing-cost.yml} / {@code application-routing-latency.yml}).
 * {@code llm.mode=fake} resolves a concrete, no-network {@code FakeLlmClient}, so
 * the run needs no API key and makes no outbound call. After startup the
 * {@code atmosphere.ai.routing.*} auto-configuration has wrapped that resolved
 * client in a {@link RoutingLlmClient} and installed it as
 * {@code AiConfig.get().client()} — the exact process-wide client every
 * {@code AgentRuntime} dispatch reads. The test drives a real
 * {@link ChatCompletionRequest} through that client and captures the
 * {@code routing.model} decision the router emits before it delegates.</p>
 *
 * <p><strong>The contrast (the heart of the proof).</strong> Both profiles
 * declare the <em>identical</em> two-model pool — {@code swift-pro} (premium:
 * fast + most capable, but expensive) and {@code frugal-mini} (cheap but slow).
 * The objective alone decides the pick:</p>
 * <ul>
 *   <li><b>cost</b> objective → {@code frugal-mini}: {@code swift-pro}'s projected
 *       cost ({@code 0.05 * 2048 = 102.4}) blows the {@code 10.0} budget, so the
 *       cheaper model wins even though it is less capable;</li>
 *   <li><b>latency</b> objective → {@code swift-pro}: {@code frugal-mini}'s
 *       {@code 900ms} average exceeds the {@code 100ms} budget, so the faster
 *       model wins.</li>
 * </ul>
 * <p>Same agent, same candidate models, opposite selection — which is exactly
 * "route across models by cost or latency".</p>
 */
class CostLatencyRoutingDeliveryTest {

    @AfterEach
    void resetGlobalClient() {
        // The router is installed into the process-wide AiConfig singleton; each
        // context's DisposableBean restores the resolved client on close, but
        // reset defensively so a sibling test cannot inherit a stale router.
        AiConfig.configure("fake", "demo-router", null, null);
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @Test
    void costObjectiveRoutesToTheCheapModel() {
        var routed = routeOneTurnUnderProfile("routing-cost");

        assertEquals("frugal-mini", routed,
                "cost routing must pick frugal-mini: swift-pro's projected cost "
                        + "(0.05 * 2048 = 102.4) exceeds the 10.0 budget, so the cheaper "
                        + "model is selected even though it is the less capable one");
        assertNotEquals("swift-pro", routed,
                "the premium model must NOT be chosen when its cost exceeds the budget");
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @Test
    void latencyObjectiveRoutesToTheFastModel() {
        var routed = routeOneTurnUnderProfile("routing-latency");

        assertEquals("swift-pro", routed,
                "latency routing must pick swift-pro: frugal-mini's 900ms average "
                        + "exceeds the 100ms budget, so the faster model is selected");
        assertNotEquals("frugal-mini", routed,
                "the slow model must NOT be chosen when its latency exceeds the budget");
    }

    /**
     * Boots {@link AiToolsApplication} with {@code profile} active, drives a
     * single chat turn through the config-installed {@link RoutingLlmClient}, and
     * returns the model the router selected (the value of the {@code routing.model}
     * metadata frame it emits before delegating to the underlying client).
     *
     * <p>The application is started on a random port with the WebTransport
     * sidecar disabled so two sequential boots in the same JVM never contend for
     * a fixed port. The context is always closed in a {@code finally} so its
     * {@code DisposableBean} restores the un-wrapped client.</p>
     */
    private String routeOneTurnUnderProfile(String profile) {
        var app = new SpringApplication(AiToolsApplication.class);
        app.setAdditionalProfiles(profile);
        ConfigurableApplicationContext context = app.run(
                "--server.port=0",
                "--atmosphere.web-transport.enabled=false");
        try {
            var client = AiConfig.get().client();
            assertNotNull(client, "AiConfig must resolve a client after startup");
            assertInstanceOf(RoutingLlmClient.class, client,
                    "atmosphere.ai.routing.enabled=true must install a RoutingLlmClient "
                            + "as the client every AgentRuntime dispatch reads");

            var session = new CapturingSession();
            client.streamChatCompletion(
                    ChatCompletionRequest.of("auto", "Summarize this quarterly report."),
                    session);

            var routed = session.metadata().get("routing.model");
            assertNotNull(routed,
                    "the router must emit a routing.model decision; metadata seen: "
                            + session.metadata());
            return String.valueOf(routed);
        } finally {
            context.close();
        }
    }

    /**
     * Minimal {@link StreamingSession} that records every {@code sendMetadata}
     * call so the test can read back the router's {@code routing.model} decision.
     * The streamed text is irrelevant to the routing assertion and is discarded.
     */
    private static final class CapturingSession implements StreamingSession {

        private final Map<String, Object> metadata = new ConcurrentHashMap<>();

        Map<String, Object> metadata() {
            return metadata;
        }

        @Override
        public String sessionId() {
            return "routing-delivery-test";
        }

        @Override
        public void send(String text) {
            // routing assertion is on the metadata decision, not the body
        }

        @Override
        public void sendMetadata(String key, Object value) {
            if (key != null && value != null) {
                metadata.put(key, value);
            }
        }

        @Override
        public void progress(String message) {
        }

        @Override
        public void complete() {
        }

        @Override
        public void complete(String summary) {
        }

        @Override
        public void error(Throwable t) {
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }
}
