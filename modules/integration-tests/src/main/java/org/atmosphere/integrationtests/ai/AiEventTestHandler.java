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

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.util.Map;

/**
 * Test handler for AiEvent E2E tests. Emits a sequence of structured events
 * so Playwright specs can verify the wire protocol and client-side parsing.
 *
 * <p>Prompt "tools" → emits ToolStart, ToolResult, TextDelta, Complete.</p>
 * <p>Prompt "agent" → emits AgentStep, Progress, TextDelta, Complete.</p>
 * <p>Prompt "entity" → emits EntityStart, StructuredField×3, EntityComplete, Complete.</p>
 * <p>Prompt "error" → emits Error event.</p>
 * <p>Any other prompt → emits TextDelta tokens + Complete.</p>
 */
public class AiEventTestHandler implements AtmosphereHandler {

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("event-test").start(() ->
                    handlePrompt(trimmed, resource));
        }
    }

    private void handlePrompt(String prompt, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);
        try {
            switch (prompt) {
                case "tools" -> {
                    session.emit(new AiEvent.ToolStart("get_weather",
                            Map.of("city", "Montreal")));
                    Thread.sleep(10);
                    session.emit(new AiEvent.ToolResult("get_weather",
                            Map.of("temp", 22, "unit", "C")));
                    Thread.sleep(10);
                    session.emit(new AiEvent.TextDelta("The weather in Montreal is 22°C."));
                    session.emit(new AiEvent.Complete(null, Map.of()));
                }
                case "agent" -> {
                    session.emit(new AiEvent.AgentStep("research",
                            "Searching for information", Map.of("source", "web")));
                    Thread.sleep(10);
                    session.emit(new AiEvent.Progress("Analyzing results...", 0.5));
                    Thread.sleep(10);
                    session.emit(new AiEvent.AgentStep("synthesize",
                            "Writing response", Map.of()));
                    Thread.sleep(10);
                    session.emit(new AiEvent.TextDelta("Here is my analysis."));
                    session.emit(new AiEvent.Complete("Here is my analysis.",
                            Map.of("steps", 2)));
                }
                case "entity" -> {
                    session.emit(new AiEvent.EntityStart("UserProfile",
                            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
                                    + "\"age\":{\"type\":\"integer\"},\"city\":{\"type\":\"string\"}}}"));
                    Thread.sleep(10);
                    session.emit(new AiEvent.StructuredField("name", "Jean-François", "string"));
                    Thread.sleep(10);
                    session.emit(new AiEvent.StructuredField("age", 42, "integer"));
                    Thread.sleep(10);
                    session.emit(new AiEvent.StructuredField("city", "Montreal", "string"));
                    session.emit(new AiEvent.EntityComplete("UserProfile",
                            Map.of("name", "Jean-François", "age", 42, "city", "Montreal")));
                    session.emit(new AiEvent.Complete(null, Map.of()));
                }
                case "error" -> session.emit(new AiEvent.Error(
                        "Rate limit exceeded", "rate_limit", true));
                default -> {
                    // Default: emit text deltas word by word
                    for (var word : prompt.split("\\s+")) {
                        session.emit(new AiEvent.TextDelta(word + " "));
                    }
                    session.emit(new AiEvent.Complete(null, Map.of()));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.emit(new AiEvent.Error("Interrupted", "interrupted", false));
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout() {
            || event.isClosedByClient() || event.isClosedByApplication()) {
        }
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw && raw.message() instanceof String json) {
            event.getResource().getResponse().write(json);
            event.getResource().getResponse().flushBuffer();
        }
    }

    @Override
    public void destroy() {
    }
}
