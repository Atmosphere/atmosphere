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
package org.atmosphere.ai.jfr;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the JFR observability layer. Each test starts a JFR
 * {@link Recording}, drives the pipeline (or {@link JfrAiMetrics} directly),
 * stops the recording, dumps to disk, and reads the events back with
 * {@link RecordingFile} so assertions run on the same parser users get in
 * JDK Mission Control.
 */
class JfrAiMetricsTest {

    @Test
    void pipelineExecuteEmitsAgentTurnEvent() throws Exception {
        var events = recordAndCollect("org.atmosphere.ai.AgentTurn", () -> {
            var pipeline = new AiPipeline(new EchoRuntime(), "system", "test-model",
                    null, null, List.of(), List.of(), null);
            pipeline.execute("client-x", "hello", new CollectingSession("turn-1"));
        });

        assertFalse(events.isEmpty(), "Expected at least one AgentTurn event");
        var turn = events.get(0);
        assertEquals("echo", turn.getValue("runtime"));
        assertEquals("test-model", turn.getValue("model"));
        assertEquals("client-x", turn.getValue("clientId"));
        assertEquals("success", turn.getValue("status"));
        assertFalse((boolean) turn.getValue("cacheHit"));
    }

    @Test
    void jfrAiMetricsEmitsToolInvocationEvent() throws Exception {
        var events = recordAndCollect("org.atmosphere.ai.ToolInvocation", () -> {
            var metrics = new JfrAiMetrics();
            metrics.recordToolCall("test-model", "calculator", Duration.ofMillis(12), true);
            metrics.recordToolCall("test-model", "broken", Duration.ofMillis(3), false);
        });

        assertEquals(2, events.size());
        var ok = events.get(0);
        assertEquals("calculator", ok.getValue("tool"));
        assertEquals(ToolInvocationEvent.OUTCOME_SUCCESS, ok.getValue("outcome"));
        assertEquals(Duration.ofMillis(12).toNanos(), ((Number) ok.getValue("durationNanos")).longValue());

        var failed = events.get(1);
        assertEquals("broken", failed.getValue("tool"));
        assertEquals(ToolInvocationEvent.OUTCOME_FAILURE, failed.getValue("outcome"));
    }

    @Test
    void jfrAiMetricsEmitsAiCallEvent() throws Exception {
        var events = recordAndCollect("org.atmosphere.ai.Call", () -> {
            new JfrAiMetrics().recordLatency("test-model",
                    Duration.ofMillis(50), Duration.ofMillis(200));
        });

        assertEquals(1, events.size());
        var call = events.get(0);
        assertEquals("test-model", call.getValue("model"));
        assertEquals(Duration.ofMillis(50).toNanos(),
                ((Number) call.getValue("timeToFirstTokenNanos")).longValue());
        assertEquals(Duration.ofMillis(200).toNanos(),
                ((Number) call.getValue("totalDurationNanos")).longValue());
    }

    @Test
    void jfrAiMetricsEmitsSessionLifecyclePair() throws Exception {
        var events = recordAndCollect("org.atmosphere.ai.SessionLifecycle", () -> {
            var metrics = new JfrAiMetrics();
            metrics.sessionStarted("test-model");
            metrics.sessionEnded("test-model");
        });

        assertEquals(2, events.size());
        assertEquals(SessionLifecycleEvent.TRANSITION_STARTED, events.get(0).getValue("transition"));
        assertEquals(SessionLifecycleEvent.TRANSITION_ENDED, events.get(1).getValue("transition"));
    }

    @Test
    void jfrAiMetricsEmitsErrorEvent() throws Exception {
        var events = recordAndCollect("org.atmosphere.ai.Error", () -> {
            new JfrAiMetrics().recordError("test-model", "rate_limit");
        });

        assertEquals(1, events.size());
        assertEquals("rate_limit", events.get(0).getValue("errorType"));
    }

    @Test
    void errorPathMarksTurnEventAsError() throws Exception {
        var runtime = new EchoRuntime() {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                throw new IllegalStateException("boom");
            }
        };
        var pipeline = new AiPipeline(runtime, "system", "test-model",
                null, null, List.of(), List.of(), null);

        var events = recordAndCollect("org.atmosphere.ai.AgentTurn", () -> {
            try {
                pipeline.execute("client-err", "hello", new CollectingSession("turn-err"));
            } catch (RuntimeException expected) {
                // pipeline rethrows from the runtime call
            }
        });

        assertFalse(events.isEmpty());
        var turn = events.get(0);
        assertEquals("error", turn.getValue("status"));
        assertEquals("IllegalStateException", turn.getValue("errorType"));
    }

    @Test
    void withJfrIsIdempotent() {
        var user = AiMetrics.NOOP;
        var first = CompositeAiMetrics.withJfr(user);
        var second = CompositeAiMetrics.withJfr(first);
        assertSame(first, second, "withJfr must not double-wrap an existing composite");
    }

    @Test
    void compositeSkipsNoopAndCarriesJfr() {
        var user = new RecordingAiMetrics();
        var composite = (CompositeAiMetrics) CompositeAiMetrics.withJfr(user);
        assertEquals(2, composite.delegates().size());
        assertTrue(composite.delegates().get(0) instanceof RecordingAiMetrics);
        assertTrue(composite.delegates().get(1) instanceof JfrAiMetrics);
        composite.recordToolCall("m", "calc", Duration.ofMillis(1), true);
        assertEquals(1, user.tools);
    }

    private static List<jdk.jfr.consumer.RecordedEvent> recordAndCollect(String eventName,
                                                                        Runnable body) throws Exception {
        try (var recording = new Recording()) {
            recording.enable(eventName);
            recording.start();
            body.run();
            recording.stop();
            var dump = Files.createTempFile("atmosphere-jfr-test-", ".jfr");
            try {
                recording.dump(dump);
                var events = new ArrayList<jdk.jfr.consumer.RecordedEvent>();
                try (var file = new RecordingFile(dump)) {
                    while (file.hasMoreEvents()) {
                        var event = file.readEvent();
                        if (event.getEventType().getName().equals(eventName)) {
                            events.add(event);
                        }
                    }
                }
                return events;
            } finally {
                Files.deleteIfExists(dump);
            }
        }
    }

    /** AgentRuntime that drains the session with one chunk and completes. */
    private static class EchoRuntime implements AgentRuntime {
        @Override public String name() { return "echo"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() { return Set.of(AiCapability.TEXT_STREAMING); }
        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            assertNotNull(context);
            session.send("ok");
            session.complete();
        }
    }

    /** Captures tool-call counts to verify the composite forwards. */
    private static class RecordingAiMetrics implements AiMetrics {
        int tools;
        @Override public void recordStreamingTextUsage(String m, int p, int c) { }
        @Override public void recordLatency(String m, Duration f, Duration t) { }
        @Override public void recordCost(String m, java.math.BigDecimal c) { }
        @Override public void recordToolCall(String m, String t, Duration d, boolean s) { tools++; }
        @Override public void recordError(String m, String t) { }
    }
}
