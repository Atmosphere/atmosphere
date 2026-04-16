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
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentResumeHandleTest {

    @Test
    void replayBufferCapturesAndReplaysInOrder() {
        var buffer = new RunEventReplayBuffer(16);
        var e0 = buffer.capture("token", "hello");
        var e1 = buffer.capture("token", " world");
        var e2 = buffer.capture("complete", "");

        assertEquals(0, e0.sequence());
        assertEquals(1, e1.sequence());
        assertEquals(2, e2.sequence());

        var all = buffer.snapshot();
        assertEquals(3, all.size());
        assertEquals("token", all.get(0).type());
        assertEquals("hello", all.get(0).payload());
    }

    @Test
    void replayBufferEvictsOldestWhenFull() {
        var buffer = new RunEventReplayBuffer(3);
        buffer.capture("t", "0");
        buffer.capture("t", "1");
        buffer.capture("t", "2");
        buffer.capture("t", "3");

        var all = buffer.snapshot();
        assertEquals(3, all.size(), "capacity enforced");
        assertEquals(1L, all.get(0).sequence(), "oldest was evicted");
        assertEquals(3L, all.get(2).sequence());
    }

    @Test
    void replayFromSkipsAlreadySeenEvents() {
        var buffer = new RunEventReplayBuffer();
        buffer.capture("t", "a");
        buffer.capture("t", "b");
        buffer.capture("t", "c");

        var from1 = buffer.replayFrom(1);
        assertEquals(2, from1.size());
        assertEquals(1L, from1.get(0).sequence());
        assertEquals(2L, from1.get(1).sequence());

        assertTrue(buffer.replayFrom(99).isEmpty());
    }

    @Test
    void replayBufferRejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new RunEventReplayBuffer(0));
        assertThrows(IllegalArgumentException.class, () -> new RunEventReplayBuffer(-1));
    }

    @Test
    void registryRegistersAndLooksUp() {
        var registry = new RunRegistry();
        var handle = new StubExecutionHandle();
        var resume = registry.register("pierre", "alice", "sess-1", handle);

        assertEquals(1, registry.size());
        assertSame(resume, registry.lookup(resume.runId()).orElseThrow());
        assertEquals("pierre", resume.agentId());
        assertEquals("alice", resume.userId());
        assertEquals("sess-1", resume.sessionId());
    }

    @Test
    void registryRemovesOnCompletion() {
        var registry = new RunRegistry();
        var handle = new StubExecutionHandle();
        var resume = registry.register("pierre", "alice", "sess-1", handle);

        assertEquals(1, registry.size());
        handle.complete();
        // whenComplete callback drives cleanup; hit lookup to verify.
        assertTrue(registry.lookup(resume.runId()).isEmpty());
    }

    @Test
    void lookupReturnsEmptyForCompletedRun() {
        var registry = new RunRegistry();
        var handle = new StubExecutionHandle();
        var resume = registry.register("pierre", "alice", "sess-1", handle);
        handle.complete();

        assertTrue(registry.lookup(resume.runId()).isEmpty());
    }

    @Test
    void sweepExpiredRemovesStaleRuns() {
        var tick = new AtomicLong(Instant.parse("2026-04-15T00:00:00Z").toEpochMilli());
        Clock advancing = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(tick.get()); }
            @Override public long millis() { return tick.get(); }
        };
        var registry = new RunRegistry(advancing, Duration.ofMinutes(5));
        registry.register("pierre", "alice", "sess-1", new StubExecutionHandle());

        assertEquals(0, registry.sweepExpired(),
                "fresh run is within TTL");

        tick.addAndGet(Duration.ofMinutes(10).toMillis());
        assertEquals(1, registry.sweepExpired(),
                "stale run is swept after TTL");
        assertEquals(0, registry.size());
    }

    @Test
    void registryRejectsInvalidTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> new RunRegistry(Clock.systemUTC(), Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new RunRegistry(Clock.systemUTC(), Duration.ofSeconds(-1)));
    }

    @Test
    void handleSurfacesReplayableEvents() {
        var registry = new RunRegistry();
        var buffer = new RunEventReplayBuffer();
        buffer.capture("token", "mid-flight");
        var resume = registry.register("pierre", "alice", "sess-1",
                new StubExecutionHandle(), buffer, "run-custom-id");

        assertEquals("run-custom-id", resume.runId());
        assertEquals(1, resume.replayableEvents().size());
        assertEquals("mid-flight", resume.replayableEvents().get(0).payload());
    }

    @Test
    void handleRejectsBlankFields() {
        var exec = new StubExecutionHandle();
        var buffer = new RunEventReplayBuffer();
        assertThrows(NullPointerException.class,
                () -> new AgentResumeHandle(null, "a", "u", "s", exec, buffer, Instant.now()));
        assertThrows(NullPointerException.class,
                () -> new AgentResumeHandle("r", "a", "u", "s", null, buffer, Instant.now()));
    }

    @Test
    void runEventRejectsBlankType() {
        assertThrows(IllegalArgumentException.class,
                () -> new RunEvent(0, "", "x", Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new RunEvent(0, null, "x", Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new RunEvent(0, "t", "x", null));
    }

    @Test
    void clearEmptiesRegistry() {
        var registry = new RunRegistry();
        registry.register("pierre", "alice", "sess-1", new StubExecutionHandle());
        registry.register("pierre", "alice", "sess-2", new StubExecutionHandle());
        assertEquals(2, registry.size());
        registry.clear();
        assertEquals(0, registry.size());
    }

    // --- helpers -----------------------------------------------------------

    private static final class StubExecutionHandle implements ExecutionHandle {
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void cancel() {
            cancelled.set(true);
            done.complete(null);
        }

        @Override
        public boolean isDone() {
            return done.isDone();
        }

        @Override
        public CompletableFuture<Void> whenDone() {
            return done;
        }

        void complete() {
            done.complete(null);
        }

        boolean wasCancelled() {
            return cancelled.get();
        }
    }

    @Test
    void cancelPropagatesToExecutionHandle() {
        var handle = new StubExecutionHandle();
        var registry = new RunRegistry();
        var resume = registry.register("pierre", "alice", "sess-1", handle);

        resume.executionHandle().cancel();

        assertTrue(handle.wasCancelled());
        assertFalse(resume.isDone() == false, "handle reported done after cancel");
    }
}
