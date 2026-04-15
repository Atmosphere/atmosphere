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
package org.atmosphere.interceptor;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SuspendTrackerInterceptorTest {

    @Test
    void toStringReturnsExpected() {
        assertEquals("UUID Tracking Interceptor",
                new SuspendTrackerInterceptor().toString());
    }

    @Test
    void priorityIsBeforeDefault() {
        assertEquals(
                InvokationOrder.BEFORE_DEFAULT,
                new SuspendTrackerInterceptor().priority());
    }

    @Test
    void trackedUUIDStartsEmpty() {
        var interceptor = new SuspendTrackerInterceptor();
        assertNotNull(interceptor.trackedUUID());
        assertTrue(interceptor.trackedUUID().isEmpty());
    }

    @Test
    void inspectSkipsWebSocketMessages() {
        var r = mock(AtmosphereResourceImpl.class);
        var req = mock(AtmosphereRequest.class);
        when(r.getRequest(false)).thenReturn(req);
        // Mark as WebSocket message
        when(req.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE))
                .thenReturn("true");

        var interceptor = new SuspendTrackerInterceptor();
        assertEquals(Action.CONTINUE, interceptor.inspect(r));
        assertTrue(interceptor.trackedUUID().isEmpty());
    }

    @Test
    void inspectSkipsConnectingRequests() {
        var r = mock(AtmosphereResourceImpl.class);
        var req = mock(AtmosphereRequest.class);
        when(r.getRequest(false)).thenReturn(req);
        when(req.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE))
                .thenReturn(null);
        // Connecting: tracking-id header is "0"
        when(req.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID))
                .thenReturn("0");

        var interceptor = new SuspendTrackerInterceptor();
        assertEquals(Action.CONTINUE, interceptor.inspect(r));
    }

    @Test
    void trackedUUIDIsModifiable() {
        var interceptor = new SuspendTrackerInterceptor();
        interceptor.trackedUUID().add("test-uuid-1");
        assertTrue(interceptor.trackedUUID().contains("test-uuid-1"));
        interceptor.trackedUUID().remove("test-uuid-1");
        assertFalse(interceptor.trackedUUID().contains("test-uuid-1"));
    }
}
