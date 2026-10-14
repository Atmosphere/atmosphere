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
package org.atmosphere.ai.resume;

import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * A client that reconnects with {@code X-Atmosphere-Run-Id} while the
 * {@code @Prompt} is still running must receive the whole run: the buffered
 * events, then everything the prompt emits afterwards, through the terminal
 * frame. The prompt keeps writing into the session of the connection that went
 * away, so before the reattached resource was subscribed it got the backlog and
 * then nothing — and once the run finished the registry forgot it, so a later
 * reconnect got nothing at all.
 */
class RunReattachLiveForwardTest {

    @Test
    void aReattachMidRunReceivesTheBacklogThenTheRestOfTheRunInOrder() throws Exception {
        var registry = new RunRegistry();
        var execution = new ExecutionHandle.Settable(() -> { });
        var handle = registry.register("harness", "anonymous", "sess-1", execution);
        var buffer = handle.replayBuffer();
        buffer.capture("streaming-text", "event-0");
        buffer.capture("streaming-text", "event-1");

        var out = new StringWriter();
        var resource = resource(handle.runId(), AtmosphereResource.TRANSPORT.WEBSOCKET, out);
        assertEquals(2, RunReattachSupport.replayPendingRun(resource, registry));

        // The still-running @Prompt emits the rest from its own thread.
        var prompt = Thread.ofVirtual().start(() -> {
            for (int i = 2; i < 6; i++) {
                buffer.capture("streaming-text", "event-" + i);
            }
            buffer.capture("complete", "done");
        });
        prompt.join();
        execution.complete();

        var frames = frames(out);
        assertEquals(7, frames.size(), "event-0..event-5 and complete: " + frames);
        for (int i = 0; i < 6; i++) {
            assertTrue(frames.get(i).contains("\"data\":\"event-" + i + "\""), frames.get(i));
            assertTrue(frames.get(i).contains("\"seq\":" + (i + 1)), frames.get(i));
        }
        assertTrue(frames.get(6).contains("\"type\":\"complete\""), frames.get(6));
        assertEquals(0, buffer.subscriberCount(), "the terminal frame ends the subscription");
        Mockito.verify(resource).removeEventListener(Mockito.any());
    }

    @Test
    void aReconnectAfterTheRunFinishedStillReplaysItsTail() {
        var registry = new RunRegistry();
        var execution = new ExecutionHandle.Settable(() -> { });
        var handle = registry.register("harness", "anonymous", "sess-1", execution);
        handle.replayBuffer().capture("streaming-text", "event-0");
        handle.replayBuffer().capture("streaming-text", "event-1");
        handle.replayBuffer().capture("complete", "done");
        execution.complete();
        assertTrue(registry.lookup(handle.runId()).isEmpty(), "the run is no longer live");

        var out = new StringWriter();
        var resource = resource(handle.runId(), AtmosphereResource.TRANSPORT.WEBSOCKET, out);

        assertEquals(3, RunReattachSupport.replayPendingRun(resource, registry),
                "a finished run's events and its complete frame stay replayable for the TTL");
        assertTrue(frames(out).get(2).contains("\"type\":\"complete\""), frames(out).toString());
    }

    @Test
    void disconnectingTheReattachedResourceStopsForwarding() {
        var registry = new RunRegistry();
        var handle = registry.register("harness", "anonymous", "sess-1",
                new ExecutionHandle.Settable(() -> { }));
        var buffer = handle.replayBuffer();
        buffer.capture("streaming-text", "event-0");

        var out = new StringWriter();
        var resource = resource(handle.runId(), AtmosphereResource.TRANSPORT.WEBSOCKET, out);
        RunReattachSupport.replayPendingRun(resource, registry);
        var listener = ArgumentCaptor.forClass(AtmosphereResourceEventListener.class);
        Mockito.verify(resource).addEventListener(listener.capture());
        assertEquals(1, buffer.subscriberCount());

        listener.getValue().onDisconnect(Mockito.mock(AtmosphereResourceEvent.class));
        buffer.capture("streaming-text", "event-1");

        assertEquals(0, buffer.subscriberCount(), "the subscription is released on disconnect");
        assertEquals(1, frames(out).size(), "nothing is written after the disconnect: " + frames(out));
        Mockito.verify(resource).removeEventListener(listener.getValue());
    }

    @Test
    void longPollingKeepsTheSingleWriteDrain() {
        var registry = new RunRegistry();
        var handle = registry.register("harness", "anonymous", "sess-1",
                new ExecutionHandle.Settable(() -> { }));
        handle.replayBuffer().capture("streaming-text", "event-0");

        var out = new StringWriter();
        var resource = resource(handle.runId(), AtmosphereResource.TRANSPORT.LONG_POLLING, out);
        assertEquals(1, RunReattachSupport.replayPendingRun(resource, registry));
        assertEquals(0, handle.replayBuffer().subscriberCount(),
                "a resumed poll has no open response to forward to");
    }

    @Test
    void retainedFinishedRunsAreBoundedAndExpire() {
        var now = new AtomicReference<>(Instant.parse("2026-10-14T10:00:00Z"));
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var registry = new RunRegistry(clock, Duration.ofMinutes(30));
        var ids = new ArrayList<String>();
        for (int i = 0; i <= RunRegistry.MAX_RETAINED_COMPLETED; i++) {
            var execution = new ExecutionHandle.Settable(() -> { });
            ids.add(registry.register("a", "anonymous", "s" + i, execution).runId());
            execution.complete();
        }
        assertEquals(RunRegistry.MAX_RETAINED_COMPLETED, registry.retainedCompletedCount());
        assertTrue(registry.lookupReplayable(ids.getFirst()).isEmpty(), "the oldest is evicted");
        assertTrue(registry.lookupReplayable(ids.getLast()).isPresent());

        now.set(now.get().plus(Duration.ofMinutes(31)));
        assertTrue(registry.lookupReplayable(ids.getLast()).isEmpty(), "past the TTL it expires");
    }

    private static List<String> frames(StringWriter out) {
        var frames = new ArrayList<String>();
        for (var line : out.toString().split("\n")) {
            if (!line.isBlank()) {
                frames.add(line);
            }
        }
        return frames;
    }

    private static AtmosphereResource resource(String runId, AtmosphereResource.TRANSPORT transport,
                                               StringWriter out) {
        var resource = Mockito.mock(AtmosphereResource.class);
        var request = Mockito.mock(AtmosphereRequest.class);
        var response = Mockito.mock(AtmosphereResponse.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute(RunReattachSupport.RUN_ID_ATTRIBUTE)).thenReturn(runId);
        when(resource.uuid()).thenReturn("reattached");
        when(resource.transport()).thenReturn(transport);
        when(resource.getResponse()).thenReturn(response);
        try {
            when(response.getWriter()).thenReturn(new PrintWriter(out));
        } catch (java.io.IOException e) {
            throw new AssertionError("mock cannot throw", e);
        }
        return resource;
    }
}
