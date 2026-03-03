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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class DefaultStreamingSessionTest {

    private DefaultStreamingSession createSession(String sessionId) {
        var resource = mock(AtmosphereResource.class);
        var broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(resource.uuid()).thenReturn("uuid-" + sessionId);
        return new DefaultStreamingSession(sessionId, resource);
    }

    @Test
    public void testSessionRegistersResourceOnCreate() {
        var session = createSession("reg-test");

        var result = DefaultStreamingSession.resourceForSession("reg-test");
        assertTrue(result.isPresent());

        // Cleanup
        session.complete();
    }

    @Test
    public void testSessionDeregistersOnComplete() {
        var session = createSession("dereg-complete");
        assertTrue(DefaultStreamingSession.resourceForSession("dereg-complete").isPresent());

        session.complete();
        assertTrue(DefaultStreamingSession.resourceForSession("dereg-complete").isEmpty());
    }

    @Test
    public void testSessionDeregistersOnCompleteWithSummary() {
        var session = createSession("dereg-summary");
        assertTrue(DefaultStreamingSession.resourceForSession("dereg-summary").isPresent());

        session.complete("done");
        assertTrue(DefaultStreamingSession.resourceForSession("dereg-summary").isEmpty());
    }

    @Test
    public void testSessionDeregistersOnError() {
        var session = createSession("dereg-error");
        assertTrue(DefaultStreamingSession.resourceForSession("dereg-error").isPresent());

        session.error(new RuntimeException("test error"));
        assertTrue(DefaultStreamingSession.resourceForSession("dereg-error").isEmpty());
    }

    @Test
    public void testResourceForSessionReturnsEmptyForUnknown() {
        assertTrue(DefaultStreamingSession.resourceForSession("nonexistent").isEmpty());
    }
}
