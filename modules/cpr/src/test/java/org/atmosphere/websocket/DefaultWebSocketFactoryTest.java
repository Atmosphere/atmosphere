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
package org.atmosphere.websocket;

import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultWebSocketFactoryTest {

    private DefaultWebSocketFactory factory;
    private AtmosphereResourceFactory resourceFactory;

    @BeforeEach
    void setUp() throws Exception {
        factory = new DefaultWebSocketFactory();
        resourceFactory = mock(AtmosphereResourceFactory.class);
        injectField(factory, "factory", resourceFactory);
    }

    @SuppressWarnings("deprecation")
    @Test
    void findReturnsWebSocketWhenResourceExists() {
        var resource = mock(AtmosphereResourceImpl.class);
        var webSocket = mock(WebSocket.class);
        when(resourceFactory.find("test-uuid")).thenReturn(resource);
        when(resource.webSocket()).thenReturn(webSocket);

        assertSame(webSocket, factory.find("test-uuid"));
    }

    @SuppressWarnings("deprecation")
    @Test
    void findReturnsNullWhenResourceNotFound() {
        when(resourceFactory.find("missing")).thenReturn(null);

        assertNull(factory.find("missing"));
    }

    @Test
    void findWebSocketReturnsOptionalWithWebSocket() {
        var resource = mock(AtmosphereResourceImpl.class);
        var webSocket = mock(WebSocket.class);
        when(resourceFactory.findResource("ws-uuid"))
                .thenReturn(Optional.of(resource));
        when(resource.webSocket()).thenReturn(webSocket);

        Optional<WebSocket> result = factory.findWebSocket("ws-uuid");
        assertTrue(result.isPresent());
        assertSame(webSocket, result.get());
    }

    @Test
    void findWebSocketReturnsEmptyWhenResourceNotFound() {
        when(resourceFactory.findResource("absent"))
                .thenReturn(Optional.empty());

        Optional<WebSocket> result = factory.findWebSocket("absent");
        assertEquals(Optional.empty(), result);
    }

    @Test
    void findWebSocketReturnsEmptyWhenWebSocketIsNull() {
        var resource = mock(AtmosphereResourceImpl.class);
        when(resourceFactory.findResource("no-ws"))
                .thenReturn(Optional.of(resource));
        when(resource.webSocket()).thenReturn(null);

        Optional<WebSocket> result = factory.findWebSocket("no-ws");
        // Optional.ofNullable(null) in the map will produce empty
        assertEquals(Optional.empty(), result);
    }

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
