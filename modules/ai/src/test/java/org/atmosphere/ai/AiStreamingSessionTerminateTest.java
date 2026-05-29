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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the session-scoped teardown primitive ({@link StreamingSession#onTerminate})
 * fires on every terminal path exactly once — the contract the code-as-action
 * sandbox relies on to never leak a container (Correctness Invariants #1, #2).
 */
class AiStreamingSessionTerminateTest {

    private StreamingSession delegate;
    private AgentRuntime runtime;
    private AtmosphereResource resource;

    @BeforeEach
    void setUp() {
        delegate = mock(StreamingSession.class);
        runtime = mock(AgentRuntime.class);
        resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
    }

    private AiStreamingSession newSession() {
        return new AiStreamingSession(delegate, runtime, "system", null, List.of(), resource);
    }

    @Test
    void completeClosesRegisteredResourceExactlyOnce() {
        var session = newSession();
        var closes = new AtomicInteger();
        session.onTerminate(closes::incrementAndGet);

        session.complete();
        session.complete(); // idempotent — second terminal call must not re-close

        assertEquals(1, closes.get());
    }

    @Test
    void errorAlsoFiresTeardown() {
        var session = newSession();
        var closed = new AtomicInteger();
        session.onTerminate(closed::incrementAndGet);

        session.error(new RuntimeException("boom"));

        assertEquals(1, closed.get(), "the failure path must release session-scoped resources");
    }

    @Test
    void allRegisteredResourcesCloseEvenWhenOneThrows() {
        var session = newSession();
        var firstClosed = new AtomicInteger();
        var lastClosed = new AtomicInteger();
        session.onTerminate(firstClosed::incrementAndGet);
        session.onTerminate(() -> {
            throw new IllegalStateException("close failure");
        });
        session.onTerminate(lastClosed::incrementAndGet);

        session.complete();

        assertEquals(1, firstClosed.get());
        assertEquals(1, lastClosed.get(), "a failing close must not block the others");
    }

    @Test
    void registeringAfterTerminationClosesImmediately() {
        var session = newSession();
        session.complete();

        var closed = new AtomicInteger();
        session.onTerminate(closed::incrementAndGet);

        assertTrue(closed.get() >= 1, "registering after termination must close immediately, not leak");
    }
}
