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
import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.cpr.HeartbeatAtmosphereResourceEvent;
import org.atmosphere.util.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Map;
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

    // ── client heartbeat detection reads a bounded prefix of the body ──

    private AtmosphereResourceImpl post(AtmosphereRequest request) {
        interceptor.configure(framework.getAtmosphereConfig());
        interceptor.clientHeartbeatFrequencyInSeconds(10);
        var resource = mock(AtmosphereResourceImpl.class);
        when(resource.getRequest(false)).thenReturn(request);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getAtmosphereConfig()).thenReturn(framework.getAtmosphereConfig());
        return resource;
    }

    private static AtmosphereRequest streamed(InputStream body) {
        // X-Heartbeat-Server: 0 keeps the server heartbeat out of these tests.
        return new AtmosphereRequestImpl.Builder().method("POST")
                .headers(Map.of(HeaderConfig.X_HEARTBEAT_SERVER, "0"))
                .inputStream(body).build();
    }

    @Test
    void anEndlessPostIsNotReadPastTheHeartbeatPadding() throws Exception {
        // Endless, but it fails a read past 64 KiB rather than exhaust the heap
        // of a build where the interceptor reads the whole body again.
        var endless = new InputStream() {
            long served;

            @Override
            public int read() throws IOException {
                if (served > 65_536) {
                    throw new IOException("read past 64 KiB");
                }
                served++;
                return 'a';
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (served > 65_536) {
                    throw new IOException("read past 64 KiB");
                }
                Arrays.fill(b, off, off + len, (byte) 'a');
                served += len;
                return len;
            }
        };
        var request = streamed(endless);

        assertEquals(Action.CONTINUE, interceptor.inspect(post(request)));
        assertTrue(endless.served <= interceptor.getPaddingBytes().length + 1,
                "read " + endless.served + " bytes to look for a heartbeat");

        // The bytes it looked at are still there for the handler, ahead of the rest.
        var next = request.getInputStream().readNBytes(16);
        assertArrayEquals("a".repeat(16).getBytes(StandardCharsets.UTF_8), next);
    }

    @Test
    void aLargePostReachesTheHandlerWhole() throws Exception {
        var text = "0123456789".repeat(10_000);
        var request = streamed(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        var resource = post(request);

        assertEquals(Action.CONTINUE, interceptor.inspect(resource));
        assertTrue(request.body().isEmpty(), "a large body must not be cached by the interceptor");
        assertEquals(text, IOUtils.readEntirelyAsString(resource, 1 << 20));
    }

    @Test
    void aBodyShorterThanTheProbeIsCachedWhole() {
        var request = streamed(new ByteArrayInputStream("Y".getBytes(StandardCharsets.UTF_8)));

        assertEquals(Action.CONTINUE, interceptor.inspect(post(request)));
        assertTrue(request.body().hasBytes());
        assertEquals("Y", new String(request.body().asBytes(), request.body().byteOffset(),
                request.body().byteLength(), StandardCharsets.UTF_8));
    }

    @Test
    void aStreamedHeartbeatIsStillRecognised() {
        var resource = post(streamed(new ByteArrayInputStream("X".getBytes(StandardCharsets.UTF_8))));

        assertEquals(Action.CANCELLED, interceptor.inspect(resource));
        verify(resource).notifyListeners(Mockito.any(HeartbeatAtmosphereResourceEvent.class));
    }

    @Test
    void thePaddingFollowedByMoreIsNotAHeartbeat() throws Exception {
        var request = streamed(new ByteArrayInputStream("XX".getBytes(StandardCharsets.UTF_8)));

        assertEquals(Action.CONTINUE, interceptor.inspect(post(request)));
        assertArrayEquals("XX".getBytes(StandardCharsets.UTF_8), request.getInputStream().readAllBytes());
    }
}
