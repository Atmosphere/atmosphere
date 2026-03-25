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
package org.atmosphere.config.managed;

import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Heartbeat;
import org.atmosphere.config.service.PathParam;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link AnnotatedLifecycle} — validates annotation scanning,
 * heartbeat support, and lifecycle invocation.
 */
public class AnnotatedLifecycleTest {

    // ── Test endpoint classes ──

    /**
     * Endpoint with all lifecycle annotations: @Ready, @Disconnect, @Heartbeat.
     */
    public static class FullEndpoint {
        final AtomicBoolean readyCalled = new AtomicBoolean();
        final AtomicBoolean disconnectCalled = new AtomicBoolean();
        final AtomicBoolean heartbeatCalled = new AtomicBoolean();
        final AtomicReference<AtmosphereResourceEvent> lastHeartbeatEvent = new AtomicReference<>();

        @Ready
        public void onReady(AtmosphereResource resource) {
            readyCalled.set(true);
        }

        @Disconnect
        public void onDisconnect(AtmosphereResourceEvent event) {
            disconnectCalled.set(true);
        }

        @Heartbeat
        public void onHeartbeat(AtmosphereResourceEvent event) {
            heartbeatCalled.set(true);
            lastHeartbeatEvent.set(event);
        }
    }

    /**
     * Endpoint with only @Heartbeat — no @Ready or @Disconnect.
     */
    public static class HeartbeatOnlyEndpoint {
        final AtomicBoolean heartbeatCalled = new AtomicBoolean();

        @Heartbeat
        public void onHeartbeat() {
            heartbeatCalled.set(true);
        }
    }

    /**
     * Endpoint with no lifecycle annotations at all.
     */
    public static class BareEndpoint {
        public void someMethod() {
            // not annotated
        }
    }

    /**
     * Endpoint with @Ready and @Disconnect but no @Heartbeat.
     */
    public static class NoHeartbeatEndpoint {
        final AtomicBoolean readyCalled = new AtomicBoolean();

        @Ready
        public void onReady() {
            readyCalled.set(true);
        }

        @Disconnect
        public void onDisconnect() {
            // no-op
        }
    }

    /**
     * Endpoint with a @PathParam field.
     */
    public static class PathParamEndpoint {
        @PathParam("room")
        String room;

        @Ready
        public void onReady() {
            // no-op
        }
    }

    // ── scan() tests ──

    @Test
    public void testScanFindsAllAnnotations() {
        var lifecycle = AnnotatedLifecycle.scan(FullEndpoint.class);

        assertNotNull(lifecycle.readyMethod(), "Expected @Ready method to be found");
        assertNotNull(lifecycle.disconnectMethod(), "Expected @Disconnect method to be found");
        assertNotNull(lifecycle.heartbeatMethod(), "Expected @Heartbeat method to be found");
    }

    @Test
    public void testScanFindsHeartbeatOnly() {
        var lifecycle = AnnotatedLifecycle.scan(HeartbeatOnlyEndpoint.class);

        assertNull(lifecycle.readyMethod(), "Should not find @Ready");
        assertNull(lifecycle.disconnectMethod(), "Should not find @Disconnect");
        assertNotNull(lifecycle.heartbeatMethod(), "Expected @Heartbeat method to be found");
    }

    @Test
    public void testScanBareEndpointFindsNothing() {
        var lifecycle = AnnotatedLifecycle.scan(BareEndpoint.class);

        assertNull(lifecycle.readyMethod());
        assertNull(lifecycle.disconnectMethod());
        assertNull(lifecycle.heartbeatMethod());
        assertFalse(lifecycle.hasPathParams());
    }

    @Test
    public void testScanNoHeartbeat() {
        var lifecycle = AnnotatedLifecycle.scan(NoHeartbeatEndpoint.class);

        assertNotNull(lifecycle.readyMethod());
        assertNotNull(lifecycle.disconnectMethod());
        assertNull(lifecycle.heartbeatMethod(), "@Heartbeat should not be found");
    }

    @Test
    public void testScanDetectsPathParams() {
        var lifecycle = AnnotatedLifecycle.scan(PathParamEndpoint.class);

        assertTrue(lifecycle.hasPathParams(), "Expected @PathParam field to be detected");
    }

    @Test
    public void testScanNoPathParams() {
        var lifecycle = AnnotatedLifecycle.scan(FullEndpoint.class);

        assertFalse(lifecycle.hasPathParams(), "FullEndpoint has no @PathParam fields");
    }

