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

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        AiStreamingSession.removeActiveSession(RESOURCE_UUID);
    }

    @Test
    void registerAndLookupActiveSession() {
        var delegate = mock(StreamingSession.class);
        var runtime = mock(AgentRuntime.class);
        var session = new AiStreamingSession(delegate, runtime, "sys", null,
                List.of(), resource);

        AiStreamingSession.registerActive(session);

        var found = AiStreamingSession.activeSession(RESOURCE_UUID);
        assertNotNull(found);
        assertEquals(session, found);
    }

    @Test
    void completeRemovesActiveSession() {
        var delegate = mock(StreamingSession.class);
        var runtime = mock(AgentRuntime.class);
        var session = new AiStreamingSession(delegate, runtime, "sys", null,
                List.of(), resource);
        AiStreamingSession.registerActive(session);

        session.complete();

        assertNull(AiStreamingSession.activeSession(RESOURCE_UUID));
    }

    @Test
    void completeWithSummaryRemovesActiveSession() {
        var delegate = mock(StreamingSession.class);
        var runtime = mock(AgentRuntime.class);
        var session = new AiStreamingSession(delegate, runtime, "sys", null,
                List.of(), resource);
        AiStreamingSession.registerActive(session);

        session.complete("done");

        assertNull(AiStreamingSession.activeSession(RESOURCE_UUID));
    }

    @Test
    void errorRemovesActiveSession() {
        var delegate = mock(StreamingSession.class);
        var runtime = mock(AgentRuntime.class);
        var session = new AiStreamingSession(delegate, runtime, "sys", null,
                List.of(), resource);
        AiStreamingSession.registerActive(session);

        session.error(new RuntimeException("test"));

        assertNull(AiStreamingSession.activeSession(RESOURCE_UUID));
    }

    @Test
    void removeActiveSessionNullSafe() {
        AiStreamingSession.removeActiveSession(null);
        // Should not throw
    }

    @Test
    void registerActiveNullUuidSafe() {
        var nullResource = mock(AtmosphereResource.class);
        when(nullResource.uuid()).thenReturn(null);
        var request = mock(AtmosphereRequest.class);
        when(nullResource.getRequest()).thenReturn(request);
        var delegate = mock(StreamingSession.class);
        var runtime = mock(AgentRuntime.class);
        var session = new AiStreamingSession(delegate, runtime, "sys", null,
                List.of(), nullResource);

        AiStreamingSession.registerActive(session);
        // Should not throw; no entry created
        assertNull(AiStreamingSession.activeSession(null));
    }

    @Test
    void tryResolveApprovalRoutesToRegistry() throws Exception {
        var delegate = mock(StreamingSession.class);
        var runtime = mock(AgentRuntime.class);
        var session = new AiStreamingSession(delegate, runtime, "sys", null,
                List.of(), resource);
        AiStreamingSession.registerActive(session);

        // The approval registry is internal; we can't register a pending directly.
        // But we can verify the tryResolveApproval path.
        // A message with approval prefix but unknown ID should return true
        // (expired/unknown ID handling in ApprovalRegistry).
        assertTrue(session.tryResolveApproval("/__approval/apr_unknown/approve"));
    }

    @Test
    void tryResolveApprovalReturnsFalseForNonApprovalMessage() {
        var delegate = mock(StreamingSession.class);
        var runtime = mock(AgentRuntime.class);
        var session = new AiStreamingSession(delegate, runtime, "sys", null,
                List.of(), resource);

        assertFalse(session.tryResolveApproval("Hello, world!"));
    }

    @Test
    void activeSessionReplacedOnNewRegistration() {
        var delegate = mock(StreamingSession.class);
        var runtime = mock(AgentRuntime.class);
        var session1 = new AiStreamingSession(delegate, runtime, "sys", null,
                List.of(), resource);
        var session2 = new AiStreamingSession(delegate, runtime, "sys", null,
                List.of(), resource);

        AiStreamingSession.registerActive(session1);
        AiStreamingSession.registerActive(session2);

        assertEquals(session2, AiStreamingSession.activeSession(RESOURCE_UUID));
    }

    @Test
    void noActiveSessionReturnsNull() {
        assertNull(AiStreamingSession.activeSession("nonexistent-uuid"));
    }
}
