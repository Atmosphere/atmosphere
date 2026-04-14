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
package org.atmosphere.admin.framework;

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandlerWrapper;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class FrameworkControllerTest {

    private AtmosphereFramework framework;
    private BroadcasterFactory factory;
    private FrameworkController controller;

    @BeforeEach
    public void setUp() {
        framework = mock(AtmosphereFramework.class);
        factory = mock(BroadcasterFactory.class);
        when(framework.getBroadcasterFactory()).thenReturn(factory);
        controller = new FrameworkController(framework);
    }

    // ── listBroadcasters ──

    @Test
    public void testListBroadcastersReturnsSummary() {
        var broadcaster = mockBroadcaster("/chat", false);
        when(factory.lookupAll()).thenReturn(List.of(broadcaster));

        var result = controller.listBroadcasters();
        assertEquals(1, result.size());
        assertEquals("/chat", result.getFirst().get("id"));
        assertNotNull(result.getFirst().get("className"));
        assertEquals(0, result.getFirst().get("resourceCount"));
        assertEquals(false, result.getFirst().get("isDestroyed"));
    }

    @Test
    public void testListBroadcastersNullFactory() {
        when(framework.getBroadcasterFactory()).thenReturn(null);
        var ctrl = new FrameworkController(framework);
        assertTrue(ctrl.listBroadcasters().isEmpty());
    }

    @Test
    public void testListBroadcastersEmpty() {
        when(factory.lookupAll()).thenReturn(Collections.emptyList());
        assertTrue(controller.listBroadcasters().isEmpty());
    }

    // ── getBroadcaster ──

    @Test
    public void testGetBroadcasterFound() {
        var resource = mockResource("uuid-1", "WEBSOCKET", true, false);
        var broadcaster = mockBroadcaster("/chat", false);
        when(broadcaster.getAtmosphereResources()).thenReturn(List.of(resource));
        when(broadcaster.getScope()).thenReturn(Broadcaster.SCOPE.APPLICATION);
        when(factory.lookup("/chat", false)).thenReturn(broadcaster);

        var result = controller.getBroadcaster("/chat");
        assertTrue(result.isPresent());
        var detail = result.get();
        assertEquals("/chat", detail.get("id"));
        assertEquals("APPLICATION", detail.get("scope"));

        @SuppressWarnings("unchecked")
        var resources = (List<Map<String, Object>>) detail.get("resources");
        assertEquals(1, resources.size());
        assertEquals("uuid-1", resources.getFirst().get("uuid"));
    }

    @Test
    public void testGetBroadcasterNotFound() {
        when(factory.lookup("/missing", false)).thenReturn(null);
        assertTrue(controller.getBroadcaster("/missing").isEmpty());
    }

    @Test
    public void testGetBroadcasterNullFactory() {
        when(framework.getBroadcasterFactory()).thenReturn(null);
        var ctrl = new FrameworkController(framework);
        assertTrue(ctrl.getBroadcaster("/chat").isEmpty());
    }

    // ── listResources ──

    @Test
    public void testListResourcesAcrossBroadcasters() {
        var r1 = mockResource("uuid-1", "WEBSOCKET", true, false);
        var r2 = mockResource("uuid-2", "SSE", false, true);

        var b1 = mockBroadcaster("/chat", false);
        when(b1.getAtmosphereResources()).thenReturn(List.of(r1));

        var b2 = mockBroadcaster("/notify", false);
        when(b2.getAtmosphereResources()).thenReturn(List.of(r2));

        when(factory.lookupAll()).thenReturn(List.of(b1, b2));

        var result = controller.listResources();
        assertEquals(2, result.size());
        assertEquals("uuid-1", result.get(0).get("uuid"));
        assertEquals("/chat", result.get(0).get("broadcaster"));
        assertEquals("uuid-2", result.get(1).get("uuid"));
        assertEquals("/notify", result.get(1).get("broadcaster"));
    }

    @Test
    public void testListResourcesNullFactory() {
        when(framework.getBroadcasterFactory()).thenReturn(null);
        var ctrl = new FrameworkController(framework);
        assertTrue(ctrl.listResources().isEmpty());
    }

    // ── listHandlers ──

    @Test
    public void testListHandlers() {
        var wrapper = mock(AtmosphereHandlerWrapper.class);
        var handler = mock(org.atmosphere.cpr.AtmosphereHandler.class);
        when(wrapper.atmosphereHandler()).thenReturn(handler);

        var handlers = new LinkedHashMap<String, AtmosphereHandlerWrapper>();
        handlers.put("/chat", wrapper);
        when(framework.getAtmosphereHandlers()).thenReturn(handlers);

        var result = controller.listHandlers();
        assertEquals(1, result.size());
        assertEquals("/chat", result.getFirst().get("path"));
        assertFalse(result.getFirst().get("className").toString().isEmpty());
    }

    // ── listInterceptors ──

    @Test
    public void testListInterceptors() {
        var interceptor = mock(AtmosphereInterceptor.class);
        var list = new LinkedList<AtmosphereInterceptor>();
        list.add(interceptor);
        when(framework.interceptors()).thenReturn(list);

        var result = controller.listInterceptors();
        assertEquals(1, result.size());
        assertFalse(result.getFirst().get("className").toString().isEmpty());
    }

    // ── broadcast ──

    @Test
    public void testBroadcastSuccess() {
        var broadcaster = mockBroadcaster("/chat", false);
        when(factory.lookup("/chat", false)).thenReturn(broadcaster);

        assertTrue(controller.broadcast("/chat", "hello"));
        verify(broadcaster).broadcast("hello");
    }

    @Test
    public void testBroadcastUnknownBroadcaster() {
        when(factory.lookup("/missing", false)).thenReturn(null);
        assertFalse(controller.broadcast("/missing", "hello"));
    }

    @Test
    public void testBroadcastDestroyedBroadcaster() {
        var broadcaster = mockBroadcaster("/chat", true);
        when(factory.lookup("/chat", false)).thenReturn(broadcaster);

        assertFalse(controller.broadcast("/chat", "hello"));
        verify(broadcaster, never()).broadcast("hello");
    }

    @Test
    public void testBroadcastNullFactory() {
        when(framework.getBroadcasterFactory()).thenReturn(null);
        var ctrl = new FrameworkController(framework);
        assertFalse(ctrl.broadcast("/chat", "hello"));
    }

    // ── unicast ──

    @Test
    public void testUnicastSuccess() {
        var resource = mockResource("uuid-1", "WEBSOCKET", true, false);
        var broadcaster = mockBroadcaster("/chat", false);
        when(broadcaster.getAtmosphereResources()).thenReturn(List.of(resource));
        when(factory.lookup("/chat", false)).thenReturn(broadcaster);

        assertTrue(controller.unicast("/chat", "uuid-1", "hello"));
        verify(broadcaster).broadcast("hello", resource);
    }

    @Test
    public void testUnicastResourceNotFound() {
        var resource = mockResource("uuid-1", "WEBSOCKET", true, false);
        var broadcaster = mockBroadcaster("/chat", false);
        when(broadcaster.getAtmosphereResources()).thenReturn(List.of(resource));
        when(factory.lookup("/chat", false)).thenReturn(broadcaster);

        assertFalse(controller.unicast("/chat", "uuid-99", "hello"));
    }

    @Test
    public void testUnicastDestroyedBroadcaster() {
        var broadcaster = mockBroadcaster("/chat", true);
        when(factory.lookup("/chat", false)).thenReturn(broadcaster);

        assertFalse(controller.unicast("/chat", "uuid-1", "hello"));
    }

    // ── destroyBroadcaster ──

    @Test
    public void testDestroyBroadcasterSuccess() {
        var broadcaster = mockBroadcaster("/chat", false);
        when(factory.lookup("/chat", false)).thenReturn(broadcaster);

        assertTrue(controller.destroyBroadcaster("/chat"));
        verify(broadcaster).destroy();
    }

    @Test
    public void testDestroyBroadcasterNotFound() {
        when(factory.lookup("/missing", false)).thenReturn(null);
        assertFalse(controller.destroyBroadcaster("/missing"));
    }

    @Test
    public void testDestroyAlreadyDestroyedBroadcaster() {
        var broadcaster = mockBroadcaster("/chat", true);
        when(factory.lookup("/chat", false)).thenReturn(broadcaster);

        assertFalse(controller.destroyBroadcaster("/chat"));
        verify(broadcaster, never()).destroy();
    }

    // ── disconnectResource ──

    @Test
    public void testDisconnectResourceSuccess() throws IOException {
        var resource = mockResource("uuid-1", "WEBSOCKET", true, false);
        var broadcaster = mockBroadcaster("/chat", false);
        when(broadcaster.getAtmosphereResources()).thenReturn(List.of(resource));
        when(factory.lookupAll()).thenReturn(List.of(broadcaster));

        assertTrue(controller.disconnectResource("uuid-1"));
        verify(resource).close();
    }

    @Test
    public void testDisconnectResourceNotFound() {
        var broadcaster = mockBroadcaster("/chat", false);
        when(broadcaster.getAtmosphereResources())
                .thenReturn(Collections.<AtmosphereResource>emptyList());
        when(factory.lookupAll()).thenReturn(List.of(broadcaster));

        assertFalse(controller.disconnectResource("uuid-99"));
    }

    @Test
    public void testDisconnectResourceIOException() throws IOException {
        var resource = mockResource("uuid-1", "WEBSOCKET", true, false);
        doThrow(new IOException("close failed")).when(resource).close();
        var broadcaster = mockBroadcaster("/chat", false);
        when(broadcaster.getAtmosphereResources()).thenReturn(List.of(resource));
        when(factory.lookupAll()).thenReturn(List.of(broadcaster));

        // Should still return true — the resource was found
        assertTrue(controller.disconnectResource("uuid-1"));
    }

    // ── resumeResource ──

    @Test
    public void testResumeResourceSuccess() {
        var resource = mockResource("uuid-1", "WEBSOCKET", true, false);
        var broadcaster = mockBroadcaster("/chat", false);
        when(broadcaster.getAtmosphereResources()).thenReturn(List.of(resource));
        when(factory.lookupAll()).thenReturn(List.of(broadcaster));

        assertTrue(controller.resumeResource("uuid-1"));
        verify(resource).resume();
    }

    @Test
    public void testResumeResourceNotFound() {
        when(factory.lookupAll()).thenReturn(Collections.<Broadcaster>emptyList());
        assertFalse(controller.resumeResource("uuid-99"));
    }

    @Test
    public void testResumeResourceNullFactory() {
        when(framework.getBroadcasterFactory()).thenReturn(null);
        var ctrl = new FrameworkController(framework);
        assertFalse(ctrl.resumeResource("uuid-1"));
    }

    // ── Helpers ──

    @SuppressWarnings("unchecked")
    private Broadcaster mockBroadcaster(String id, boolean destroyed) {
        var broadcaster = mock(Broadcaster.class);
        when(broadcaster.getID()).thenReturn(id);
        when(broadcaster.isDestroyed()).thenReturn(destroyed);

        when(broadcaster.getAtmosphereResources())
                .thenReturn((Collection) Collections.emptyList());

        return broadcaster;
    }

    private AtmosphereResource mockResource(String uuid, String transport,
                                            boolean suspended, boolean resumed) {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn(uuid);
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.valueOf(transport));
        when(resource.isSuspended()).thenReturn(suspended);
        when(resource.isResumed()).thenReturn(resumed);
        return resource;
    }
}
