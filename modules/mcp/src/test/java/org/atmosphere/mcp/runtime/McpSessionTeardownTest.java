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
package org.atmosphere.mcp.runtime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link McpSession} teardown completing its in-flight
 * server-initiated requests (Correctness Invariant #2 — Terminal Path
 * Completeness).
 *
 * <p>Pending {@code elicitation/create} futures used to be dropped
 * un-completed when a session was deleted, evicted by TTL, or discarded on
 * handler destroy, parking every caller awaiting a response forever.</p>
 */
class McpSessionTeardownTest {

    private static CompletableFuture<JsonNode> register(McpSession session, String id) {
        var future = new CompletableFuture<JsonNode>();
        session.registerServerRequest(id, future);
        return future;
    }

    @Test
    void closeFailsEveryPendingServerRequest() {
        var session = new McpSession();
        var first = register(session, "req-1");
        var second = register(session, "req-2");

        assertEquals(2, session.pendingServerRequestIds().size());

        var cancelled = session.close();

        assertEquals(2, cancelled, "close must report how many futures it failed");
        assertTrue(first.isCompletedExceptionally(), "pending future must be completed, not dropped");
        assertTrue(second.isCompletedExceptionally());
        assertTrue(session.pendingServerRequestIds().isEmpty(), "the map must be drained");
    }

    @Test
    void closedFutureCarriesASessionClosedCause() {
        var session = new McpSession();
        var future = register(session, "req-cause");

        session.close();

        var thrown = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        var cause = assertInstanceOf(McpSession.McpSessionClosedException.class, thrown.getCause(),
                "callers must be able to distinguish teardown from a protocol failure");
        assertEquals(session.sessionId(), cause.sessionId());
    }

    @Test
    void awaitingCallerUnparksImmediatelyOnClose() throws Exception {
        var session = new McpSession();
        var future = register(session, "req-await");

        // Without the teardown fix this join would never return.
        var waiter = CompletableFuture.runAsync(() -> {
            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                // expected — session closed
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (TimeoutException e) {
                throw new IllegalStateException("caller was never released", e);
            }
        });

        session.close();

        waiter.get(5, TimeUnit.SECONDS);
    }

    @Test
    void closeIsIdempotent() {
        var session = new McpSession();
        register(session, "req-idem");

        assertEquals(1, session.close(), "first close fails the one pending request");
        assertEquals(0, session.close(), "second close has nothing left to fail");
        assertEquals(0, session.close());
        assertTrue(session.isClosed());
    }

    @Test
    void registerAfterCloseFailsFastRatherThanStranding() {
        var session = new McpSession();
        session.close();

        var future = new CompletableFuture<JsonNode>();
        session.registerServerRequest("req-late", future);

        assertTrue(future.isCompletedExceptionally(),
                "registering onto a closed session must fail the future — nothing would ever complete it");
        assertTrue(session.pendingServerRequestIds().isEmpty(),
                "a closed session must not accumulate new entries");
    }

    @Test
    void pendingServerRequestsAreBounded() {
        var session = new McpSession();
        for (int i = 0; i < McpSession.MAX_PENDING_SERVER_REQUESTS; i++) {
            register(session, "req-" + i);
        }
        assertEquals(McpSession.MAX_PENDING_SERVER_REQUESTS, session.pendingServerRequestIds().size());

        var overflow = new CompletableFuture<JsonNode>();
        session.registerServerRequest("req-overflow", overflow);

        assertTrue(overflow.isCompletedExceptionally(),
                "past the cap the caller must see backpressure, not an unbounded map (Invariant #3)");
        assertEquals(McpSession.MAX_PENDING_SERVER_REQUESTS, session.pendingServerRequestIds().size());
    }

    @Test
    void closeDoesNotDisturbAnAlreadyAnsweredRequest() {
        var session = new McpSession();
        var answered = register(session, "req-answered");
        var pending = register(session, "req-pending");

        assertTrue(session.completeServerRequest("req-answered", null));
        assertTrue(answered.isDone());
        assertFalse(answered.isCompletedExceptionally(), "a normal response must stay successful");

        assertEquals(1, session.close(), "only the still-pending request is failed");
        assertTrue(pending.isCompletedExceptionally());
        assertFalse(answered.isCompletedExceptionally());
    }

    @Test
    void freshSessionIsNotClosed() {
        assertFalse(new McpSession().isClosed());
    }
}
