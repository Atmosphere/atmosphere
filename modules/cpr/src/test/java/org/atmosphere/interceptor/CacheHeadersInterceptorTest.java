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
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.FrameworkConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheHeadersInterceptorTest {

    private CacheHeadersInterceptor interceptor;
    private AtmosphereConfig config;

    @BeforeEach
    void setUp() {
        interceptor = new CacheHeadersInterceptor();
        config = mock(AtmosphereConfig.class);
    }

    @Test
    void configureDefaultsEnableCacheHeaders() {
        when(config.getInitParameter(ApplicationConfig.NO_CACHE_HEADERS)).thenReturn(null);
        when(config.getInitParameter(FrameworkConfig.WRITE_HEADERS)).thenReturn(null);
        interceptor.configure(config);
        assertTrue(interceptor.injectCacheHeaders());
        assertTrue(interceptor.writeHeaders());
    }

    @Test
    void configureDisablesCacheHeadersWhenSet() {
        when(config.getInitParameter(ApplicationConfig.NO_CACHE_HEADERS)).thenReturn("true");
        when(config.getInitParameter(FrameworkConfig.WRITE_HEADERS)).thenReturn(null);
        interceptor.configure(config);
        assertFalse(interceptor.injectCacheHeaders());
    }

    @Test
    void configureDisablesWriteHeaders() {
        when(config.getInitParameter(ApplicationConfig.NO_CACHE_HEADERS)).thenReturn(null);
        when(config.getInitParameter(FrameworkConfig.WRITE_HEADERS)).thenReturn("false");
        interceptor.configure(config);
        assertFalse(interceptor.writeHeaders());
    }

    private AtmosphereResourceImpl mockResource() {
        var resource = mock(AtmosphereResourceImpl.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.getResponse()).thenReturn(response);
        when(resource.getRequest()).thenReturn(request);
        // Utils.webSocketMessage() casts to AtmosphereResourceImpl and calls getRequest(false)
        when(resource.getRequest(false)).thenReturn(request);
        when(request.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE)).thenReturn(null);
        return resource;
    }

    @Test
    void inspectSetsHeadersWhenEnabled() {
        interceptor.injectCacheHeaders(true).writeHeaders(true);
        var resource = mockResource();

        var action = interceptor.inspect(resource);
        assertEquals(Action.TYPE.CONTINUE, action.type());
        verify(resource.getResponse()).setHeader("Expires", "-1");
        verify(resource.getResponse()).setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        verify(resource.getResponse()).setHeader("Pragma", "no-cache");
    }

    @Test
    void inspectSkipsHeadersWhenDisabled() {
        interceptor.injectCacheHeaders(false).writeHeaders(true);
        var resource = mockResource();

        interceptor.inspect(resource);
        verify(resource.getResponse(), never()).setHeader("Expires", "-1");
    }

    @Test
    void inspectSkipsHeadersWhenWriteHeadersFalse() {
        interceptor.injectCacheHeaders(true).writeHeaders(false);
        var resource = mockResource();

        interceptor.inspect(resource);
        verify(resource.getResponse(), never()).setHeader("Expires", "-1");
    }

    @Test
    void fluentSetters() {
        var result = interceptor.injectCacheHeaders(false).writeHeaders(false);
        assertFalse(result.injectCacheHeaders());
        assertFalse(result.writeHeaders());
    }

    @Test
    void toStringReturnsDescriptiveName() {
        assertEquals("Default Response's Headers Interceptor", interceptor.toString());
    }
}
