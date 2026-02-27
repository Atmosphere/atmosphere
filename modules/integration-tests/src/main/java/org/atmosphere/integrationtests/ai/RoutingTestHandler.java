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
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;

/**
 * Test handler for /ai/routing endpoint.
 * Routes prompts to different fake models based on content:
 * - "code" keyword -> code-model
 * - "translate" keyword -> translate-model
 * - default -> default-model
 * Each model emits distinct tokens so tests can verify routing.
 */
public class RoutingTestHandler implements AtmosphereHandler {

    private final RoutingLlmClient router;

    public RoutingTestHandler() {
        var codeClient = FakeLlmClient.withTokens("code-model",
                "CODE:", " function", " hello", "().", " Done.");
        var translateClient = FakeLlmClient.withTokens("translate-model",
                "TRANSLATE:", " Bonjour", " le", " monde.", " Fin.");
        var defaultClient = FakeLlmClient.withTokens("default-model",
                "DEFAULT:", " general", " response.", " Complete.");

        router = RoutingLlmClient.builder(defaultClient, "default-model")
                .route(RoutingLlmClient.RoutingRule.contentBased(
                        prompt -> prompt.toLowerCase().contains("code"),
                        codeClient, "code-model"))
                .route(RoutingLlmClient.RoutingRule.contentBased(
                        prompt -> prompt.toLowerCase().contains("translate"),
                        translateClient, "translate-model"))
                .build();
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("routing-handler").start(() -> {
                var session = StreamingSessions.start(resource);
                var request = ChatCompletionRequest.of("auto", trimmed);
                router.streamChatCompletion(request, session);
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
}
