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
package org.atmosphere.session;

import jakarta.servlet.ServletContext;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

public class DurableSessionInterceptorTest {

    private InMemorySessionStore store;
    private DurableSessionInterceptor interceptor;
    private AtmosphereResource resource;
    private AtmosphereRequest request;
    private AtmosphereResponse response;
    private AtmosphereConfig config;
    private AtmosphereFramework framework;
    private BroadcasterFactory broadcasterFactory;
    private ServletContext servletContext;

    @BeforeMethod
    public void setUp() {
        store = new InMemorySessionStore();
        interceptor = new DurableSessionInterceptor(store, Duration.ofHours(24), Duration.ofMinutes(5));

        resource = mock(AtmosphereResource.class);
        request = mock(AtmosphereRequest.class);
        response = mock(AtmosphereResponse.class);
        config = mock(AtmosphereConfig.class);
        framework = mock(AtmosphereFramework.class);
        broadcasterFactory = mock(BroadcasterFactory.class);
        servletContext = mock(ServletContext.class);

        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(resource.uuid()).thenReturn("resource-uuid-1");
        when(resource.broadcasters()).thenReturn(List.of());

        when(config.getBroadcasterFactory()).thenReturn(broadcasterFactory);
        when(config.framework()).thenReturn(framework);
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(config.getServletContext()).thenReturn(servletContext);

        interceptor.configure(config);
    }

    @Test
    public void testNewSessionCreated() {
        when(request.getHeader(DurableSessionInterceptor.SESSION_TOKEN_HEADER)).thenReturn(null);
        when(request.getParameter("X-Atmosphere-Session-Token")).thenReturn(null);

        var action = interceptor.inspect(resource);

        assertEquals(action, Action.CONTINUE);
        // Should set session token in response
        verify(response).setHeader(eq(DurableSessionInterceptor.SESSION_TOKEN_RESPONSE_HEADER), anyString());
    }

    @Test
    public void testSessionTokenSetInResponse() {
        when(request.getHeader(DurableSessionInterceptor.SESSION_TOKEN_HEADER)).thenReturn(null);
        when(request.getParameter("X-Atmosphere-Session-Token")).thenReturn(null);

        interceptor.inspect(resource);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(eq(DurableSessionInterceptor.SESSION_TOKEN_RESPONSE_HEADER), captor.capture());
        // Token should be a UUID
        assertNotNull(captor.getValue());
        assertEquals(captor.getValue().length(), 36);
    }

    @Test
    public void testSessionRestoredFromHeader() {
        // Pre-populate a session
        var session = DurableSession.create("existing-token", "old-resource")
                .withRooms(Set.of("chat"))
                .withBroadcasters(Set.of("/chat"));
        store.save(session);

        when(request.getHeader(DurableSessionInterceptor.SESSION_TOKEN_HEADER)).thenReturn("existing-token");

        // Mock broadcaster lookup
        var broadcaster = mock(Broadcaster.class);
        when(broadcasterFactory.lookup("/chat", false)).thenReturn(broadcaster);

        var action = interceptor.inspect(resource);

        assertEquals(action, Action.CONTINUE);
        verify(response).setHeader(DurableSessionInterceptor.SESSION_TOKEN_RESPONSE_HEADER, "existing-token");
        verify(broadcaster).addAtmosphereResource(resource);
    }

    @Test
    public void testExpiredTokenCreatesNewSession() {
        when(request.getHeader(DurableSessionInterceptor.SESSION_TOKEN_HEADER)).thenReturn("expired-token");

        var action = interceptor.inspect(resource);

        assertEquals(action, Action.CONTINUE);
        // Should create a new token, not reuse the expired one
        var captor = ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(eq(DurableSessionInterceptor.SESSION_TOKEN_RESPONSE_HEADER), captor.capture());
        assertNotEquals(captor.getValue(), "expired-token");
    }

    @Test
    public void testSessionTokenFromQueryParameter() {
        var session = DurableSession.create("param-token", "old-resource");
        store.save(session);

        when(request.getHeader(DurableSessionInterceptor.SESSION_TOKEN_HEADER)).thenReturn(null);
        when(request.getParameter("X-Atmosphere-Session-Token")).thenReturn("param-token");

        var action = interceptor.inspect(resource);

        assertEquals(action, Action.CONTINUE);
        verify(response).setHeader(DurableSessionInterceptor.SESSION_TOKEN_RESPONSE_HEADER, "param-token");
    }

    @Test
    public void testSaveCurrentState() {
        var session = DurableSession.create("save-token", "res-1");
        store.save(session);

        // Mock broadcaster
        var broadcaster = mock(Broadcaster.class);
        when(broadcaster.getID()).thenReturn("/chat");
        when(resource.broadcasters()).thenReturn(List.of(broadcaster));

        interceptor.saveCurrentState(resource, "save-token");

        var restored = store.restore("save-token").get();
        assertEquals(restored.broadcasters(), Set.of("/chat"));
    }

    @Test
    public void testStoreFailureOnRestoreDoesNotCrash() {
        // Use a failing store
        var failingStore = mock(SessionStore.class);
        when(failingStore.restore(anyString())).thenThrow(new RuntimeException("DB down"));

        var failInterceptor = new DurableSessionInterceptor(failingStore, Duration.ofHours(24), Duration.ofMinutes(5));
        failInterceptor.configure(config);

        when(request.getHeader(DurableSessionInterceptor.SESSION_TOKEN_HEADER)).thenReturn("some-token");

        // Should NOT throw — should gracefully create a new session
        var action = failInterceptor.inspect(resource);
        assertEquals(action, Action.CONTINUE);
    }

    @Test
    public void testStoreFailureOnSaveDoesNotCrash() {
        var failingStore = mock(SessionStore.class);
        doThrow(new RuntimeException("DB down")).when(failingStore).save(any());

        var failInterceptor = new DurableSessionInterceptor(failingStore, Duration.ofHours(24), Duration.ofMinutes(5));
        failInterceptor.configure(config);

        when(request.getHeader(DurableSessionInterceptor.SESSION_TOKEN_HEADER)).thenReturn(null);
        when(request.getParameter("X-Atmosphere-Session-Token")).thenReturn(null);

        // Should NOT throw
        var action = failInterceptor.inspect(resource);
        assertEquals(action, Action.CONTINUE);
    }

    @Test
    public void testDestroyShutdownsCleanly() {
        // Should not throw
        interceptor.destroy();
    }

    @Test
    public void testPriority() {
        assertEquals(interceptor.priority(), org.atmosphere.interceptor.InvokationOrder.BEFORE_DEFAULT);
    }
}
