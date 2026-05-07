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
package org.atmosphere.ai;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the framework-level confidence elicitation path — verifies
 * the system prompt is augmented with the elicitation cue, the
 * {@link ConfidenceCapturingSession} parses model-emitted confidence
 * fields on stream completion, and the resulting {@link AiConfidence}
 * fires through {@link StreamingSession#confidence(AiConfidence)} ahead
 * of the terminal frame.
 */
class AiPipelineConfidenceTest {

    @Test
    void aiConfidenceRecordValidatesAggregateRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new AiConfidence(java.util.OptionalDouble.of(1.5),
                        List.of(), AiConfidence.Source.MODEL_REPORTED_FIELD));
        assertThrows(IllegalArgumentException.class,
                () -> new AiConfidence(java.util.OptionalDouble.of(-0.1),
                        List.of(), AiConfidence.Source.MODEL_REPORTED_FIELD));
    }

    @Test
    void tokenLogprobRejectsPositiveAndNaN() {
        assertThrows(IllegalArgumentException.class, () -> new TokenLogprob("a", 0.5));
        assertThrows(IllegalArgumentException.class, () -> new TokenLogprob("a", Double.NaN));
        // Zero is allowed (probability 1.0).
        assertEquals(1.0, new TokenLogprob("a", 0.0).linearProbability());
    }

    @Test
    void fromLogprobsComputesArithmeticMeanOfLinearProbabilities() {
        var conf = AiConfidence.fromLogprobs(List.of(
                new TokenLogprob("a", 0.0),       // exp=1.0
                new TokenLogprob("b", -0.6931472) // exp≈0.5
        ));
        assertTrue(conf.aggregate().isPresent());
        assertEquals(0.75, conf.aggregate().getAsDouble(), 1e-6);
        assertEquals(AiConfidence.Source.LOGPROBS_NATIVE, conf.source());
        assertEquals(2, conf.tokens().size());
    }

    @Test
    void fromLogprobsEmptyListReturnsUnknown() {
        var conf = AiConfidence.fromLogprobs(List.of());
        assertFalse(conf.aggregate().isPresent());
        assertEquals(AiConfidence.Source.LOGPROBS_NATIVE, conf.source());
    }

    @Test
    void elicitationDefaultsAreReasonable() {
        var e = AiConfidenceElicitation.defaults();
        assertEquals("confidence", e.fieldName());
        assertNotNull(e.systemPromptCue());
        assertTrue(e.effectiveCue().contains("confidence"));
        assertTrue(e.effectiveCue().contains("[0.0, 1.0]"));
    }

    @Test
    void elicitationCustomFieldRebuildsCue() {
        var e = AiConfidenceElicitation.withField("certainty");
        assertEquals("certainty", e.fieldName());
        assertTrue(e.effectiveCue().contains("\"certainty\""));
        assertFalse(e.effectiveCue().contains("\"confidence\""));
    }

    @Test
    void noElicitationMeansNoConfidenceEvent() {
        var session = new ConfidenceObservingSession("conf-off");
        var pipeline = newPipeline(plainResponse("answer is 42"));
        pipeline.execute("c1", "go", session);
        assertNull(session.lastConfidence,
                "Without elicitation the decorator must not fire confidence events");
    }

    @Test
    void elicitationParsesModelReportedField() {
        var session = new ConfidenceObservingSession("conf-on");
        var pipeline = newPipeline(plainResponse("answer is 42 {\"confidence\": 0.83}"));
        pipeline.setDefaultConfidenceElicitation(AiConfidenceElicitation.defaults());
        pipeline.execute("c1", "go", session);

        assertNotNull(session.lastConfidence);
        assertEquals(AiConfidence.Source.MODEL_REPORTED_FIELD, session.lastConfidence.source());
        assertTrue(session.lastConfidence.aggregate().isPresent());
        assertEquals(0.83, session.lastConfidence.aggregate().getAsDouble(), 1e-9);
    }

    @Test
    void modelNonComplianceEmitsUnknown() {
        var session = new ConfidenceObservingSession("conf-noncompliant");
        var pipeline = newPipeline(plainResponse("just an answer, no confidence field"));
        pipeline.setDefaultConfidenceElicitation(AiConfidenceElicitation.defaults());
        pipeline.execute("c1", "go", session);

        assertNotNull(session.lastConfidence);
        assertFalse(session.lastConfidence.aggregate().isPresent(),
                "Missing field must surface as unknown, not absent event");
        assertEquals(AiConfidence.Source.MODEL_REPORTED_FIELD, session.lastConfidence.source());
    }

    @Test
    void outOfRangeValueParsedAsUnknown() {
        var session = new ConfidenceObservingSession("conf-bad");
        var pipeline = newPipeline(plainResponse("hi {\"confidence\": 5.0}"));
        pipeline.setDefaultConfidenceElicitation(AiConfidenceElicitation.defaults());
        pipeline.execute("c1", "go", session);

        assertNotNull(session.lastConfidence);
        assertFalse(session.lastConfidence.aggregate().isPresent());
    }

    @Test
    void runtimeExplicitConfidenceWinsOverDecoratorParsing() {
        var nativeConfidence = AiConfidence.fromLogprobs(List.of(
                new TokenLogprob("yes", 0.0)));
        var session = new ConfidenceObservingSession("conf-native");
        var pipeline = newPipeline(seq -> {
            seq.send("answer {\"confidence\": 0.4}");
            seq.confidence(nativeConfidence);
            seq.complete();
        });
        pipeline.setDefaultConfidenceElicitation(AiConfidenceElicitation.defaults());
        pipeline.execute("c1", "go", session);

        // Native logprobs win — decorator does NOT also fire its parsed value.
        assertNotNull(session.lastConfidence);
        assertEquals(AiConfidence.Source.LOGPROBS_NATIVE, session.lastConfidence.source());
        assertEquals(1.0, session.lastConfidence.aggregate().getAsDouble(), 1e-9);
        assertEquals(1, session.confidenceCallCount,
                "Exactly one confidence event must reach the leaf session");
    }

    @Test
    void elicitationSkippedWhenStructuredOutputInPlay() {
        // When responseType is set, the structured-output parser owns the
        // entire response shape; appending a separate confidence field
        // would break the JSON parse. The decorator must not be installed.
        var captured = new AtomicReference<AgentExecutionContext>();
        var runtime = new StubRuntime() {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                captured.set(context);
                session.complete();
            }
        };
        var pipeline = new AiPipeline(runtime, "system", "test-model",
                null, null, List.of(), List.of(), AiMetrics.NOOP, SimpleRecord.class);
        pipeline.setDefaultConfidenceElicitation(AiConfidenceElicitation.defaults());

        var session = new ConfidenceObservingSession("conf-with-structured");
        pipeline.execute("c1", "go", session);

        // System prompt should NOT carry the confidence cue when structured
        // output is in play — only the schema instructions augment the prompt.
        var systemPrompt = captured.get().systemPrompt();
        assertFalse(systemPrompt.contains("confidence"),
                "Confidence cue must not appear in system prompt when structured output is in play");
    }

    @Test
    void perRequestElicitationOverridesPipelineDefault() {
        var session = new ConfidenceObservingSession("conf-per-req");
        var pipeline = newPipeline(plainResponse("hi {\"score\": 0.42}"));
        // Pipeline default uses "confidence", caller overrides to "score".
        pipeline.setDefaultConfidenceElicitation(AiConfidenceElicitation.defaults());
        var perRequest = AiConfidenceElicitation.withField("score");
        pipeline.execute("c1", "go", session,
                Map.of(AiConfidenceElicitation.METADATA_KEY, perRequest));

        assertNotNull(session.lastConfidence);
        assertTrue(session.lastConfidence.aggregate().isPresent());
        assertEquals(0.42, session.lastConfidence.aggregate().getAsDouble(), 1e-9);
    }

    @Test
    void streamingSessionDefaultUsageEmitsMetadataKeys() {
        var captured = new HashMap<String, Object>();
        var session = new StreamingSession() {
            @Override public String sessionId() { return "metadata-test"; }
            @Override public void send(String text) { }
            @Override public void sendMetadata(String key, Object value) { captured.put(key, value); }
            @Override public void progress(String message) { }
            @Override public void complete() { }
            @Override public void complete(String summary) { }
            @Override public void error(Throwable t) { }
            @Override public boolean isClosed() { return false; }
        };
        session.confidence(AiConfidence.reported(0.7));

        assertEquals(0.7, captured.get(AiConfidence.AGGREGATE_METADATA_KEY));
        assertEquals("MODEL_REPORTED_FIELD", captured.get(AiConfidence.SOURCE_METADATA_KEY));
    }

    private record SimpleRecord(String message) { }

    private static AiPipeline newPipeline(StreamingDriver driver) {
        var runtime = new StubRuntime() {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                driver.drive(session);
            }
        };
        return new AiPipeline(runtime, "system", "test-model",
                null, null, List.of(), List.of(), AiMetrics.NOOP);
    }

    private static StreamingDriver plainResponse(String text) {
        return session -> {
            session.send(text);
            session.complete();
        };
    }

    @FunctionalInterface
    private interface StreamingDriver {
        void drive(StreamingSession session);
    }

    private static class StubRuntime implements AgentRuntime {
        @Override public String name() { return "conf-stub"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING, AiCapability.CONFIDENCE_SCORES);
        }
        @Override public void execute(AgentExecutionContext context, StreamingSession session) {
            session.complete();
        }
    }

    /** Captures the last AiConfidence emitted by the pipeline plus the
     * accumulated text — enough to assert end-to-end behaviour without
     * pulling in CollectingSession (which is final). */
    private static class ConfidenceObservingSession implements StreamingSession {
        private final String id;
        private final StringBuilder text = new StringBuilder();
        AiConfidence lastConfidence;
        int confidenceCallCount;
        boolean completed;
        Throwable failure;

        ConfidenceObservingSession(String sessionId) {
            this.id = sessionId;
        }

        @Override public String sessionId() { return id; }
        @Override public void send(String t) { if (t != null) text.append(t); }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { completed = true; }
        @Override public void complete(String summary) { complete(); }
        @Override public void error(Throwable t) { failure = t; completed = true; }
        @Override public boolean isClosed() { return completed; }
        @Override public boolean hasErrored() { return failure != null; }
        @Override public void confidence(AiConfidence confidence) {
            lastConfidence = confidence;
            confidenceCallCount++;
        }
    }
}
