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

    private final RoutingLlmClient costRouter;
    private final RoutingLlmClient latencyRouter;

    public CostLatencyRoutingTestHandler() {
        var cheapClient = FakeLlmClient.withTokens("cheap-model",
                "CHEAP:", " budget", " response.");
        var midClient = FakeLlmClient.withTokens("mid-model",
                "MID:", " balanced", " response.");
        var premiumClient = FakeLlmClient.withTokens("premium-model",
                "PREMIUM:", " high-quality", " response.");
        var defaultClient = FakeLlmClient.withTokens("default-model",
                "DEFAULT:", " fallback", " response.");

        // Cost-based routing: models with different cost profiles
        costRouter = RoutingLlmClient.builder(defaultClient, "default-model")
                .route(RoutingRule.costBased(Double.MAX_VALUE, List.of(
                        // costPerToken=0.01, capability=10 (best)
                        new ModelOption(premiumClient, "premium-model", 0.01, 200, 10),
                        // costPerToken=0.005, capability=7
                        new ModelOption(midClient, "mid-model", 0.005, 100, 7),
                        // costPerToken=0.001, capability=3 (cheapest)
                        new ModelOption(cheapClient, "cheap-model", 0.001, 50, 3)
                )))
                .build();

        // Latency-based routing: models with different latency profiles
        var fastClient = FakeLlmClient.withTokens("fast-model",
                "FAST:", " quick", " response.");
        var mediumClient = FakeLlmClient.withTokens("medium-model",
                "MEDIUM:", " moderate", " response.");
        var slowClient = FakeLlmClient.withTokens("slow-model",
                "SLOW:", " thorough", " response.");

        latencyRouter = RoutingLlmClient.builder(defaultClient, "default-model")
                .route(RoutingRule.latencyBased(Long.MAX_VALUE, List.of(
                        new ModelOption(slowClient, "slow-model", 0.001, 500, 10),
                        new ModelOption(mediumClient, "medium-model", 0.005, 150, 7),
                        new ModelOption(fastClient, "fast-model", 0.01, 30, 3)
                )))
                .build();
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
                routeRequest(trimmed, session, resource);
            });
        }
    }

    private void routeRequest(String prompt,
                              org.atmosphere.ai.StreamingSession session,
                              AtmosphereResource resource) {
        if (prompt.startsWith("cost:")) {
            // Format: "cost:<budget> <text>"
            var rest = prompt.substring(5);
            var spaceIdx = rest.indexOf(' ');
            var budget = Double.parseDouble(rest.substring(0, spaceIdx));
            var text = rest.substring(spaceIdx + 1);
            var maxTokens = (int) (budget / 0.001); // Normalize to token count

            var request = ChatCompletionRequest.builder("auto")
                    .user(text)
                    .maxTokens(maxTokens)
                    .build();

            // Build a cost router with the specific budget
            var cheapClient = FakeLlmClient.withTokens("cheap-model",
                    "CHEAP:", " budget", " response.");
            var midClient = FakeLlmClient.withTokens("mid-model",
                    "MID:", " balanced", " response.");
            var premiumClient = FakeLlmClient.withTokens("premium-model",
                    "PREMIUM:", " high-quality", " response.");
            var defaultClient = FakeLlmClient.withTokens("default-model",
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

            var fastClient = FakeLlmClient.withTokens("fast-model",
                    "FAST:", " quick", " response.");
            var mediumClient = FakeLlmClient.withTokens("medium-model",
                    "MEDIUM:", " moderate", " response.");
            var slowClient = FakeLlmClient.withTokens("slow-model",
                    "SLOW:", " thorough", " response.");
            var defaultClient = FakeLlmClient.withTokens("default-model",
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
            var defaultClient = FakeLlmClient.withTokens("default-model",
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
