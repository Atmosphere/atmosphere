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

import org.atmosphere.ai.approval.ApprovalRegistry;
import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovalRoutingTest {

    private static final String RESOURCE_UUID = "test-resource-uuid";
    private AtmosphereResource resource;

    @BeforeEach
    void setUp() {
        resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn(RESOURCE_UUID);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
    }

    @AfterEach
    void tearDown() {
        AiStreamingSession.removeAllForResource(RESOURCE_UUID);
    }

    private AiStreamingSession newSession() {
        return newSession(resource);
    }

    private AiStreamingSession newSession(AtmosphereResource r) {
        var delegate = mock(StreamingSession.class);
        var runtime = mock(AgentRuntime.class);
        return new AiStreamingSession(delegate, runtime, "sys", null, List.of(), r);
    }

    private static PendingApproval pending(String id) {
        return new PendingApproval(id, "tool", java.util.Map.of(),
                "tool reason", "prompt", Instant.now().plusSeconds(30));
    }

    @Test
    void registerAndResolveMatchingApproval() {
        var session = newSession();
        AiStreamingSession.registerActive(session);

        var approvalId = "apr_match";
        session.approvalRegistry().register(pending(approvalId));

        assertTrue(AiStreamingSession.tryResolveApprovalForResource(
                RESOURCE_UUID, "/__approval/" + approvalId + "/approve"));
    }

    @Test
    void completeRemovesOnlyThisSession() {
        var a = newSession();
        var b = newSession();
        AiStreamingSession.registerActive(a);
        AiStreamingSession.registerActive(b);

        b.complete();

        // B is gone, A is still registered and can still own approvals on this UUID.
        var approvalId = "apr_a_only";
        a.approvalRegistry().register(pending(approvalId));
        assertTrue(AiStreamingSession.tryResolveApprovalForResource(
                RESOURCE_UUID, "/__approval/" + approvalId + "/approve"));
    }

    @Test
    void errorRemovesOnlyThisSession() {
        var a = newSession();
        var b = newSession();
        AiStreamingSession.registerActive(a);
        AiStreamingSession.registerActive(b);

        a.error(new RuntimeException("boom"));

        var approvalId = "apr_b_only";
        b.approvalRegistry().register(pending(approvalId));
        assertTrue(AiStreamingSession.tryResolveApprovalForResource(
                RESOURCE_UUID, "/__approval/" + approvalId + "/approve"));
    }

    @Test
    void overlappingPromptsOnSameResourceBothRouteApprovalsCorrectly() {
        // Prompt A and Prompt B run concurrently on the same AtmosphereResource.
        // A's registry owns apr_a, B's registry owns apr_b. Each approval message
        // must route to the registry that actually owns the ID — walking the list
        // of sessions registered for this UUID and short-circuiting only on
        // RESOLVED (not on the UNKNOWN_ID returned by the other session).
        var a = newSession();
        var b = newSession();
        AiStreamingSession.registerActive(a);
        AiStreamingSession.registerActive(b);

        a.approvalRegistry().register(pending("apr_a"));
        b.approvalRegistry().register(pending("apr_b"));

        assertTrue(AiStreamingSession.tryResolveApprovalForResource(
                RESOURCE_UUID, "/__approval/apr_a/approve"));
        assertTrue(AiStreamingSession.tryResolveApprovalForResource(
                RESOURCE_UUID, "/__approval/apr_b/approve"));
    }

    @Test
    void unknownApprovalIdDoesNotConsumeLaterOwners() {
        // Regression: previously, tryResolveAnySession short-circuited on tryResolve
        // returning true for UNKNOWN_ID, so the first session scanned would swallow
        // the message even when a later session owned the pending approval.
        var resourceA = mock(AtmosphereResource.class);
        when(resourceA.uuid()).thenReturn("uuid-a");
        var resourceB = mock(AtmosphereResource.class);
        when(resourceB.uuid()).thenReturn("uuid-b");

        var sessionA = newSession(resourceA);
        var sessionB = newSession(resourceB);
        AiStreamingSession.registerActive(sessionA);
        AiStreamingSession.registerActive(sessionB);

        try {
            sessionB.approvalRegistry().register(pending("apr_owned_by_b"));

            assertTrue(AiStreamingSession.tryResolveAnySession(
                    "/__approval/apr_owned_by_b/approve"));
        } finally {
            AiStreamingSession.removeAllForResource("uuid-a");
            AiStreamingSession.removeAllForResource("uuid-b");
        }
    }

    @Test
    void resourceScopedLookupReturnsFalseForMissingUuid() {
        assertFalse(AiStreamingSession.tryResolveApprovalForResource(
                "nonexistent-uuid", "/__approval/any/approve"));
    }

    @Test
    void tryResolveApprovalReturnsFalseForNonApprovalMessage() {
        var session = newSession();
        assertFalse(session.tryResolveApproval("Hello, world!"));
    }

    @Test
    void tryResolveApprovalOnAdapterReturnsTrueForUnknownId() {
        // The single-registry tryResolve adapter preserves the "consumed"
        // semantics for the websocket fast-path: an approval-shaped message with
        // an unknown ID still short-circuits so it isn't forwarded as a prompt.
        var session = newSession();
        assertTrue(session.tryResolveApproval("/__approval/apr_unknown/approve"));
    }

    @Test
    void removeActiveSessionNullSafe() {
        AiStreamingSession.removeActiveSession(null);
        AiStreamingSession.removeAllForResource(null);
        // Should not throw
    }

    @Test
    void registerActiveNullUuidSafe() {
        var nullResource = mock(AtmosphereResource.class);
        when(nullResource.uuid()).thenReturn(null);
        var request = mock(AtmosphereRequest.class);
        when(nullResource.getRequest()).thenReturn(request);
        var session = newSession(nullResource);

        AiStreamingSession.registerActive(session);
        // Should not throw; no entry created
        assertFalse(AiStreamingSession.tryResolveApprovalForResource(null, "/__approval/x/approve"));
    }

    @Test
    void cleanShutdownResolveTriStateEnumExposed() {
        // Sanity: the new tri-state is reachable from tests and matches wire protocol.
        var registry = new ApprovalRegistry();
        assertEquals(ApprovalRegistry.ResolveResult.NOT_APPROVAL_MESSAGE,
                registry.resolve("hello"));
        assertEquals(ApprovalRegistry.ResolveResult.UNKNOWN_ID,
                registry.resolve("/__approval/apr_ghost/approve"));
    }
}
