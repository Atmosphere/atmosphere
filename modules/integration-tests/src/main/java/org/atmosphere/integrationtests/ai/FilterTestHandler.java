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
import org.atmosphere.ai.filter.ContentSafetyFilter;
import org.atmosphere.ai.filter.CostMeteringFilter;
import org.atmosphere.ai.filter.PiiRedactionFilter;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.util.Set;

/**
 * Test handler for /ai/filters endpoint.
 * Registers PII, Safety, and Cost filters on the broadcaster.
 * The prompt text controls which fake LLM scenario is used.
 */
public class FilterTestHandler implements AtmosphereHandler {

    private volatile boolean filtersConfigured = false;

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        if (!filtersConfigured) {
            configureFilters(resource);
            filtersConfigured = true;
        }
        resource.suspend();

        // Read incoming WebSocket message (if any)
        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("filter-handler").start(() -> {
                var session = StreamingSessions.start(resource);
                var client = selectClient(trimmed);
                var request = ChatCompletionRequest.of(client.modelName(), trimmed);
                client.streamChatCompletion(request, session);
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

    private void configureFilters(AtmosphereResource resource) {
        var config = resource.getBroadcaster().getBroadcasterConfig();
        config.addFilter(new PiiRedactionFilter());
        config.addFilter(new ContentSafetyFilter(
                ContentSafetyFilter.keywordChecker(Set.of("harmful", "dangerous", "violent"))));
        config.addFilter(new CostMeteringFilter());
    }

    private FakeLlmClient selectClient(String prompt) {
        if (prompt.startsWith("pii:")) {
            return FakeLlmClient.withPii("pii-model");
        } else if (prompt.startsWith("safety:")) {
            var harmfulWord = prompt.substring("safety:".length()).trim();
            if (harmfulWord.isEmpty()) {
                harmfulWord = "harmful";
            }
            return FakeLlmClient.withHarmfulContent("safety-model", harmfulWord);
        } else {
            return FakeLlmClient.withTokens("default-model",
                    "Hello", " from", " the", " AI", ".");
        }
    }
}