    // ── onHeartbeat() invocation tests ──

    @Test
    public void testOnHeartbeatInvokesAnnotatedMethod() {
        var lifecycle = AnnotatedLifecycle.scan(FullEndpoint.class);
        var endpoint = new FullEndpoint();
        var event = mock(AtmosphereResourceEvent.class);

        lifecycle.onHeartbeat(endpoint, event);

        assertTrue(endpoint.heartbeatCalled.get(), "@Heartbeat method should have been invoked");
        assertSame(event, endpoint.lastHeartbeatEvent.get(),
                "Event passed to @Heartbeat should be the same mock event");
    }

    @Test
    public void testOnHeartbeatNoArgMethod() {
        var lifecycle = AnnotatedLifecycle.scan(HeartbeatOnlyEndpoint.class);
        var endpoint = new HeartbeatOnlyEndpoint();
        var event = mock(AtmosphereResourceEvent.class);

        lifecycle.onHeartbeat(endpoint, event);

        assertTrue(endpoint.heartbeatCalled.get(),
                "@Heartbeat with no parameters should still be invoked");
    }

    @Test
    public void testOnHeartbeatNoMethodDoesNotThrow() {
        var lifecycle = AnnotatedLifecycle.scan(BareEndpoint.class);
        var endpoint = new BareEndpoint();
        var event = mock(AtmosphereResourceEvent.class);

        // Should not throw even though there's no @Heartbeat method
        assertDoesNotThrow(() -> lifecycle.onHeartbeat(endpoint, event));
    }

    // ── onReady() invocation tests ──

    @Test
    public void testOnReadyInvokesAnnotatedMethod() {
        var lifecycle = AnnotatedLifecycle.scan(FullEndpoint.class);
        var endpoint = new FullEndpoint();
        var resource = mock(AtmosphereResource.class);

        lifecycle.onReady(endpoint, resource);

        assertTrue(endpoint.readyCalled.get(), "@Ready method should have been invoked");
    }

    @Test
    public void testOnReadyNoMethodDoesNotThrow() {
        var lifecycle = AnnotatedLifecycle.scan(BareEndpoint.class);
        var endpoint = new BareEndpoint();
        var resource = mock(AtmosphereResource.class);

        assertDoesNotThrow(() -> lifecycle.onReady(endpoint, resource));
    }

    // ── onDisconnect() invocation tests ──

    @Test
    public void testOnDisconnectInvokesAnnotatedMethod() {
        var lifecycle = AnnotatedLifecycle.scan(FullEndpoint.class);
        var endpoint = new FullEndpoint();
        var event = mock(AtmosphereResourceEvent.class);

        lifecycle.onDisconnect(endpoint, event);

        assertTrue(endpoint.disconnectCalled.get(), "@Disconnect method should have been invoked");
    }

    // ── heartbeatMethod() accessor test ──

    @Test
    public void testHeartbeatMethodReturnsCorrectMethod() throws Exception {
        var lifecycle = AnnotatedLifecycle.scan(FullEndpoint.class);
        var expected = FullEndpoint.class.getMethod("onHeartbeat", AtmosphereResourceEvent.class);

        assertEquals(expected, lifecycle.heartbeatMethod());
    }

    @Test
    public void testHeartbeatMethodReturnsNullWhenAbsent() {
        var lifecycle = AnnotatedLifecycle.scan(NoHeartbeatEndpoint.class);

        assertNull(lifecycle.heartbeatMethod());
    }

    // ── findMethod() static helper test ──

    @Test
    public void testFindMethodReturnsAnnotatedMethod() {
        var method = AnnotatedLifecycle.findMethod(FullEndpoint.class, Heartbeat.class);

        assertNotNull(method);
        assertEquals("onHeartbeat", method.getName());
    }

    @Test
    public void testFindMethodReturnsNullWhenNotPresent() {
        var method = AnnotatedLifecycle.findMethod(BareEndpoint.class, Heartbeat.class);

        assertNull(method);
    }

    // ── hasPathParamFields() static helper test ──

    @Test
    public void testHasPathParamFieldsTrue() {
        assertTrue(AnnotatedLifecycle.hasPathParamFields(PathParamEndpoint.class));
    }

    @Test
    public void testHasPathParamFieldsFalse() {
        assertFalse(AnnotatedLifecycle.hasPathParamFields(FullEndpoint.class));
    }
}
