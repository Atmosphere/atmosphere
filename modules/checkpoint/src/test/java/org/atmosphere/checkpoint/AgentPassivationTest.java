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
package org.atmosphere.checkpoint;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AgentSnapshot;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AgentPassivation} — verifies a paused agent's
 * conversation history survives a snapshot/load round-trip via
 * {@link InMemoryCheckpointStore}, and that resume reinjects runtime
 * references from the caller's base context while overriding persistent
 * fields with the snapshot's values.
 */
class AgentPassivationTest {

    @Test
    void passivateThenLoadSnapshotRoundTrips() {
        var store = new InMemoryCheckpointStore();
        var runtime = new RecordingRuntime();
        var ctx = baseContext("hello", List.of(
                new ChatMessage("user", "ping", null, null, List.of()),
                new ChatMessage("assistant", "pong", null, null, List.of())));

        var id = AgentPassivation.passivate(runtime, ctx, store, "awaiting human approval");

        var snapshot = AgentPassivation.loadSnapshot(store, id);
        assertNotNull(snapshot);
        assertEquals("hello", snapshot.pendingMessage());
        assertEquals("recording-stub", snapshot.runtimeName());
        assertEquals("awaiting human approval", snapshot.reason());
        assertEquals(2, snapshot.history().size());
        assertEquals("ping", snapshot.history().get(0).content());
    }

    @Test
    void resumeMergesSnapshotIntoBaseAndCallsRuntime() {
        var store = new InMemoryCheckpointStore();
        var runtime = new RecordingRuntime();
        var paused = baseContext("draft contract for legal", List.of(
                new ChatMessage("user", "earlier turn", null, null, List.of())));
        var id = AgentPassivation.passivate(runtime, paused, store, "needs legal review");

        // Two days later: caller builds a fresh base context with the runtime
        // references that need to be reinjected (tools, memory, listeners),
        // then resumes. We use empty references here — the assertion is on
        // the flow, not on the references themselves.
        var freshBase = baseContext("", List.of());
        var session = new NoopSession();

        AgentPassivation.resume(runtime, store, id, "approved by Alice", freshBase, session);

        var resumed = runtime.lastContext.get();
        assertNotNull(resumed);
        assertEquals("approved by Alice", resumed.message(),
                "external signal must replace pending message");
        assertEquals(1, resumed.history().size(),
                "snapshot history must override base history");
        assertEquals("earlier turn", resumed.history().get(0).content());
        assertEquals("session-1", resumed.sessionId(),
                "snapshot identity columns override base");
    }

    @Test
    void resumeWithoutSignalReplaysPendingMessage() {
        var store = new InMemoryCheckpointStore();
        var runtime = new RecordingRuntime();
        var paused = baseContext("retry the failed call", List.of());
        var id = AgentPassivation.passivate(runtime, paused, store, "transient outage");

        AgentPassivation.resume(runtime, store, id, null,
                baseContext("", List.of()), new NoopSession());

        assertEquals("retry the failed call",
                runtime.lastContext.get().message());
    }

    @Test
    void loadSnapshotFromMissingCheckpointThrows() {
        var store = new InMemoryCheckpointStore();
        assertThrows(IllegalStateException.class,
                () -> AgentPassivation.loadSnapshot(store, "nonexistent"));
    }

    @Test
    void resumeFromMissingCheckpointThrows() {
        var store = new InMemoryCheckpointStore();
        var runtime = new RecordingRuntime();
        assertThrows(IllegalStateException.class,
                () -> AgentPassivation.resume(runtime, store, "nonexistent",
                        "signal", baseContext("", List.of()), new NoopSession()));
    }

    @Test
    void agentSnapshotFiltersNonStringMetadata() {
        var ctx = new AgentExecutionContext(
                "msg", "sys", "model",
                "agent-1", "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(),
                Map.of(
                        "string-key", "string-value",
                        "int-key", 42,
                        "obj-key", new Object()),
                List.of(), null, null);
        var snapshot = AgentSnapshot.from("test", ctx, "test reason");

        // Only the String-valued entry survives the snapshot — non-string
        // values would not round-trip through the SQLite checkpoint store.
        assertEquals(1, snapshot.metadata().size());
        assertEquals("string-value", snapshot.metadata().get("string-key"));
    }

    @Test
    void resumeMetadataMergesBaseOverSnapshot() {
        var store = new InMemoryCheckpointStore();
        var runtime = new RecordingRuntime();
        var paused = new AgentExecutionContext(
                "msg", "sys", "model",
                null, "session-2", null, null,
                List.of(), null, null, List.of(),
                Map.of("trace.id", "trace-old", "shared.key", "from-snapshot"),
                List.of(), null, null);
        var id = AgentPassivation.passivate(runtime, paused, store, "reason");

        // Base context carries a fresh trace id — must win on key collision.
        var freshBase = new AgentExecutionContext(
                "msg", "sys", "model",
                null, "session-2", null, null,
                List.of(), null, null, List.of(),
                Map.of("trace.id", "trace-new"),
                List.of(), null, null);

        AgentPassivation.resume(runtime, store, id, "go",
                freshBase, new NoopSession());

        var merged = runtime.lastContext.get().metadata();
        assertEquals("trace-new", merged.get("trace.id"),
                "Base trace.id must win — caller-injected refs are not clobbered");
        assertEquals("from-snapshot", merged.get("shared.key"),
                "Keys only in snapshot must survive the merge");
    }

    @Test
    void passivateAssignsUniqueCheckpointIds() {
        var store = new InMemoryCheckpointStore();
        var runtime = new RecordingRuntime();
        var ctx = baseContext("msg", List.of());

        var id1 = AgentPassivation.passivate(runtime, ctx, store, "first");
        var id2 = AgentPassivation.passivate(runtime, ctx, store, "second");

        assertNotEquals(id1, id2);
    }

    @Test
    void runtimePassivationCapabilityIsHonest() {
        // A runtime that declares PASSIVATION must commit to history
        // threading; verify the stub's declared set includes the flag.
        var caps = new RecordingRuntime().capabilities();
        assertTrue(caps.contains(AiCapability.PASSIVATION));
    }

    @Test
    void nullArgsRejected() {
        var store = new InMemoryCheckpointStore();
        var runtime = new RecordingRuntime();
        var ctx = baseContext("msg", List.of());
        assertThrows(NullPointerException.class,
                () -> AgentPassivation.passivate(null, ctx, store, "r"));
        assertThrows(NullPointerException.class,
                () -> AgentPassivation.passivate(runtime, null, store, "r"));
        assertThrows(NullPointerException.class,
                () -> AgentPassivation.passivate(runtime, ctx, null, "r"));
        assertThrows(NullPointerException.class,
                () -> AgentPassivation.passivate(runtime, ctx, store, null));
    }

    private static AgentExecutionContext baseContext(String message, List<ChatMessage> history) {
        return new AgentExecutionContext(
                message, "system", "test-model",
                "agent-1", "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(),
                new HashMap<>(), history, null, null);
    }

    /** Records the last context the runtime was asked to execute. */
    private static class RecordingRuntime implements AgentRuntime {
        final AtomicReference<AgentExecutionContext> lastContext = new AtomicReference<>();

        @Override public String name() { return "recording-stub"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING, AiCapability.PASSIVATION);
        }
        @Override public void execute(AgentExecutionContext context, StreamingSession session) {
            lastContext.set(context);
            session.complete();
        }
    }

    private static class NoopSession implements StreamingSession {
        @Override public String sessionId() { return "test"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
    }
}
