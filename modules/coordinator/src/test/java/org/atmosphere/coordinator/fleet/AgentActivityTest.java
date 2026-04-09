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
package org.atmosphere.coordinator.fleet;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.coordinator.test.StubActivityListener;
import org.atmosphere.coordinator.transport.AgentTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentActivityTest {

    @Test
    void sealedInterfacePermitsAllVariants() {
        AgentActivity activity = new AgentActivity.Idle("test", Instant.now());
        var matched = switch (activity) {
            case AgentActivity.Idle ignored -> true;
            case AgentActivity.Thinking ignored -> false;
            case AgentActivity.Executing ignored -> false;
            case AgentActivity.WaitingForInput ignored -> false;
            case AgentActivity.Retrying ignored -> false;
            case AgentActivity.CircuitOpen ignored -> false;
            case AgentActivity.Completed ignored -> false;
            case AgentActivity.Failed ignored -> false;
            case AgentActivity.Evaluated ignored -> false;
        };
        assertTrue(matched);
    }

    @Test
    void allVariantsExposeAgentName() {
        var now = Instant.now();
        List<AgentActivity> activities = List.of(
                new AgentActivity.Idle("a", now),
                new AgentActivity.Thinking("a", "s", now),
                new AgentActivity.Executing("a", "s", "detail", now),
                new AgentActivity.WaitingForInput("a", "reason", now),
                new AgentActivity.Retrying("a", "s", 1, 3, now),
                new AgentActivity.CircuitOpen("a", "reason", now),
                new AgentActivity.Completed("a", "s", Duration.ZERO),
                new AgentActivity.Failed("a", "s", "err", Duration.ZERO),
                new AgentActivity.Evaluated("a", "quality", 0.9, true, "good")
        );
        for (var activity : activities) {
            assertEquals("a", activity.agentName(),
                    activity.getClass().getSimpleName() + " should expose agentName");
        }
    }

    @Test
    void proxyEmitsThinkingAndCompletedOnSuccess() {
        var transport = mock(AgentTransport.class);
        var expected = new AgentResult("weather", "search", "Sunny", Map.of(), Duration.ZERO, true);
        when(transport.send("weather", "search", Map.of())).thenReturn(expected);

        var listener = new StubActivityListener();
        var proxy = new DefaultAgentProxy("weather", "1.0.0", 1, true, 0,
                transport, List.of(listener));

        proxy.call("search", Map.of());

        listener.assertTransition("weather", "Thinking", "Completed");
    }

    @Test
    void proxyEmitsThinkingAndFailedOnFailure() {
        var transport = mock(AgentTransport.class);
        var failure = AgentResult.failure("weather", "search", "timeout", Duration.ZERO);
        when(transport.send("weather", "search", Map.of())).thenReturn(failure);

        var listener = new StubActivityListener();
        var proxy = new DefaultAgentProxy("weather", "1.0.0", 1, true, 0,
                transport, List.of(listener));

        proxy.call("search", Map.of());

        listener.assertTransition("weather", "Thinking", "Failed");
    }

    @Test
    void proxyEmitsRetryingDuringRetries() {
        var transport = mock(AgentTransport.class);
        var failure = AgentResult.failure("weather", "search", "timeout", Duration.ZERO);
        var success = new AgentResult("weather", "search", "Sunny", Map.of(), Duration.ZERO, true);
        when(transport.send("weather", "search", Map.of()))
                .thenReturn(failure)
                .thenReturn(success);

        var listener = new StubActivityListener();
        var proxy = new DefaultAgentProxy("weather", "1.0.0", 1, true, 1,
                transport, List.of(listener));

        var result = proxy.call("search", Map.of());

        assertTrue(result.success());
        // Thinking -> Retrying -> Thinking -> Completed
        listener.assertTransition("weather", "Thinking", "Retrying", "Thinking", "Completed");
    }

    @Test
    void proxyEmitsFailedAfterAllRetriesExhausted() {
        var transport = mock(AgentTransport.class);
        var failure = AgentResult.failure("weather", "search", "timeout", Duration.ZERO);
        when(transport.send("weather", "search", Map.of())).thenReturn(failure);

        var listener = new StubActivityListener();
        var proxy = new DefaultAgentProxy("weather", "1.0.0", 1, true, 2,
                transport, List.of(listener));

        var result = proxy.call("search", Map.of());

        assertTrue(!result.success());
        // Thinking -> Retrying -> Thinking -> Retrying -> Thinking -> Failed
        listener.assertTransition("weather",
                "Thinking", "Retrying", "Thinking", "Retrying", "Thinking", "Failed");
    }

    @Test
    void multipleListenersAllReceiveEvents() {
        var transport = mock(AgentTransport.class);
        var expected = new AgentResult("w", "s", "ok", Map.of(), Duration.ZERO, true);
        when(transport.send("w", "s", Map.of())).thenReturn(expected);

        var listener1 = new StubActivityListener();
        var listener2 = new StubActivityListener();
        var proxy = new DefaultAgentProxy("w", "1.0.0", 1, true, 0,
                transport, List.of(listener1, listener2));

        proxy.call("s", Map.of());

        listener1.assertTransition("w", "Thinking", "Completed");
        listener2.assertTransition("w", "Thinking", "Completed");
    }

    @Test
    void failingListenerDoesNotBlockExecution() {
        var transport = mock(AgentTransport.class);
        var expected = new AgentResult("w", "s", "ok", Map.of(), Duration.ZERO, true);
        when(transport.send("w", "s", Map.of())).thenReturn(expected);

        AgentActivityListener failing = activity -> { throw new RuntimeException("boom"); };
        var listener = new StubActivityListener();
        var proxy = new DefaultAgentProxy("w", "1.0.0", 1, true, 0,
                transport, List.of(failing, listener));

        var result = proxy.call("s", Map.of());

        assertTrue(result.success());
        listener.assertTransition("w", "Thinking", "Completed");
    }

    @Test
    void streamingActivityListenerMapsToAiEvents() {
        var emitted = new ArrayList<AiEvent>();
        StreamingSession session = new StreamingSession() {
            @Override public String sessionId() { return "test"; }
            @Override public void send(String text) { }
            @Override public void sendMetadata(String key, Object value) { }
            @Override public void progress(String message) { }
            @Override public void complete() { }
            @Override public void complete(String summary) { }
            @Override public void error(Throwable t) { }
            @Override public boolean isClosed() { return false; }
            @Override public void emit(AiEvent event) { emitted.add(event); }
        };

        var listener = new StreamingActivityListener(session);

        listener.onActivity(new AgentActivity.Thinking("weather", "search", Instant.now()));
        listener.onActivity(new AgentActivity.Completed("weather", "search", Duration.ofMillis(100)));

        assertEquals(2, emitted.size());
        assertInstanceOf(AiEvent.AgentStep.class, emitted.get(0));
        assertInstanceOf(AiEvent.AgentStep.class, emitted.get(1));

        var thinking = (AiEvent.AgentStep) emitted.get(0);
        assertEquals("thinking", thinking.stepName());
        assertEquals("agent-step", thinking.eventType());

        var completed = (AiEvent.AgentStep) emitted.get(1);
        assertEquals("completed", completed.stepName());
        assertTrue(completed.description().contains("100ms"));
    }

    @Test
    void streamingActivityListenerSkipsIdleEvents() {
        var emitted = new ArrayList<AiEvent>();
        StreamingSession session = new StreamingSession() {
            @Override public String sessionId() { return "test"; }
            @Override public void send(String text) { }
            @Override public void sendMetadata(String key, Object value) { }
            @Override public void progress(String message) { }
            @Override public void complete() { }
            @Override public void complete(String summary) { }
            @Override public void error(Throwable t) { }
            @Override public boolean isClosed() { return false; }
            @Override public void emit(AiEvent event) { emitted.add(event); }
        };

        var listener = new StreamingActivityListener(session);
        listener.onActivity(new AgentActivity.Idle("weather", Instant.now()));

        assertTrue(emitted.isEmpty());
    }

    @Test
    void streamingActivityListenerMapsRetrying() {
        var emitted = new ArrayList<AiEvent>();
        StreamingSession session = new StreamingSession() {
            @Override public String sessionId() { return "test"; }
            @Override public void send(String text) { }
            @Override public void sendMetadata(String key, Object value) { }
            @Override public void progress(String message) { }
            @Override public void complete() { }
            @Override public void complete(String summary) { }
            @Override public void error(Throwable t) { }
            @Override public boolean isClosed() { return false; }
            @Override public void emit(AiEvent event) { emitted.add(event); }
        };

        var listener = new StreamingActivityListener(session);
        listener.onActivity(new AgentActivity.Retrying(
                "weather", "search", 2, 3, Instant.now()));

        assertEquals(1, emitted.size());
        var step = (AiEvent.AgentStep) emitted.getFirst();
        assertEquals("retrying", step.stepName());
        assertTrue(step.description().contains("2/3"));
    }

    @Test
    void streamingActivityListenerMapsCircuitOpen() {
        var emitted = new ArrayList<AiEvent>();
        StreamingSession session = new StreamingSession() {
            @Override public String sessionId() { return "test"; }
            @Override public void send(String text) { }
            @Override public void sendMetadata(String key, Object value) { }
            @Override public void progress(String message) { }
            @Override public void complete() { }
            @Override public void complete(String summary) { }
            @Override public void error(Throwable t) { }
            @Override public boolean isClosed() { return false; }
            @Override public void emit(AiEvent event) { emitted.add(event); }
        };

        var listener = new StreamingActivityListener(session);
        listener.onActivity(new AgentActivity.CircuitOpen(
                "weather", "too many failures", Instant.now().plusSeconds(30)));

        assertEquals(1, emitted.size());
        var step = (AiEvent.AgentStep) emitted.getFirst();
        assertEquals("circuit-open", step.stepName());
        assertTrue(step.description().contains("too many failures"));
    }

    @Test
    void stubActivityListenerFiltersByAgent() {
        var listener = new StubActivityListener();
        listener.onActivity(new AgentActivity.Thinking("a", "s", Instant.now()));
        listener.onActivity(new AgentActivity.Thinking("b", "s", Instant.now()));
        listener.onActivity(new AgentActivity.Completed("a", "s", Duration.ZERO));

        assertEquals(2, listener.activitiesFor("a").size());
        assertEquals(1, listener.activitiesFor("b").size());
        assertEquals(3, listener.activities().size());
    }

    @Test
    void stubActivityListenerClearsEvents() {
        var listener = new StubActivityListener();
        listener.onActivity(new AgentActivity.Thinking("a", "s", Instant.now()));
        assertEquals(1, listener.activities().size());

        listener.clear();
        assertTrue(listener.activities().isEmpty());
    }
}
