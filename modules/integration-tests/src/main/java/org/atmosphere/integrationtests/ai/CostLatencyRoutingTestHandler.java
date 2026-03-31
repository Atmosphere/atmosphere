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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.routing.RoutingLlmClient;
import org.atmosphere.ai.routing.RoutingLlmClient.RoutingRule;
import org.atmosphere.ai.routing.RoutingLlmClient.RoutingRule.ModelOption;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.util.List;

/**
 * Test handler for /ai/cost-routing endpoint.
 * Uses CostBased + LatencyBased routing rules.
 * Prompt format: "cost:&lt;budget&gt; &lt;text&gt;" or "latency:&lt;ms&gt; &lt;text&gt;"
 */
public class CostLatencyRoutingTestHandler implements AtmosphereHandler {

    public CostLatencyRoutingTestHandler() {
        // Routers are created per-request in routeRequest() with specific budgets/latencies
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("cost-routing-handler").start(() -> {
                var session = StreamingSessions.start(resource);
                routeRequest(trimmed, session);
            });
        }
    }

    private void routeRequest(String prompt,
                              org.atmosphere.ai.StreamingSession session) {
        if (prompt.startsWith("cost:")) {
            // Format: "cost:<budget> <text>"
            var rest = prompt.substring(5);
            var spaceIdx = rest.indexOf(' ');
            var budget = Double.parseDouble(rest.substring(0, spaceIdx));
            var text = rest.substring(spaceIdx + 1);
            var maxStreamingTexts = (int) (budget / 0.001); // Normalize to streaming text count

            var request = ChatCompletionRequest.builder("auto")
                    .user(text)
                    .maxStreamingTexts(maxStreamingTexts)
                    .build();

            // Build a cost router with the specific budget
            var cheapClient = FakeLlmClient.withTexts("cheap-model",
                    "CHEAP:", " budget", " response.");
            var midClient = FakeLlmClient.withTexts("mid-model",
                    "MID:", " balanced", " response.");
            var premiumClient = FakeLlmClient.withTexts("premium-model",
                    "PREMIUM:", " high-quality", " response.");
            var defaultClient = FakeLlmClient.withTexts("default-model",
                    "DEFAULT:", " fallback", " response.");

            var router = RoutingLlmClient.builder(defaultClient, "default-model")
                    .route(RoutingRule.costBased(budget, List.of(
                            new ModelOption(premiumClient, "premium-model", 0.01, 200, 10),
                            new ModelOption(midClient, "mid-model", 0.005, 100, 7),
                            new ModelOption(cheapClient, "cheap-model", 0.001, 50, 3)
                    )))
                    .build();

            router.streamChatCompletion(request, session);

        } else if (prompt.startsWith("latency:")) {
            // Format: "latency:<ms> <text>"
            var rest = prompt.substring(8);
            var spaceIdx = rest.indexOf(' ');
            var maxLatency = Long.parseLong(rest.substring(0, spaceIdx));
            var text = rest.substring(spaceIdx + 1);

            var request = ChatCompletionRequest.of("auto", text);

            var fastClient = FakeLlmClient.withTexts("fast-model",
                    "FAST:", " quick", " response.");
            var mediumClient = FakeLlmClient.withTexts("medium-model",
                    "MEDIUM:", " moderate", " response.");
            var slowClient = FakeLlmClient.withTexts("slow-model",
                    "SLOW:", " thorough", " response.");
            var defaultClient = FakeLlmClient.withTexts("default-model",
                    "DEFAULT:", " fallback", " response.");

            var router = RoutingLlmClient.builder(defaultClient, "default-model")
                    .route(RoutingRule.latencyBased(maxLatency, List.of(
                            new ModelOption(slowClient, "slow-model", 0.001, 500, 10),
                            new ModelOption(mediumClient, "medium-model", 0.005, 150, 7),
                            new ModelOption(fastClient, "fast-model", 0.01, 30, 3)
                    )))
                    .build();

            router.streamChatCompletion(request, session);

        } else {
            // Unknown format — use default
            var defaultClient = FakeLlmClient.withTexts("default-model",
                    "DEFAULT:", " fallback", " response.");
            var request = ChatCompletionRequest.of("default-model", prompt);
            defaultClient.streamChatCompletion(request, session);
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()) {
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw) {
            var inner = raw.message();
            if (inner instanceof String json) {
                event.getResource().getResponse().write(json);
                event.getResource().getResponse().flushBuffer();
            }
        }
    }

    @Override
    public void destroy() {
    }
}
