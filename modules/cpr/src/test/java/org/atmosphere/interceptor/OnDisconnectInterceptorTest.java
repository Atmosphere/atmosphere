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

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Enumeration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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

    /**
     * A close message whose page is going away (a reload, a navigation) marks the
     * event of the suspended resource it names, which the disconnect then reads
     * like a WebSocket closed with 1001; the close request's own event is not the
     * one dispatched. The mark is set before the event reports the client's
     * close: a disconnect read in between would otherwise see a leave.
     */
    @Test
    void aGoingAwayCloseMessageMarksTheSuspendedResourceEvent() {
        var suspended = mock(AtmosphereResourceImpl.class);
        var suspendedEvent = mock(AtmosphereResourceEventImpl.class);
        var closeRequestEvent = mock(AtmosphereResourceEventImpl.class);
        var asyncSupport = closeMessageFor(suspended, suspendedEvent, closeRequestEvent);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_CLOSE_REASON))
                .thenReturn(HeaderConfig.CLOSE_REASON_GOING_AWAY);

        assertEquals(Action.CANCELLED, interceptor.inspect(resource));

        var order = inOrder(suspendedEvent, asyncSupport);
        order.verify(suspendedEvent).closedGoingAway(true);
        order.verify(suspendedEvent).isClosedByClient(true);
        order.verify(asyncSupport).completeLifecycle(suspended, false);
        verify(closeRequestEvent, never()).closedGoingAway(anyBoolean());
    }

    /**
     * The disconnect may be read on another thread (the container cancelling the
     * aborted connection) as soon as the event reports the client's close: at that
     * instant the going-away mark must already be readable, or the reload is read
     * as a leave and cancelled without the grace.
     */
    @Test
    void theGoingAwayMarkIsReadableWhenTheClientCloseBecomesVisible() {
        var suspended = mock(AtmosphereResourceImpl.class);
        var suspendedEvent = spy(new AtmosphereResourceEventImpl(suspended, false, false));
        var goingAwayWhenClosed = new AtomicReference<Boolean>();
        doAnswer(inv -> {
            goingAwayWhenClosed.set(suspendedEvent.closedGoingAway());
            return inv.callRealMethod();
        }).when(suspendedEvent).isClosedByClient(true);
        closeMessageFor(suspended, suspendedEvent, mock(AtmosphereResourceEventImpl.class));
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_CLOSE_REASON))
                .thenReturn(HeaderConfig.CLOSE_REASON_GOING_AWAY);

        assertEquals(Action.CANCELLED, interceptor.inspect(resource));

        assertEquals(Boolean.TRUE, goingAwayWhenClosed.get(),
                "a reader that sees the client's close must see the page going away too");
        assertTrue(suspendedEvent.isClosedByClient());
        assertTrue(suspendedEvent.closedGoingAway());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "left", "going-away-not"})
    void aCloseMessageWithoutTheGoingAwayReasonIsALeave(String reason) {
        var suspended = mock(AtmosphereResourceImpl.class);
        var suspendedEvent = mock(AtmosphereResourceEventImpl.class);
        var asyncSupport = closeMessageFor(suspended, suspendedEvent, mock(AtmosphereResourceEventImpl.class));
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_CLOSE_REASON)).thenReturn(reason);

        assertEquals(Action.CANCELLED, interceptor.inspect(resource));

        verify(suspendedEvent).isClosedByClient(true);
        verify(suspendedEvent, never()).closedGoingAway(true);
        verify(asyncSupport).completeLifecycle(suspended, false);
    }

    /**
     * The close message as atmosphere.js sends it: {@code navigator.sendBeacon}
     * cannot set headers, so everything, the reason included, rides in the query
     * string, which the framework turns into headers before the interceptor runs.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theCloseReasonOfTheBeaconQueryStringReachesTheEvent(boolean goingAway) throws Exception {
        var framework = new AtmosphereFramework();
        framework.setAsyncSupport(new BlockingIOCometSupport(framework.getAtmosphereConfig()));
        framework.init(servletConfig());
        try {
            var query = "X-Atmosphere-tracking-id=suspended-uuid&X-Atmosphere-Framework=5.0.32"
                    + "&X-Atmosphere-Transport=close"
                    + (goingAway ? "&X-Atmosphere-Close-Reason=going-away" : "");
            AtmosphereRequest beacon = new AtmosphereRequestImpl.Builder()
                    .method("POST").pathInfo("/chat").queryString(query).build();
            framework.configureRequestResponse(beacon, AtmosphereResponseImpl.newInstance());
            assertEquals("", beacon.getQueryString(), "the X-Atmosphere parameters are moved to headers");

            var suspended = mock(AtmosphereResourceImpl.class);
            var suspendedEvent = new AtmosphereResourceEventImpl(suspended, false, false);
            var asyncSupport = closeMessageFor(suspended, suspendedEvent, mock(AtmosphereResourceEventImpl.class));
            when(resource.getRequest(false)).thenReturn(beacon);

            assertEquals(Action.CANCELLED, interceptor.inspect(resource));

            assertTrue(suspendedEvent.isClosedByClient());
            assertEquals(goingAway, suspendedEvent.closedGoingAway(),
                    goingAway ? "the beacon's going-away reason marks the event"
                            : "a close message without a reason is a leave");
            verify(asyncSupport).completeLifecycle(suspended, false);
        } finally {
            framework.destroy();
        }
    }

    private static ServletConfig servletConfig() {
        return new ServletConfig() {
            @Override
            public String getServletName() {
                return "void";
            }

            @Override
            public ServletContext getServletContext() {
                return mock(ServletContext.class);
            }

            @Override
            public String getInitParameter(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return null;
            }
        };
    }

    /** A close message from {@link #resource} naming the distinct {@code suspended} resource. */
    private AsynchronousProcessor closeMessageFor(AtmosphereResourceImpl suspended,
            AtmosphereResourceEventImpl suspendedEvent, AtmosphereResourceEventImpl closeRequestEvent) {
        var factory = mock(AtmosphereResourceFactory.class);
        var asyncSupport = mock(AsynchronousProcessor.class);
        var framework = mock(AtmosphereFramework.class);
        when(resource.getRequest(false)).thenReturn(request);
        when(request.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE)).thenReturn(null);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT))
                .thenReturn(HeaderConfig.DISCONNECT_TRANSPORT_MESSAGE);
        when(resource.uuid()).thenReturn("suspended-uuid");
        when(resource.getAtmosphereResourceEvent()).thenReturn(closeRequestEvent);
        when(config.resourcesFactory()).thenReturn(factory);
        when(factory.findResource("suspended-uuid")).thenReturn(Optional.of(suspended));
        when(suspended.getAtmosphereResourceEvent()).thenReturn(suspendedEvent);
        when(config.framework()).thenReturn(framework);
        doReturn(asyncSupport).when(framework).getAsyncSupport();
        return asyncSupport;
    }

    @Test
    void toStringReturnsDescription() {
        assertEquals("Browser disconnection detection", interceptor.toString());
    }
}
