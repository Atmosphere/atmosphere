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

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the process-global streaming-session TTL sweep
 * (Correctness Invariant #3 — every cache needs an eviction policy).
 *
 * <p>The load-bearing assertions are the pair: an orphaned session IS reaped
 * and a live session is NOT. A sweeper that reaps everything would "pass" a
 * leak test while breaking every in-flight stream.</p>
 */
class StreamingSessionSweeperTest {

    private static final long TTL_MS = 60_000L;

    @AfterEach
    void clearProperty() {
        System.clearProperty(StreamingSessionSweeper.TTL_PROPERTY);
    }

    private static AtmosphereResource resource(String uuid) {
        var resource = mock(AtmosphereResource.class);
        var broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(resource.uuid()).thenReturn(uuid);
        return resource;
    }

    // ── DefaultStreamingSession registry ────────────────────────────────

    @Test
    void sweepReapsOrphanedDefaultSessionButNotLiveOne() {
        var orphan = new DefaultStreamingSession("sweep-orphan", resource("uuid-orphan"));
        var live = new DefaultStreamingSession("sweep-live", resource("uuid-live"));

        // Age only the orphan past the TTL; `live` keeps its construction stamp.
        orphan.lastActivityMillis = System.currentTimeMillis() - (TTL_MS + 5_000L);

        var reaped = DefaultStreamingSession.sweepExpired(TTL_MS);

        assertEquals(1, reaped, "exactly the orphaned session must be reaped");
        assertTrue(DefaultStreamingSession.resourceForSession("sweep-orphan").isEmpty(),
                "orphaned session must be removed from the process-global map");
        assertTrue(orphan.isClosed(),
                "a reaped session must be marked closed so late writes are dropped");
        assertTrue(DefaultStreamingSession.resourceForSession("sweep-live").isPresent(),
                "a session with recent activity must survive the sweep");
        assertFalse(live.isClosed(), "a live session must not be closed by the sweeper");

        live.complete();
    }

    @Test
    void outboundActivityRefreshesTheTtlClock() {
        var session = new DefaultStreamingSession("sweep-refresh", resource("uuid-refresh"));
        session.lastActivityMillis = System.currentTimeMillis() - (TTL_MS + 5_000L);

        // A frame on the wire must reset the clock and save the session.
        session.send("still streaming");

        assertEquals(0, DefaultStreamingSession.sweepExpired(TTL_MS),
                "a session that just sent a frame must not be reaped");
        assertTrue(DefaultStreamingSession.resourceForSession("sweep-refresh").isPresent());

        session.complete();
    }

    @Test
    void completedSessionLeavesNothingForTheSweeper() {
        var session = new DefaultStreamingSession("sweep-completed", resource("uuid-completed"));
        session.complete();

        assertEquals(0, DefaultStreamingSession.sweepExpired(TTL_MS),
                "terminal events already reclaim the entry — sweep is the backstop, not the primary path");
    }

    // ── AiStreamingSession registry ─────────────────────────────────────

    @Test
    void sweepReapsOrphanedAiSessionButNotLiveOne() {
        var orphanResource = resource("ai-uuid-orphan");
        var liveResource = resource("ai-uuid-live");
        var orphan = new AiStreamingSession(
                new CollectingSession(), mock(AgentRuntime.class), "", null,
                List.of(), orphanResource);
        var live = new AiStreamingSession(
                new CollectingSession(), mock(AgentRuntime.class), "", null,
                List.of(), liveResource);
        AiStreamingSession.registerActive(orphan);
        AiStreamingSession.registerActive(live);

        orphan.lastActivityMillis = System.currentTimeMillis() - (TTL_MS + 5_000L);

        var reaped = AiStreamingSession.sweepExpired(TTL_MS);

        assertEquals(1, reaped, "exactly the orphaned AI session must be reaped");
        assertFalse(AiStreamingSession.resourceHasActiveSessions("ai-uuid-orphan"),
                "orphaned AI session must be removed from ACTIVE_SESSIONS");
        assertTrue(AiStreamingSession.resourceHasActiveSessions("ai-uuid-live"),
                "a live AI session must survive the sweep");

        AiStreamingSession.removeAllForResource("ai-uuid-live");
    }

    @Test
    void sweepKeepsOtherSessionsOnTheSameResource() {
        var shared = resource("ai-uuid-shared");
        var stale = new AiStreamingSession(
                new CollectingSession(), mock(AgentRuntime.class), "", null, List.of(), shared);
        var fresh = new AiStreamingSession(
                new CollectingSession(), mock(AgentRuntime.class), "", null, List.of(), shared);
        AiStreamingSession.registerActive(stale);
        AiStreamingSession.registerActive(fresh);

        // Overlapping prompts on one socket: only the abandoned one ages out.
        stale.lastActivityMillis = System.currentTimeMillis() - (TTL_MS + 5_000L);

        assertEquals(1, AiStreamingSession.sweepExpired(TTL_MS));
        assertTrue(AiStreamingSession.resourceHasActiveSessions("ai-uuid-shared"),
                "the still-live overlapping prompt must keep its registration");

        AiStreamingSession.removeAllForResource("ai-uuid-shared");
    }

    // ── Sweeper lifecycle + configuration ───────────────────────────────

    @Test
    void ttlDefaultsAndHonoursSystemProperty() {
        assertEquals(StreamingSessionSweeper.DEFAULT_TTL_MS, StreamingSessionSweeper.ttlMs());

        System.setProperty(StreamingSessionSweeper.TTL_PROPERTY, "12345");
        assertEquals(12345L, StreamingSessionSweeper.ttlMs());
    }

    @Test
    void malformedOrNonPositiveTtlFallsBackToDefault() {
        System.setProperty(StreamingSessionSweeper.TTL_PROPERTY, "not-a-number");
        assertEquals(StreamingSessionSweeper.DEFAULT_TTL_MS, StreamingSessionSweeper.ttlMs());

        System.setProperty(StreamingSessionSweeper.TTL_PROPERTY, "0");
        assertEquals(StreamingSessionSweeper.DEFAULT_TTL_MS, StreamingSessionSweeper.ttlMs(),
                "a zero TTL would reap live sessions — must fall back to the default");
    }

    @Test
    void sweeperStartsOnRegistrationAndShutdownIsIdempotent() {
        StreamingSessionSweeper.shutdown();
        assertFalse(StreamingSessionSweeper.isRunning());

        // Registering a session lazily starts the daemon sweeper.
        var session = new DefaultStreamingSession("sweep-lifecycle", resource("uuid-lifecycle"));
        assertTrue(StreamingSessionSweeper.isRunning(),
                "constructing a session must start the sweeper");

        StreamingSessionSweeper.shutdown();
        assertFalse(StreamingSessionSweeper.isRunning(), "shutdown must stop the sweeper");
        StreamingSessionSweeper.shutdown();
        assertFalse(StreamingSessionSweeper.isRunning(), "shutdown must be idempotent");

        session.complete();
    }

    @Test
    void sweepPassIsSafeWhenRegistriesAreEmpty() {
        // The scheduled task must never throw — a failing pass would kill the
        // scheduled future and silently disable the leak guard.
        StreamingSessionSweeper.sweep();
    }
}
