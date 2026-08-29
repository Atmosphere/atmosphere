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
package org.atmosphere.ai.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The owner identity must survive a recycled request so on-disconnect fact
 * extraction still runs.
 *
 * <p>Tomcat can fire the async error/cancel listener with a recycled request: the
 * {@code AtmosphereResourceEvent} still carries its uuid, but the request — and with
 * it the {@code ai.userId} attribute — is gone. {@code handleDisconnect} therefore
 * called {@code cleanupDisconnected(null, uuid)}, so
 * {@code LongTermMemoryInterceptor.onDisconnect} received a null userId and returned
 * early at DEBUG. Every fact from that conversation was dropped <em>even when the
 * application had configured an identity</em> — silently, on the path that fires when
 * a browser tab is closed, which is exactly when on-session-close extraction is
 * supposed to run.</p>
 *
 * <p>The fix remembers the resolved owner keyed by uuid for the life of the
 * connection and reclaims it on the recycled path. These tests pin that the identity
 * round-trips and that the entry is released on cleanup — an unbounded map keyed by
 * connection would be a leak (Invariant #3).</p>
 */
class AiEndpointHandlerRecycledDisconnectUserIdTest {

    private static final String UUID_A = "uuid-recycled-A";
    private static final String UUID_B = "uuid-recycled-B";

    @AfterEach
    void clearState() {
        AiEndpointHandler.forgetRunOwner(UUID_A);
        AiEndpointHandler.forgetRunOwner(UUID_B);
    }

    @Test
    void ownerIsRecoverableAfterTheRequestIsRecycled() {
        AiEndpointHandler.rememberRunOwner(UUID_A, "alice");

        // The recycled path has no request and therefore no ai.userId attribute;
        // the uuid is the only thing left to key on.
        assertEquals("alice", AiEndpointHandler.recallRunOwner(UUID_A),
                "on-disconnect fact extraction needs the owner that was resolved at "
                        + "connect time — passing null here silently drops every fact");
    }

    @Test
    void ownersDoNotBleedBetweenConnections() {
        AiEndpointHandler.rememberRunOwner(UUID_A, "alice");
        AiEndpointHandler.rememberRunOwner(UUID_B, "bob");

        assertEquals("alice", AiEndpointHandler.recallRunOwner(UUID_A));
        assertEquals("bob", AiEndpointHandler.recallRunOwner(UUID_B),
                "facts must never be attributed to the wrong user");
    }

    @Test
    void forgettingReleasesTheEntry() {
        AiEndpointHandler.rememberRunOwner(UUID_A, "alice");
        AiEndpointHandler.forgetRunOwner(UUID_A);

        assertNull(AiEndpointHandler.recallRunOwner(UUID_A),
                "the map is keyed by connection — not releasing it on cleanup is an "
                        + "unbounded structure fed by external input");
    }

    @Test
    void anonymousConnectionsAreNotRemembered() {
        AiEndpointHandler.rememberRunOwner(UUID_A, null);
        assertNull(AiEndpointHandler.recallRunOwner(UUID_A),
                "an anonymous connection deliberately gets no memory");

        AiEndpointHandler.rememberRunOwner(UUID_A, "   ");
        assertNull(AiEndpointHandler.recallRunOwner(UUID_A), "blank is not an identity");
    }
}
