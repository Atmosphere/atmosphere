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
import org.atmosphere.ai.cache.AiResponseCacheInspector;
import org.atmosphere.ai.cache.AiResponseCacheListener;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.routing.RoutingLlmClient;
import org.atmosphere.ai.routing.RoutingLlmClient.RoutingRule;
import org.atmosphere.ai.routing.RoutingLlmClient.RoutingRule.ModelOption;
import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.util.List;

/**
 * Combined test handler for /ai/combined-cost-cache endpoint.
 * Exercises both cache listener coalescing AND cost/latency routing together.
 * Prompt format: "cost:&lt;budget&gt; &lt;text&gt;" or "latency:&lt;ms&gt; &lt;text&gt;"
 */
public class CombinedCostCacheTestHandler implements AtmosphereHandler {

    private volatile boolean cacheConfigured = false;
    private final AiResponseCacheListener cacheListener = new AiResponseCacheListener();

    public CombinedCostCacheTestHandler() {
        cacheListener.addCoalescedListener(event ->
                System.out.println("COMBINED_COALESCED:" + event.sessionId()
                        + ":tokens=" + event.totalTokens()
                        + ":status=" + event.status()
                        + ":elapsed=" + event.elapsedMs()
                        + ":broadcaster=" + event.broadcasterId()));
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        if (!cacheConfigured) {
            configureCache(resource);
            cacheConfigured = true;
        }
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("combined-handler").start(() -> {
                var session = StreamingSessions.start(resource);
                routeRequest(trimmed, session);
            });
        }
    }

    private void routeRequest(String prompt, org.atmosphere.ai.StreamingSession session) {
        if (prompt.startsWith("cost:")) {
            var rest = prompt.substring(5);
            var spaceIdx = rest.indexOf(' ');
            var budget = Double.parseDouble(rest.substring(0, spaceIdx));
            var text = rest.substring(spaceIdx + 1);
            var maxTokens = (int) (budget / 0.001);

            var request = ChatCompletionRequest.builder("auto")
                    .user(text)
                    .maxTokens(maxTokens)
                    .build();

            buildCostRouter(budget).streamChatCompletion(request, session);

        } else if (prompt.startsWith("latency:")) {
            var rest = prompt.substring(8);
            var spaceIdx = rest.indexOf(' ');
            var maxLatency = Long.parseLong(rest.substring(0, spaceIdx));
            var text = rest.substring(spaceIdx + 1);

            var request = ChatCompletionRequest.of("auto", text);
            buildLatencyRouter(maxLatency).streamChatCompletion(request, session);

        } else {
            var client = FakeLlmClient.withTokens("default-model",
                    "DEFAULT:", " fallback", " response.");
            var request = ChatCompletionRequest.of("default-model", prompt);
            client.streamChatCompletion(request, session);
        }
    }

    private RoutingLlmClient buildCostRouter(double budget) {
        var defaultClient = FakeLlmClient.withTokens("default-model",
                "DEFAULT:", " fallback", " response.");
        return RoutingLlmClient.builder(defaultClient, "default-model")
                .route(RoutingRule.costBased(budget, List.of(
                        new ModelOption(
                                FakeLlmClient.withTokens("premium-model",
                                        "PREMIUM:", " high", " quality", " output."),
                                "premium-model", 0.01, 200, 10),
                        new ModelOption(
                                FakeLlmClient.withTokens("mid-model",
                                        "MID:", " balanced", " output."),
                                "mid-model", 0.005, 100, 7),
                        new ModelOption(
                                FakeLlmClient.withTokens("cheap-model",
                                        "CHEAP:", " budget", " output."),
                                "cheap-model", 0.001, 50, 3)
                )))
                .build();
    }

    private RoutingLlmClient buildLatencyRouter(long maxLatency) {
        var defaultClient = FakeLlmClient.withTokens("default-model",
                "DEFAULT:", " fallback", " response.");
        return RoutingLlmClient.builder(defaultClient, "default-model")
                .route(RoutingRule.latencyBased(maxLatency, List.of(
                        new ModelOption(
                                FakeLlmClient.withTokens("slow-model",
                                        "SLOW:", " thorough", " deep", " output."),
                                "slow-model", 0.001, 500, 10),
                        new ModelOption(
                                FakeLlmClient.withTokens("medium-model",
                                        "MEDIUM:", " moderate", " output."),
                                "medium-model", 0.005, 150, 7),
                        new ModelOption(
                                FakeLlmClient.withTokens("fast-model",
                                        "FAST:", " quick", " output."),
                                "fast-model", 0.01, 30, 3)
                )))
                .build();
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

    private void configureCache(AtmosphereResource resource) {
        var broadcasterConfig = resource.getBroadcaster().getBroadcasterConfig();
        var atmosphereConfig = resource.getAtmosphereConfig();
        var cache = new UUIDBroadcasterCache();
        cache.configure(atmosphereConfig);
        cache.inspector(new AiResponseCacheInspector());
        cache.addBroadcasterCacheListener(cacheListener);
        broadcasterConfig.setBroadcasterCache(cache);
        cache.start();
    }
}
