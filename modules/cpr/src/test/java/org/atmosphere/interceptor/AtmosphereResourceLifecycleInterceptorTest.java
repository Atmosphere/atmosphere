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
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.atmosphere.cpr.ApplicationConfig.ATMOSPHERERESOURCE_INTERCEPTOR_METHOD;
import static org.atmosphere.cpr.ApplicationConfig.ATMOSPHERERESOURCE_INTERCEPTOR_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AtmosphereResourceLifecycleInterceptorTest {

    private AtmosphereResourceLifecycleInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AtmosphereResourceLifecycleInterceptor();
    }

    @Test
    void defaultMethodIsGet() {
        assertEquals("GET", interceptor.method());
    }

    @Test
    void defaultTimeoutIsNegativeOne() {
        assertEquals(-1, interceptor.timeoutInSeconds());
    }

    @Test
    void methodSetterReturnsSelf() {
        AtmosphereResourceLifecycleInterceptor result = interceptor.method("POST");
        assertSame(interceptor, result);
        assertEquals("POST", interceptor.method());
    }

    @Test
    void timeoutSetterReturnsSelf() {
        AtmosphereResourceLifecycleInterceptor result = interceptor.timeoutInSeconds(30);
        assertSame(interceptor, result);
        assertEquals(30, interceptor.timeoutInSeconds());
    }

    @Test
    void configureReadsMethodParameter() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ATMOSPHERERESOURCE_INTERCEPTOR_METHOD)).thenReturn("POST");
        when(config.getInitParameter(ATMOSPHERERESOURCE_INTERCEPTOR_TIMEOUT)).thenReturn(null);

        interceptor.configure(config);
        assertEquals("POST", interceptor.method());
    }

    @Test
    void configureReadsTimeoutParameter() {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ATMOSPHERERESOURCE_INTERCEPTOR_METHOD)).thenReturn(null);
        when(config.getInitParameter(ATMOSPHERERESOURCE_INTERCEPTOR_TIMEOUT)).thenReturn("60");

        interceptor.configure(config);
        assertEquals(60, interceptor.timeoutInSeconds());
    }

    @Test
    void inspectSetsResumeOnBroadcastForAjaxTransport() {
        AtmosphereResourceImpl r = mock(AtmosphereResourceImpl.class);
        when(r.transport()).thenReturn(AtmosphereResource.TRANSPORT.AJAX);

        Action result = interceptor.inspect(r);
        assertEquals(Action.CONTINUE, result);
        verify(r).resumeOnBroadcast(true);
    }

    @Test
    void inspectSetsResumeOnBroadcastForLongPolling() {
        AtmosphereResourceImpl r = mock(AtmosphereResourceImpl.class);
        when(r.transport()).thenReturn(AtmosphereResource.TRANSPORT.LONG_POLLING);

        Action result = interceptor.inspect(r);
        assertEquals(Action.CONTINUE, result);
        verify(r).resumeOnBroadcast(true);
    }

    @Test
    void inspectReturnsContinueForStreaming() {
        AtmosphereResourceImpl r = mock(AtmosphereResourceImpl.class);
        when(r.transport()).thenReturn(AtmosphereResource.TRANSPORT.STREAMING);

        Action result = interceptor.inspect(r);
        assertEquals(Action.CONTINUE, result);
    }

    @Test
    void toStringReturnsExpected() {
        assertEquals("Atmosphere LifeCycle", interceptor.toString());
    }

    @Test
    void destroyDoesNotThrow() {
        interceptor.destroy();
    }
}
