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

import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.HeaderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.Future;

import static org.atmosphere.cpr.ApplicationConfig.CLIENT_HEARTBEAT_INTERVAL_IN_SECONDS;
import static org.atmosphere.cpr.ApplicationConfig.FLUSH_BUFFER_HEARTBEAT;
import static org.atmosphere.cpr.ApplicationConfig.HEARTBEAT_INTERVAL_IN_SECONDS;
import static org.atmosphere.cpr.ApplicationConfig.HEARTBEAT_PADDING_CHAR;
import static org.atmosphere.cpr.ApplicationConfig.RESUME_ON_HEARTBEAT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeartbeatInterceptorTest {

    private HeartbeatInterceptor interceptor;
    private AtmosphereFramework framework;

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new HeartbeatInterceptor();
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(Mockito.mock(AsyncSupport.class));
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "test";
            }

            @Override
            public ServletContext getServletContext() {
                return Mockito.mock(ServletContext.class);
            }

            @Override
            public String getInitParameter(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return null;
            }
        });
    }

    @AfterEach
    void tearDown() {
        framework.destroy();
    }

    // ── Fluent setters and getters ──

    @Test
    void defaultHeartbeatFrequency() {
        assertEquals(60, interceptor.heartbeatFrequencyInSeconds());
    }

    @Test
    void setHeartbeatFrequency() {
        assertSame(interceptor, interceptor.heartbeatFrequencyInSeconds(30));
        assertEquals(30, interceptor.heartbeatFrequencyInSeconds());
    }

    @Test
    void defaultClientHeartbeatFrequency() {
        assertEquals(0, interceptor.clientHeartbeatFrequencyInSeconds());
    }

    @Test
    void setClientHeartbeatFrequency() {
        assertSame(interceptor, interceptor.clientHeartbeatFrequencyInSeconds(10));
        assertEquals(10, interceptor.clientHeartbeatFrequencyInSeconds());
    }

    @Test
    void defaultResumeOnHeartbeat() {
        assertFalse(interceptor.resumeOnHeartbeat());
    }

    @Test
    void setResumeOnHeartbeat() {
        assertSame(interceptor, interceptor.resumeOnHeartbeat(true));
        assertTrue(interceptor.resumeOnHeartbeat());
    }

    @Test
    void defaultPaddingBytes() {
        assertArrayEquals("X".getBytes(), interceptor.getPaddingBytes());
    }

    @Test
    void setPaddingText() {
        byte[] custom = "HB".getBytes(StandardCharsets.UTF_8);
        assertSame(interceptor, interceptor.paddingText(custom));
        assertArrayEquals(custom, interceptor.getPaddingBytes());
    }

    // ── Configuration via AtmosphereConfig ──

    @Test
    void configureHeartbeatInterval() {
        framework.addInitParameter(HEARTBEAT_INTERVAL_IN_SECONDS, "15");
        interceptor.configure(framework.getAtmosphereConfig());
        assertEquals(15, interceptor.heartbeatFrequencyInSeconds());
    }

    @Test
    void configurePaddingChar() {
        framework.addInitParameter(HEARTBEAT_PADDING_CHAR, "♥");
        interceptor.configure(framework.getAtmosphereConfig());
        assertArrayEquals("♥".getBytes(StandardCharsets.UTF_8), interceptor.getPaddingBytes());
    }

    @Test
    void configureClientHeartbeatInterval() {
        framework.addInitParameter(CLIENT_HEARTBEAT_INTERVAL_IN_SECONDS, "5");
        interceptor.configure(framework.getAtmosphereConfig());
        assertEquals(5, interceptor.clientHeartbeatFrequencyInSeconds());
    }

    @Test
    void configureResumeOnHeartbeatDefault() {
        interceptor.configure(framework.getAtmosphereConfig());
        assertTrue(interceptor.resumeOnHeartbeat());
    }

    @Test
    void configureResumeOnHeartbeatFalse() {
        framework.addInitParameter(RESUME_ON_HEARTBEAT, "false");
        interceptor.configure(framework.getAtmosphereConfig());
        assertFalse(interceptor.resumeOnHeartbeat());
    }

    @Test
    void configureFlushBufferDefault() {
        interceptor.configure(framework.getAtmosphereConfig());
        // Default is true; no direct getter, but verifiable through behavior
    }

    @Test
    void configureFlushBufferFalse() {
        framework.addInitParameter(FLUSH_BUFFER_HEARTBEAT, "false");
        interceptor.configure(framework.getAtmosphereConfig());
        // Should not throw during configuration
    }

    @Test
    void configureWithDefaults() {
        interceptor.configure(framework.getAtmosphereConfig());
        assertEquals(60, interceptor.heartbeatFrequencyInSeconds());
        assertEquals(0, interceptor.clientHeartbeatFrequencyInSeconds());
        assertArrayEquals("X".getBytes(), interceptor.getPaddingBytes());
    }

    // ── extractHeartbeatInterval ──

    @Test
    void extractHeartbeatIntervalFromHeader() {
        interceptor.configure(framework.getAtmosphereConfig());

        var resource = mock(AtmosphereResourceImpl.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest(false)).thenReturn(request);
        when(request.getHeader(HeaderConfig.X_HEARTBEAT_SERVER)).thenReturn("120");

        int interval = interceptor.extractHeartbeatInterval(resource);
        assertEquals(120, interval);
    }

    @Test
    void extractHeartbeatIntervalEnforcesMinimum() {
        interceptor.configure(framework.getAtmosphereConfig());

        var resource = mock(AtmosphereResourceImpl.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest(false)).thenReturn(request);
        // Request interval lower than configured → uses configured value
        when(request.getHeader(HeaderConfig.X_HEARTBEAT_SERVER)).thenReturn("10");

        int interval = interceptor.extractHeartbeatInterval(resource);
        assertEquals(60, interval);
    }

    @Test
    void extractHeartbeatIntervalZeroDisables() {
        interceptor.configure(framework.getAtmosphereConfig());

        var resource = mock(AtmosphereResourceImpl.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest(false)).thenReturn(request);
        when(request.getHeader(HeaderConfig.X_HEARTBEAT_SERVER)).thenReturn("0");

        int interval = interceptor.extractHeartbeatInterval(resource);
        assertEquals(0, interval);
    }

    @Test
    void extractHeartbeatIntervalNoHeader() {
        interceptor.configure(framework.getAtmosphereConfig());

        var resource = mock(AtmosphereResourceImpl.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest(false)).thenReturn(request);
        when(request.getHeader(HeaderConfig.X_HEARTBEAT_SERVER)).thenReturn(null);

        int interval = interceptor.extractHeartbeatInterval(resource);
        assertEquals(60, interval);
    }

    @Test
    void extractHeartbeatIntervalInvalidHeader() {
        interceptor.configure(framework.getAtmosphereConfig());

        var resource = mock(AtmosphereResourceImpl.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest(false)).thenReturn(request);
        when(request.getHeader(HeaderConfig.X_HEARTBEAT_SERVER)).thenReturn("not-a-number");

        int interval = interceptor.extractHeartbeatInterval(resource);
        assertEquals(60, interval);
    }

    // ── cancelF ──

    @Test
    @SuppressWarnings("unchecked")
    void cancelFCancelsFuture() {
        var request = AtmosphereRequestImpl.newInstance();
        var future = mock(Future.class);
        request.setAttribute(HeartbeatInterceptor.HEARTBEAT_FUTURE, future);

        interceptor.cancelF(request);

        verify(future).cancel(false);
    }

    @Test
    void cancelFHandlesNullFuture() {
        var request = AtmosphereRequestImpl.newInstance();
        // No future set — should not throw
        interceptor.cancelF(request);
    }

    @Test
    void cancelFHandlesMissingAttribute() {
        var request = AtmosphereRequestImpl.newInstance();
        request.setAttribute(HeartbeatInterceptor.HEARTBEAT_FUTURE, "not-a-future");
        // Wrong type — ClassCastException caught internally
        interceptor.cancelF(request);
    }

    // ── toString ──

    @Test
    void toStringDescribesInterceptor() {
        assertEquals("Heartbeat Interceptor Support", interceptor.toString());
    }

    // ── destroy idempotency ──

    @Test
    void destroyIsIdempotent() {
        interceptor.configure(framework.getAtmosphereConfig());
        interceptor.destroy();
        interceptor.destroy(); // should not throw
    }
}
