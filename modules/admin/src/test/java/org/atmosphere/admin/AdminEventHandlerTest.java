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
package org.atmosphere.admin;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AdminEventHandlerTest {

    private AdminEventHandler handler;

    @BeforeEach
    public void setUp() {
        handler = new AdminEventHandler();
    }

    @Test
    public void testOnRequestSuspendsResource() throws IOException {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("test-uuid");

        handler.onRequest(resource);

        verify(resource).suspend();
    }

    @Test
    public void testOnStateChangeWritesMessage() throws IOException {
        var resource = mock(AtmosphereResource.class);
        var event = mock(AtmosphereResourceEvent.class);
        when(event.getResource()).thenReturn(resource);
        when(event.isCancelled()).thenReturn(false);
        when(event.isClosedByClient()).thenReturn(false);
        when(event.isClosedByApplication()).thenReturn(false);
        when(event.getMessage()).thenReturn("{\"type\":\"test\"}");

        handler.onStateChange(event);

        verify(resource).write("{\"type\":\"test\"}");
    }

    @Test
    public void testOnStateChangeCancelledDoesNotWrite() throws IOException {
        var resource = mock(AtmosphereResource.class);
        var event = mock(AtmosphereResourceEvent.class);
        when(event.getResource()).thenReturn(resource);
        when(event.isCancelled()).thenReturn(true);

        handler.onStateChange(event);

        verify(resource, never()).write(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    public void testOnStateChangeClosedByClientDoesNotWrite() throws IOException {
        var resource = mock(AtmosphereResource.class);
        var event = mock(AtmosphereResourceEvent.class);
        when(event.getResource()).thenReturn(resource);
        when(event.isCancelled()).thenReturn(false);
        when(event.isClosedByClient()).thenReturn(true);

        handler.onStateChange(event);

        verify(resource, never()).write(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    public void testOnStateChangeClosedByApplicationDoesNotWrite() throws IOException {
        var resource = mock(AtmosphereResource.class);
        var event = mock(AtmosphereResourceEvent.class);
        when(event.getResource()).thenReturn(resource);
        when(event.isCancelled()).thenReturn(false);
        when(event.isClosedByClient()).thenReturn(false);
        when(event.isClosedByApplication()).thenReturn(true);

        handler.onStateChange(event);

        verify(resource, never()).write(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    public void testOnStateChangeNullMessageDoesNotWrite() throws IOException {
        var resource = mock(AtmosphereResource.class);
        var event = mock(AtmosphereResourceEvent.class);
        when(event.getResource()).thenReturn(resource);
        when(event.isCancelled()).thenReturn(false);
        when(event.isClosedByClient()).thenReturn(false);
        when(event.isClosedByApplication()).thenReturn(false);
        when(event.getMessage()).thenReturn(null);

        handler.onStateChange(event);

        verify(resource, never()).write(org.mockito.ArgumentMatchers.anyString());
    }

    // ── broadcastEvent (static) ──

    @Test
    public void testBroadcastEventSuccess() {
        var factory = mock(BroadcasterFactory.class);
        var broadcaster = mock(Broadcaster.class);
        when(factory.lookup(AdminEventHandler.ADMIN_BROADCASTER_ID, false))
                .thenReturn(broadcaster);
        when(broadcaster.isDestroyed()).thenReturn(false);

        AdminEventHandler.broadcastEvent(factory, "{\"type\":\"test\"}");

        verify(broadcaster).broadcast("{\"type\":\"test\"}");
    }

    @Test
    public void testBroadcastEventNullFactory() {
        // Should not throw
        AdminEventHandler.broadcastEvent(null, "{\"type\":\"test\"}");
    }

    @Test
    public void testBroadcastEventNoBroadcaster() {
        var factory = mock(BroadcasterFactory.class);
        when(factory.lookup(AdminEventHandler.ADMIN_BROADCASTER_ID, false))
                .thenReturn(null);

        // Should not throw
        AdminEventHandler.broadcastEvent(factory, "{\"type\":\"test\"}");
    }

    @Test
    public void testBroadcastEventDestroyedBroadcaster() {
        var factory = mock(BroadcasterFactory.class);
        var broadcaster = mock(Broadcaster.class);
        when(factory.lookup(AdminEventHandler.ADMIN_BROADCASTER_ID, false))
                .thenReturn(broadcaster);
        when(broadcaster.isDestroyed()).thenReturn(true);

        AdminEventHandler.broadcastEvent(factory, "{\"type\":\"test\"}");

        verify(broadcaster, never()).broadcast(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    public void testAdminBroadcasterIdConstant() {
        assertEquals("/atmosphere/admin/events", AdminEventHandler.ADMIN_BROADCASTER_ID);
    }
}
