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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link TracingCapturingSession}.
 */
public class TracingCapturingSessionTest {

    private StreamingSession delegate;
    private AiMetrics metrics;

    @BeforeEach
    public void setUp() {
        delegate = mock(StreamingSession.class);
        metrics = mock(AiMetrics.class);
    }

    @Test
    public void testSessionStartedCalledOnConstruction() {
        new TracingCapturingSession(delegate, metrics, "gpt-4");
        verify(metrics).sessionStarted("gpt-4");
    }

    @Test
    public void testSendDelegatesToWrappedSession() {
        var session = new TracingCapturingSession(delegate, metrics, "gpt-4");

        session.send("Hello");
        session.send(" world");

        verify(delegate).send("Hello");
        verify(delegate).send(" world");
    }

    @Test
    public void testSendTracksStreamingTextCount() {
        var session = new TracingCapturingSession(delegate, metrics, "gpt-4");

        session.send("a");
        session.send("b");
        session.send("c");

        assertEquals(3, session.streamingTextCount());
    }

    @Test
    public void testFirstSendCapturesTimestamp() {
        var session = new TracingCapturingSession(delegate, metrics, "gpt-4");
        assertNull(session.firstStreamingTextTime());

        session.send("first");
        var firstTime = session.firstStreamingTextTime();
        assertNotNull(firstTime);

        session.send("second");
        // First streaming text time should not change
        assertEquals(firstTime, session.firstStreamingTextTime());
    }

    @Test
    public void testCompleteReportsMetricsAndDelegates() {
        var session = new TracingCapturingSession(delegate, metrics, "gpt-4");

        session.send("chunk");
        session.complete();

        verify(metrics).recordLatency(eq("gpt-4"), any(Duration.class), any(Duration.class));
        verify(metrics).recordStreamingTextUsage("gpt-4", 0, 1);
        verify(metrics).sessionEnded("gpt-4");
        verify(delegate).complete();
    }

    @Test
    public void testCompleteWithSummaryReportsMetricsAndDelegates() {
        var session = new TracingCapturingSession(delegate, metrics, "gpt-4");

        session.send("part1");
        session.send("part2");
        session.complete("Full response");

        verify(metrics).recordLatency(eq("gpt-4"), any(Duration.class), any(Duration.class));
        verify(metrics).recordStreamingTextUsage("gpt-4", 0, 2);
        verify(metrics).sessionEnded("gpt-4");
        verify(delegate).complete("Full response");
    }

    @Test
    public void testCompleteWithNoSendsSkipsStreamingTextUsage() {
        var session = new TracingCapturingSession(delegate, metrics, "gpt-4");

        session.complete();

        verify(metrics).recordLatency(eq("gpt-4"), any(Duration.class), any(Duration.class));
        verify(metrics, never()).recordStreamingTextUsage(anyString(), anyInt(), anyInt());
        verify(metrics).sessionEnded("gpt-4");
        verify(delegate).complete();
    }

    @Test
    public void testErrorReportsErrorAndSessionEnd() {
        var session = new TracingCapturingSession(delegate, metrics, "gpt-4");
        var error = new RuntimeException("timeout occurred");

        session.error(error);

        verify(metrics).recordError("gpt-4", "timeout");
        verify(metrics).sessionEnded("gpt-4");
        verify(delegate).error(error);
    }

    @Test
    public void testErrorClassifiesRateLimit() {
        var session = new TracingCapturingSession(delegate, metrics, "gpt-4");
        var error = new RuntimeException("HTTP 429 rate limit exceeded");

        session.error(error);

        verify(metrics).recordError("gpt-4", "rate_limit");
    }

    @Test
    public void testErrorClassifiesServerError() {
        var session = new TracingCapturingSession(delegate, metrics, "gpt-4");
        var error = new RuntimeException("HTTP 503 service unavailable");

        session.error(error);

        verify(metrics).recordError("gpt-4", "server_error");
    }

    @Test
    public void testErrorClassifiesUnknown() {
        var session = new TracingCapturingSession(delegate, metrics, "gpt-4");
        var error = new RuntimeException("something weird happened");

        session.error(error);

        verify(metrics).recordError("gpt-4", "unknown");
    }

    @Test
    public void testErrorClassifiesNullMessage() {
        var session = new TracingCapturingSession(delegate, metrics, "gpt-4");
        var error = new RuntimeException((String) null);

        session.error(error);

        verify(metrics).recordError("gpt-4", "unknown");
    }

    @Test
    public void testDelegateMethodsPassThrough() {
        var session = new TracingCapturingSession(delegate, metrics, "gpt-4");

        when(delegate.sessionId()).thenReturn("session-42");
        when(delegate.isClosed()).thenReturn(false);

        assertEquals("session-42", session.sessionId());
        assertFalse(session.isClosed());

        session.sendMetadata("key", "value");
        verify(delegate).sendMetadata("key", "value");

        session.progress("thinking");
        verify(delegate).progress("thinking");

        session.stream("hello");
        verify(delegate).stream("hello");
    }

    @Test
    public void testSendContentDelegates() {
        var session = new TracingCapturingSession(delegate, metrics, "gpt-4");
        var content = new Content.Text("hello");

        session.sendContent(content);

        verify(delegate).sendContent(content);
    }

    @Test
    public void testNullModelDefaultsToUnknown() {
        var session = new TracingCapturingSession(delegate, metrics, null);

        session.complete();

        verify(metrics).sessionStarted("unknown");
        verify(metrics).recordLatency(eq("unknown"), any(Duration.class), any(Duration.class));
        verify(metrics).sessionEnded("unknown");
    }

    @Test
    public void testStartTimeIsRecorded() {
        var session = new TracingCapturingSession(delegate, metrics, "gpt-4");
        assertNotNull(session.startTime());
    }

    @Test
    public void testTimingCaptureIntegration() throws InterruptedException {
        var session = new TracingCapturingSession(delegate, metrics, "gpt-4");

        // Small delay to ensure measurable timing
        Thread.sleep(10);
        session.send("chunk");
        Thread.sleep(10);
        session.complete();

        // Verify that latency was recorded with positive durations
        verify(metrics).recordLatency(
                eq("gpt-4"),
                argThat(ttft -> !ttft.isNegative() && !ttft.isZero()),
                argThat(total -> !total.isNegative() && !total.isZero())
        );
    }
}
