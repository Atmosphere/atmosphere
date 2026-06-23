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
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.NativeStructuredOutput;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.integrationtests.ai.structured.FilmReview;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * E2E handler exercising provider-native structured output
 * ({@link AiCapability#NATIVE_STRUCTURED_OUTPUT}) through the full
 * {@link AiPipeline} — including the {@code NativeStructuredDispatch}
 * graceful-fall-back seam — against a deterministic runtime (no live LLM key
 * needed, so this runs on CI).
 *
 * <p>The runtime declares {@code NATIVE_STRUCTURED_OUTPUT}, so the pipeline
 * stamps the apply flag and wraps the dispatch. Prompt forms:</p>
 * <ul>
 *   <li>{@code ok} — the native attempt succeeds: it sees the apply flag,
 *       emits a {@code test.native.attempt=true} breadcrumb, returns valid JSON,
 *       and the {@code StructuredOutputCapturingSession} emits
 *       {@code entity-complete} with the parsed {@link FilmReview}.</li>
 *   <li>{@code reject} — the native attempt rejects the schema <em>pre-stream</em>
 *       (simulating an HTTP 400 on an unsupported schema). Under
 *       {@code NativeStructuredOutputMode.AUTO} the dispatch re-runs with the
 *       apply flag cleared; the second attempt emits
 *       {@code test.native.attempt=false} and the same valid JSON, so
 *       {@code entity-complete} still arrives — proving the graceful fall-back
 *       recovered without failing the request.</li>
 * </ul>
 */
public class StructuredOutputTestHandler implements AtmosphereHandler {

    /** Valid JSON for {@link FilmReview} — what a conforming model would return. */
    private static final String FILM_REVIEW_JSON =
            "{\"title\": \"Inception\", \"rating\": 9, \"summary\": \"A mind-bending thriller\"}";

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();
        var reader = resource.getRequest().getReader();
        var prompt = reader.readLine();
        if (prompt != null && !prompt.trim().isEmpty()) {
            var trimmed = prompt.trim();
            Thread.ofVirtual().name("structured-output-test").start(() ->
                    handlePrompt(trimmed, resource));
        }
    }

    private void handlePrompt(String prompt, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);
        var pipeline = new AiPipeline(new NativeStructuredRuntime(prompt),
                "Return JSON only.", "test-model",
                null, null, List.of(), List.of(), AiMetrics.NOOP, FilmReview.class);
        pipeline.execute("client-1", prompt, session);
    }

    /**
     * Deterministic runtime that advertises {@code NATIVE_STRUCTURED_OUTPUT} and
     * branches on whether the pipeline asked it to apply the native schema.
     */
    private static final class NativeStructuredRuntime implements AgentRuntime {
        private final String prompt;

        NativeStructuredRuntime(String prompt) {
            this.prompt = prompt;
        }

        @Override public String name() { return "structured-native-test-runtime"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }

        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING, AiCapability.STRUCTURED_OUTPUT,
                    AiCapability.NATIVE_STRUCTURED_OUTPUT, AiCapability.SYSTEM_PROMPT);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            var nativeApplied = NativeStructuredOutput.shouldApply(context);
            // "reject" + native attempt: refuse the schema BEFORE emitting anything
            // (a pre-stream HTTP-400-shaped rejection). The graceful-fall-back guard
            // only re-dispatches when nothing has been forwarded yet, so we must not
            // emit a breadcrumb first.
            if ("reject".equals(prompt) && nativeApplied) {
                session.error(new RuntimeException(
                        "API returned 400: Invalid schema for response_format 'structured_output'"));
                return;
            }
            // Happy path (native applied) OR the fall-back re-dispatch (native
            // cleared): emit a breadcrumb recording which attempt produced the
            // entity, then return schema-conforming JSON.
            session.sendMetadata("test.native.attempt", nativeApplied);
            session.send(FILM_REVIEW_JSON);
            session.complete();
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException { /* no-op */ }

    @Override
    public void destroy() { /* no-op */ }
}
