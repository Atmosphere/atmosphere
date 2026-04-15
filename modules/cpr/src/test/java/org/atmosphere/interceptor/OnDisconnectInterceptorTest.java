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
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnDisconnectInterceptorTest {

    private OnDisconnectInterceptor interceptor;
    private AtmosphereConfig config;
    private AtmosphereResourceImpl resource;
    private AtmosphereRequest request;

    @BeforeEach
    void setUp() {
        interceptor = new OnDisconnectInterceptor();
        config = mock(AtmosphereConfig.class);
        resource = mock(AtmosphereResourceImpl.class);
        request = mock(AtmosphereRequest.class);
        interceptor.configure(config);
    }

    @Test
    void inspectReturnsContinueForWebSocketMessage() {
        when(resource.getRequest(false)).thenReturn(request);
        when(request.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE)).thenReturn("ws-msg");

        Action result = interceptor.inspect(resource);

        assertEquals(Action.CONTINUE, result);
    }

    @Test
    void inspectReturnsContinueForNonCloseMessage() {
        when(resource.getRequest(false)).thenReturn(request);
        when(request.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE)).thenReturn(null);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT)).thenReturn("long-polling");
        when(resource.uuid()).thenReturn("test-uuid");

        Action result = interceptor.inspect(resource);

        assertEquals(Action.CONTINUE, result);
    }

    @Test
    void inspectReturnsCancelledWhenCloseAndNullResourcesFactory() {
        when(resource.getRequest(false)).thenReturn(request);
        when(request.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE)).thenReturn(null);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT))
                .thenReturn(HeaderConfig.DISCONNECT_TRANSPORT_MESSAGE);
        when(resource.uuid()).thenReturn("test-uuid");
        when(config.resourcesFactory()).thenReturn(null);

        Action result = interceptor.inspect(resource);

        assertEquals(Action.CANCELLED, result);
    }

    @Test
    void inspectReturnsCancelledWhenCloseAndNullUuid() {
        when(resource.getRequest(false)).thenReturn(request);
        when(request.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE)).thenReturn(null);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT))
                .thenReturn(HeaderConfig.DISCONNECT_TRANSPORT_MESSAGE);
        when(resource.uuid()).thenReturn(null);
        when(config.resourcesFactory()).thenReturn(mock(AtmosphereResourceFactory.class));

        Action result = interceptor.inspect(resource);

        assertEquals(Action.CANCELLED, result);
    }

    @Test
    void inspectCompletesLifecycleOnCloseWithAsyncProcessor() {
        var factory = mock(AtmosphereResourceFactory.class);
        var event = mock(AtmosphereResourceEventImpl.class);
        var asyncSupport = mock(AsynchronousProcessor.class);
        var framework = mock(AtmosphereFramework.class);

        when(resource.getRequest(false)).thenReturn(request);
        when(request.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE)).thenReturn(null);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT))
                .thenReturn(HeaderConfig.DISCONNECT_TRANSPORT_MESSAGE);
        when(resource.uuid()).thenReturn("test-uuid");
        when(config.resourcesFactory()).thenReturn(factory);
        when(factory.findResource("test-uuid")).thenReturn(Optional.of(resource));
        when(resource.getAtmosphereResourceEvent()).thenReturn(event);
        when(config.framework()).thenReturn(framework);
        doReturn(asyncSupport).when(framework).getAsyncSupport();

        Action result = interceptor.inspect(resource);

        assertEquals(Action.CANCELLED, result);
        verify(event).isClosedByClient(true);
        verify(asyncSupport).completeLifecycle(resource, false);
    }

    @Test
    void inspectFallsBackToCurrentResourceWhenNotFound() {
        var factory = mock(AtmosphereResourceFactory.class);
        var event = mock(AtmosphereResourceEventImpl.class);
        var framework = mock(AtmosphereFramework.class);

        when(resource.getRequest(false)).thenReturn(request);
        when(request.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE)).thenReturn(null);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT))
                .thenReturn(HeaderConfig.DISCONNECT_TRANSPORT_MESSAGE);
        when(resource.uuid()).thenReturn("missing-uuid");
        when(config.resourcesFactory()).thenReturn(factory);
        when(factory.findResource("missing-uuid")).thenReturn(Optional.empty());
        when(resource.getAtmosphereResourceEvent()).thenReturn(event);
        when(config.framework()).thenReturn(framework);
        doReturn(mock(AsyncSupport.class)).when(framework).getAsyncSupport();

        Action result = interceptor.inspect(resource);

        assertEquals(Action.CANCELLED, result);
        verify(event).isClosedByClient(true);
    }

    @Test
    void toStringReturnsDescription() {
        assertEquals("Browser disconnection detection", interceptor.toString());
    }
}
