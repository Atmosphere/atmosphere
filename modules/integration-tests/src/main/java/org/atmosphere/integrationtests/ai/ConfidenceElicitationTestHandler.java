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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfidenceElicitation;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * E2E test handler exercising the framework-level
 * {@link AiConfidenceElicitation} path through {@link AiPipeline}'s
 * {@code ConfidenceCapturingSession} decorator. Verifies that the wire
 * receives the {@code ai.confidence.aggregate} / {@code .source} /
 * {@code .tokens} metadata frames the
 * {@link StreamingSession#confidence} default sink emits.
 *
 * <p>Prompt forms:</p>
 * <ul>
 *   <li>{@code reported} — runtime returns text with a
 *       {@code "confidence": 0.83} field; decorator parses, fires confidence
 *       with {@code MODEL_REPORTED_FIELD} source.</li>
 *   <li>{@code missing} — runtime returns text with no confidence field;
 *       decorator emits {@code unknown(MODEL_REPORTED_FIELD)}.</li>
 *   <li>{@code out-of-range} — runtime returns {@code "confidence": 5.0};
 *       decorator emits {@code unknown(MODEL_REPORTED_FIELD)}.</li>
 *   <li>{@code disabled} — pipeline has no elicitation configured; no
 *       confidence event reaches the wire.</li>
 *   <li>{@code per-request:FIELD} — pipeline default elicits
 *       {@code "confidence"}, but per-request metadata overrides to
 *       {@code FIELD}; runtime returns text containing
 *       {@code "FIELD": 0.42}.</li>
 * </ul>
 */
public class ConfidenceElicitationTestHandler implements AtmosphereHandler {

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();
        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("confidence-elicitation-test").start(() ->
                    handlePrompt(trimmed, resource));
        }
    }

    private void handlePrompt(String prompt, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);
        var pipeline = new AiPipeline(new TextEmittingRuntime(prompt),
                "system", "test-model",
                null, null, List.of(), List.of(), AiMetrics.NOOP);

        if (prompt.startsWith("per-request:")) {
            var fieldName = prompt.substring("per-request:".length());
            pipeline.setDefaultConfidenceElicitation(AiConfidenceElicitation.defaults());
            pipeline.execute("client-1", "go", session,
                    Map.of(AiConfidenceElicitation.METADATA_KEY,
                            AiConfidenceElicitation.withField(fieldName)));
            return;
        }
        if (!prompt.equals("disabled")) {
            pipeline.setDefaultConfidenceElicitation(AiConfidenceElicitation.defaults());
        }
        pipeline.execute("client-1", "go", session);
    }

    private static final class TextEmittingRuntime implements AgentRuntime {
        private final String prompt;

        TextEmittingRuntime(String prompt) {
            this.prompt = prompt;
        }

        @Override public String name() { return "confidence-test-runtime"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING,
                    AiCapability.SYSTEM_PROMPT,
                    AiCapability.CONFIDENCE_SCORES);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            switch (prompt) {
                case "reported", "disabled" ->
                        session.send("answer is 42 {\"confidence\": 0.83}");
                case "missing" -> session.send("answer is 42, no confidence field present");
                case "out-of-range" -> session.send("answer {\"confidence\": 5.0}");
                default -> {
                    if (prompt.startsWith("per-request:")) {
                        var fieldName = prompt.substring("per-request:".length());
                        session.send("answer {\"" + fieldName + "\": 0.42}");
                    } else {
                        session.send("default response");
                    }
                }
            }
            session.complete();
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException { /* no-op */ }

    @Override
    public void destroy() { /* no-op */ }
}
