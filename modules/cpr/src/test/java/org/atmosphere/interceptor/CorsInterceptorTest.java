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
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorsInterceptorTest {

    private CorsInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new CorsInterceptor();
    }

    @Test
    void priorityIsFirstBeforeDefault() {
        assertEquals(InvokationOrder.PRIORITY.FIRST_BEFORE_DEFAULT,
                interceptor.priority());
    }

    @Test
    void enableAccessControlDefaultsTrue() {
        assertTrue(interceptor.enableAccessControl());
    }

    @Test
    void enableAccessControlFluentApi() {
        var result = interceptor.enableAccessControl(false);
        assertFalse(interceptor.enableAccessControl());
        assertEquals(interceptor, result);
    }

    @Test
    void configureDisablesAccessControlWhenHeaderDropped() {
        var config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.DROP_ACCESS_CONTROL_ALLOW_ORIGIN_HEADER))
                .thenReturn("true");
        when(config.getInitParameter(ApplicationConfig.CORS_ALLOWED_ORIGINS))
                .thenReturn(null);

        interceptor.configure(config);
        assertFalse(interceptor.enableAccessControl());
    }

    @Test
    void inspectReturnsContinueWhenAccessControlDisabled() {
        interceptor.enableAccessControl(false);
        var resource = mockResource("GET", "http://example.com", null);
        assertEquals(Action.CONTINUE, interceptor.inspect(resource));
    }

    @Test
    void inspectSetsHeadersForAllowedOrigin() {
        var config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.DROP_ACCESS_CONTROL_ALLOW_ORIGIN_HEADER))
                .thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.CORS_ALLOWED_ORIGINS))
                .thenReturn("http://allowed.com, http://other.com");
        interceptor.configure(config);

        var response = mock(AtmosphereResponse.class);
        when(response.getHeader("Access-Control-Allow-Origin")).thenReturn(null);
        var resource = mockResourceWithResponse("GET", "http://allowed.com", response);

        var action = interceptor.inspect(resource);
        assertEquals(Action.CONTINUE, action);
        verify(response).addHeader("Access-Control-Allow-Origin", "http://allowed.com");
        verify(response).setHeader("Access-Control-Allow-Credentials", "true");
    }

    @Test
    void inspectRejectsDisallowedOrigin() {
        var config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.DROP_ACCESS_CONTROL_ALLOW_ORIGIN_HEADER))
                .thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.CORS_ALLOWED_ORIGINS))
                .thenReturn("http://allowed.com");
        interceptor.configure(config);

        var response = mock(AtmosphereResponse.class);
        when(response.getHeader("Access-Control-Allow-Origin")).thenReturn(null);
        var resource = mockResourceWithResponse("GET", "http://evil.com", response);

        interceptor.inspect(resource);
        verify(response, never()).addHeader("Access-Control-Allow-Origin", "http://evil.com");
    }

    @Test
    void inspectEchoesOriginWhenNoAllowlistConfigured() {
        // No configure() call — no allowlist set
        var response = mock(AtmosphereResponse.class);
        when(response.getHeader("Access-Control-Allow-Origin")).thenReturn(null);
        var resource = mockResourceWithResponse("GET", "http://any-origin.com", response);

        interceptor.inspect(resource);
        verify(response).addHeader("Access-Control-Allow-Origin", "http://any-origin.com");
    }

    @Test
    void inspectSkipsWhenOriginHeaderAlreadySet() {
        var response = mock(AtmosphereResponse.class);
        when(response.getHeader("Access-Control-Allow-Origin")).thenReturn("http://existing.com");
        var resource = mockResourceWithResponse("GET", "http://example.com", response);

        interceptor.inspect(resource);
        verify(response, never()).addHeader("Access-Control-Allow-Origin", "http://example.com");
    }

    @Test
    void inspectSkipsWhenNoOriginHeader() {
        var response = mock(AtmosphereResponse.class);
        var resource = mockResourceWithResponse("GET", null, response);

        var action = interceptor.inspect(resource);
        assertEquals(Action.CONTINUE, action);
        verify(response, never()).addHeader("Access-Control-Allow-Origin", (String) null);
    }

    @Test
    void inspectOptionsReturnsSKIP() {
        var response = mock(AtmosphereResponse.class);
        when(response.getHeader("Access-Control-Allow-Origin")).thenReturn(null);
        var resource = mockResourceWithResponse("OPTIONS", "http://example.com", response);

        var action = interceptor.inspect(resource);
        assertEquals(Action.SKIP_ATMOSPHEREHANDLER, action);
        verify(response).setHeader("Access-Control-Allow-Methods", "OPTIONS, GET, POST");
        verify(response).setHeader("Access-Control-Max-Age", "-1");
    }

    @Test
    void inspectReturnsContinueForWebSocketMessage() {
        var resource = mock(AtmosphereResourceImpl.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getRequest(false)).thenReturn(request);
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.WEBSOCKET);
        when(request.getAttribute("websocket.isWebSocketRequest")).thenReturn(Boolean.TRUE);
        when(request.getHeader("Connection")).thenReturn(null);

        var action = interceptor.inspect(resource);
        assertEquals(Action.CONTINUE, action);
    }

    @Test
    void toStringContainsCORS() {
        assertTrue(interceptor.toString().contains("CORS"));
    }

    @Test
    void configureWithBlankOriginsIgnoresAllowlist() {
        var config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.DROP_ACCESS_CONTROL_ALLOW_ORIGIN_HEADER))
                .thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.CORS_ALLOWED_ORIGINS))
                .thenReturn("  ");
        interceptor.configure(config);

        // Should echo origins (no allowlist set)
        var response = mock(AtmosphereResponse.class);
        when(response.getHeader("Access-Control-Allow-Origin")).thenReturn(null);
        var resource = mockResourceWithResponse("GET", "http://any.com", response);
        interceptor.inspect(resource);
        verify(response).addHeader("Access-Control-Allow-Origin", "http://any.com");
    }

    private AtmosphereResource mockResource(String method, String origin, String existingHeader) {
        var response = mock(AtmosphereResponse.class);
        when(response.getHeader("Access-Control-Allow-Origin")).thenReturn(existingHeader);
        return mockResourceWithResponse(method, origin, response);
    }

    private AtmosphereResource mockResourceWithResponse(String method, String origin,
                                                        AtmosphereResponse response) {
        var resource = mock(AtmosphereResourceImpl.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getRequest(false)).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(request.getMethod()).thenReturn(method);
        when(request.getHeader("Origin")).thenReturn(origin);
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.LONG_POLLING);
        return resource;
    }
}
