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

import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins {@link DurableRunScopeHolder} resolution via {@code session.runId()} and
 * the deterministic per-(tool,args) occurrence cursor on {@link DurableRunContext}.
 */
class DurableRunScopeHolderTest {

    @AfterEach
    void cleanup() {
        DurableRunScopeHolder.clear();
    }

    private static DurableRunContext ctx(String runId) {
        return new DurableRunContext(runId, EffectJournal.NOOP, false, "owner");
    }

    @Test
    void resolvesScopeFromSessionRunId() {
        var context = ctx("run-1");
        DurableRunScopeHolder.install("run-1", context);

        assertSame(context, DurableRunScopeHolder.current(new RunIdSession("run-1")),
                "current() resolves the scope from session.runId()");
        assertSame(context, DurableRunScopeHolder.get("run-1"));
    }

    @Test
    void returnsNullWhenSessionHasNoRunIdOrNoScope() {
        assertNull(DurableRunScopeHolder.current(new RunIdSession(null)),
                "no run id on the session -> no scope (non-durable fast path)");
        assertNull(DurableRunScopeHolder.current(new RunIdSession("unknown")),
                "a run id with no installed scope -> null");
        assertNull(DurableRunScopeHolder.current(null));
    }

    @Test
    void removeDropsScope() {
        DurableRunScopeHolder.install("run-1", ctx("run-1"));
        DurableRunScopeHolder.remove("run-1");
        assertNull(DurableRunScopeHolder.get("run-1"));
    }

    @Test
    void occurrenceOrdinalIsMonotonicPerToolAndArgs() {
        var context = ctx("run-1");
        assertEquals(0, context.nextToolOccurrence("delete_row", "h7"));
        assertEquals(1, context.nextToolOccurrence("delete_row", "h7"));
        assertEquals(2, context.nextToolOccurrence("delete_row", "h7"));
        // A different (tool,args) has its own independent counter starting at 0.
        assertEquals(0, context.nextToolOccurrence("delete_row", "h8"));
        assertEquals(0, context.nextToolOccurrence("insert_row", "h7"));
    }

    /** Minimal {@link StreamingSession} that only carries a run id. */
    private record RunIdSession(String id) implements StreamingSession {
        @Override
        public Optional<String> runId() {
            return Optional.ofNullable(id);
        }

        @Override
        public String sessionId() {
            return "session";
        }

        @Override
        public void send(String text) {
        }

        @Override
        public void sendMetadata(String key, Object value) {
        }

        @Override
        public void progress(String message) {
        }

        @Override
        public void complete() {
        }

        @Override
        public void complete(String summary) {
        }

        @Override
        public void error(Throwable t) {
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }
}
