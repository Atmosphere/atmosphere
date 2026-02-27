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
import org.atmosphere.ai.fanout.FanOutStrategy;
import org.atmosphere.ai.fanout.FanOutStreamingSession;
import org.atmosphere.ai.fanout.ModelEndpoint;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.util.List;

/**
 * Test handler for /ai/fanout endpoint.
 * Uses 3 fake models with different speeds.
 * Prompt format: "strategy:prompt" where strategy is "all", "first", or "fastest:N".
 */
public class FanOutTestHandler implements AtmosphereHandler {

    private static final List<ModelEndpoint> ENDPOINTS = List.of(
            new ModelEndpoint("fast",
                    FakeLlmClient.slow("fast-model", 30, "Fast", "-token", "-1.", " Fast", "-token", "-2."),
                    "fast-model"),
            new ModelEndpoint("medium",
                    FakeLlmClient.slow("medium-model", 80, "Med", "-token", "-1.", " Med", "-token", "-2."),
                    "medium-model"),
            new ModelEndpoint("slow",
                    FakeLlmClient.slow("slow-model", 200, "Slow", "-token", "-1.", " Slow", "-token", "-2."),
                    "slow-model")
    );

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("fanout-handler").start(() -> {
                var parentSession = StreamingSessions.start(resource);
                var strategy = parseStrategy(trimmed);
                var baseRequest = ChatCompletionRequest.of("ignored", extractPrompt(trimmed));

                try (var fanOut = new FanOutStreamingSession(
                        parentSession, ENDPOINTS, strategy, resource)) {
                    fanOut.fanOut(baseRequest);
                }
            });
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

    private FanOutStrategy parseStrategy(String prompt) {
        if (prompt.startsWith("all:")) {
            return new FanOutStrategy.AllResponses();
        } else if (prompt.startsWith("first:")) {
            return new FanOutStrategy.FirstComplete();
        } else if (prompt.startsWith("fastest:")) {
            var parts = prompt.split(":", 3);
            var threshold = parts.length > 1 ? Integer.parseInt(parts[1]) : 3;
            return new FanOutStrategy.FastestTokens(threshold);
        }
        return new FanOutStrategy.AllResponses();
    }

    private String extractPrompt(String prompt) {
        if (prompt.startsWith("fastest:")) {
            var parts = prompt.split(":", 3);
            return parts.length > 2 ? parts[2] : "test";
        }
        var idx = prompt.indexOf(':');
        return idx >= 0 ? prompt.substring(idx + 1) : prompt;
    }
}
