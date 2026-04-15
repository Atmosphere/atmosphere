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
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricsCapturingSessionTest {

    private StreamingSession mockDelegate() {
        return Mockito.mock(StreamingSession.class);
    }

    private AiMetrics mockMetrics() {
        return Mockito.mock(AiMetrics.class);
    }

    @Test
    void sessionIdDelegatesToWrapped() {
        var delegate = mockDelegate();
        when(delegate.sessionId()).thenReturn("sess-1");
        var session = new MetricsCapturingSession(delegate, mockMetrics(), "gpt-4");
        assert session.sessionId().equals("sess-1");
    }

    @Test
    void sendForwardsToDelegateAndTracksFirstToken() {
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, mockMetrics(), "gpt-4");
        session.send("hello");
        verify(delegate).send("hello");
    }

    @Test
    void completeRecordsLatencyMetrics() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        session.send("text");
        session.complete();
        verify(metrics).recordLatency(eq("gpt-4"), any(), any());
        verify(delegate).complete();
    }

    @Test
    void completeWithSummaryRecordsLatencyMetrics() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "claude");
        session.send("data");
        session.complete("done");
        verify(metrics).recordLatency(eq("claude"), any(), any());
        verify(delegate).complete("done");
    }

    @Test
    void errorRecordsErrorMetrics() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        var ex = new RuntimeException("timeout error");
        session.error(ex);
        verify(metrics).recordError("gpt-4", "timeout");
        verify(delegate).error(ex);
    }

    @Test
    void errorClassifiesRateLimit() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        session.error(new RuntimeException("429 rate limit exceeded"));
        verify(metrics).recordError("gpt-4", "rate_limit");
    }

    @Test
    void errorClassifiesServerError() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        session.error(new RuntimeException("HTTP 503 service unavailable"));
        verify(metrics).recordError("gpt-4", "server_error");
    }

    @Test
    void errorClassifiesUnknown() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        session.error(new RuntimeException("something weird"));
        verify(metrics).recordError("gpt-4", "unknown");
    }

    @Test
    void errorWithNullMessageClassifiesUnknown() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        session.error(new RuntimeException());
        verify(metrics).recordError("gpt-4", "unknown");
    }

    @Test
    void nullModelUsesUnknown() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, null);
        session.send("data");
        session.complete();
        verify(metrics).recordLatency(eq("unknown"), any(), any());
    }

    @Test
    void sendMetadataRecordsTokenUsage() {
        var metrics = mockMetrics();
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, metrics, "gpt-4");
        session.sendMetadata("usage.promptStreamingTexts", 100);
        session.sendMetadata("usage.completionStreamingTexts", 50);
        session.complete();
        verify(metrics).recordStreamingTextUsage("gpt-4", 100, 50);
    }

    @Test
    void progressForwardsToDelegateDirectly() {
        var delegate = mockDelegate();
        var session = new MetricsCapturingSession(delegate, mockMetrics(), "gpt-4");
        session.progress("loading...");
        verify(delegate).progress("loading...");
    }

    @Test
    void isClosedDelegatesToWrapped() {
        var delegate = mockDelegate();
        when(delegate.isClosed()).thenReturn(false);
        var session = new MetricsCapturingSession(delegate, mockMetrics(), "gpt-4");
        assertFalse(session.isClosed());
    }
}
