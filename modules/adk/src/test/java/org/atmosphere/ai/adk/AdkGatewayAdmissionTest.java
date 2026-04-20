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
package org.atmosphere.ai.adk;

import com.google.adk.runner.Runner;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.gateway.AiGateway;
import org.atmosphere.ai.gateway.AiGatewayHolder;
import org.atmosphere.ai.gateway.PerUserRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Exec-level proof that {@link AdkAgentRuntime} admits through
 * {@link AiGateway} before dispatching to the ADK Runner. The ADK
 * client is mocked — the downstream reactive path will fail as soon
 * as the mock Runner is touched, but {@code admitThroughGateway} is
 * line 1 of {@code doExecuteWithHandle} so the admission entry is
 * captured regardless.
 */
class AdkGatewayAdmissionTest {

    private CountingExporter exporter;

    @BeforeEach
    void installCountingGateway() {
        exporter = new CountingExporter();
        AiGatewayHolder.install(new AiGateway(
                new PerUserRateLimiter(1_000_000, Duration.ofHours(1)),
                AiGateway.CredentialResolver.noop(),
                exporter));
    }

    @AfterEach
    void restoreDefault() {
        AiGatewayHolder.reset();
    }

    @Test
    void executeRecordsExactlyOneAdmissionWithRuntimeLabel() {
        var runtime = new TestableAdkRuntime(mock(Runner.class));
        var context = new AgentExecutionContext(
                "Hello", "You are helpful", "gemini-2.5-flash",
                null, "session-1", "alice", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);

        try {
            runtime.execute(context, new NoopSession());
        } catch (RuntimeException ignored) {
            // Mock Runner with no behaviour will blow up after admission —
            // that's fine, the only thing the test cares about is that admit
            // fires (and it's the first statement in doExecuteWithHandle).
        }

        assertEquals(1, exporter.entries.size(),
                "execute() must admit through the gateway exactly once — saw "
                + exporter.entries.size());
        var entry = exporter.entries.get(0);
        assertTrue(entry.accepted(), "test limiter accepts: " + entry.reason());
        assertEquals("google-adk", entry.provider(),
                "gateway trace must carry the runtime label");
        assertEquals("alice", entry.userId());
        assertEquals("gemini-2.5-flash", entry.model());
    }

    private static final class TestableAdkRuntime extends AdkAgentRuntime {
        TestableAdkRuntime(Runner runner) {
            setNativeClient(runner);
        }
    }

    private static final class CountingExporter implements AiGateway.GatewayTraceExporter {
        final List<AiGateway.GatewayTraceEntry> entries = new CopyOnWriteArrayList<>();

        @Override
        public void record(AiGateway.GatewayTraceEntry entry) {
            entries.add(entry);
        }
    }

    private static final class NoopSession implements StreamingSession {
        @Override public String sessionId() { return "admission-test"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
    }
}
