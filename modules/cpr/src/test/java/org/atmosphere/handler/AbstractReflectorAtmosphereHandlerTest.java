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
package org.atmosphere.handler;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Serializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.servlet.ServletOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractReflectorAtmosphereHandlerTest {

    private AtmosphereResourceEvent event;
    private AtmosphereResourceImpl resource;
    private AtmosphereResponse response;
    private AtmosphereRequest request;
    private ServletOutputStream outputStream;
    private AbstractReflectorAtmosphereHandler.Default handler;

    @BeforeEach
    void setUp() {
        event = mock(AtmosphereResourceEvent.class);
        resource = mock(AtmosphereResourceImpl.class);
        response = mock(AtmosphereResponse.class);
        request = mock(AtmosphereRequest.class);
        outputStream = mock(ServletOutputStream.class);
        handler = new AbstractReflectorAtmosphereHandler.Default();
    }

    @Test
    void onRequestDoesNothing() throws IOException {
        assertDoesNotThrow(() -> handler.onRequest(mock(AtmosphereResource.class)));
    }

    @Test
    void destroyDoesNothing() {
        assertDoesNotThrow(handler::destroy);
    }

    @Test
    void onStateChangeNullMessageReturnsEarly() throws IOException {
        when(event.getMessage()).thenReturn(null);
        when(event.getResource()).thenReturn(resource);
        when(resource.getResponse()).thenReturn(response);
        when(resource.getRequest()).thenReturn(request);

        handler.onStateChange(event);

        verify(response, never()).getOutputStream();
    }

    @Test
    void onStateChangeStringWithStream() throws IOException {
        when(event.getMessage()).thenReturn("hello");
        when(event.getResource()).thenReturn(resource);
        when(resource.getResponse()).thenReturn(response);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getSerializer()).thenReturn(null);
        when(request.getAttribute(ApplicationConfig.PROPERTY_USE_STREAM)).thenReturn(true);
        when(response.getOutputStream()).thenReturn(outputStream);
        when(response.getCharacterEncoding()).thenReturn("UTF-8");
        when(event.isCancelled()).thenReturn(true);

        handler.onStateChange(event);

        verify(outputStream).write("hello".getBytes("UTF-8"));
        verify(outputStream).flush();
    }

    @Test
    void onStateChangeStringWithWriter() throws IOException {
        var sw = new StringWriter();
        var writer = new PrintWriter(sw);

        when(event.getMessage()).thenReturn("world");
        when(event.getResource()).thenReturn(resource);
        when(resource.getResponse()).thenReturn(response);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getSerializer()).thenReturn(null);
        when(request.getAttribute(ApplicationConfig.PROPERTY_USE_STREAM)).thenReturn(false);
        when(response.getWriter()).thenReturn(writer);
        when(event.isCancelled()).thenReturn(true);

        handler.onStateChange(event);

        writer.flush();
        assert sw.toString().contains("world");
    }

    @Test
    void onStateChangeWithSerializer() throws IOException {
        var serializer = mock(Serializer.class);

        when(event.getMessage()).thenReturn("data");
        when(event.getResource()).thenReturn(resource);
        when(resource.getResponse()).thenReturn(response);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getSerializer()).thenReturn(serializer);
        when(response.getOutputStream()).thenReturn(outputStream);
        when(event.isCancelled()).thenReturn(true);

        handler.onStateChange(event);

        verify(serializer).write(outputStream, "data");
    }

    @Test
    void onStateChangeListWithSerializer() throws IOException {
        var serializer = mock(Serializer.class);
        List<Object> messages = new ArrayList<>();
        messages.add("a");
        messages.add("b");

        when(event.getMessage()).thenReturn(messages);
        when(event.getResource()).thenReturn(resource);
        when(resource.getResponse()).thenReturn(response);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getSerializer()).thenReturn(serializer);
        when(response.getOutputStream()).thenReturn(outputStream);
        when(event.isCancelled()).thenReturn(true);

        handler.onStateChange(event);

        verify(serializer).write(outputStream, "a");
        verify(serializer).write(outputStream, "b");
    }

    @Test
    void onStateChangeListWithStream() throws IOException {
        List<Object> messages = new ArrayList<>();
        messages.add("m1");
        messages.add("m2");

        when(event.getMessage()).thenReturn(messages);
        when(event.getResource()).thenReturn(resource);
        when(resource.getResponse()).thenReturn(response);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getSerializer()).thenReturn(null);
        when(request.getAttribute(ApplicationConfig.PROPERTY_USE_STREAM)).thenReturn(true);
        when(response.getOutputStream()).thenReturn(outputStream);
        when(response.getCharacterEncoding()).thenReturn("UTF-8");
        when(event.isCancelled()).thenReturn(true);

        handler.onStateChange(event);

        verify(outputStream).write("m1".getBytes("UTF-8"));
        verify(outputStream).write("m2".getBytes("UTF-8"));
        verify(outputStream).flush();
    }

    @Test
    void initReadsTwoStepsWriteConfig() throws Exception {
        var config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(ApplicationConfig.TWO_STEPS_WRITE, false)).thenReturn(true);

        handler.init(config);
        // No assertion needed — just verifying it doesn't throw
    }

    @Test
    void postStateChangeResumeOnBroadcast() throws IOException {
        when(event.getMessage()).thenReturn("msg");
        when(event.getResource()).thenReturn(resource);
        when(resource.getResponse()).thenReturn(response);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getSerializer()).thenReturn(null);
        when(request.getAttribute(ApplicationConfig.PROPERTY_USE_STREAM)).thenReturn(true);
        when(response.getOutputStream()).thenReturn(outputStream);
        when(response.getCharacterEncoding()).thenReturn("UTF-8");
        when(event.isCancelled()).thenReturn(false);
        when(event.isResuming()).thenReturn(false);
        when(resource.resumeOnBroadcast()).thenReturn(true);

        handler.onStateChange(event);

        verify(resource).resume();
    }

    @Test
    void postStateChangeCancelledSkipsResume() throws IOException {
        when(event.getMessage()).thenReturn("msg");
        when(event.getResource()).thenReturn(resource);
        when(resource.getResponse()).thenReturn(response);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getSerializer()).thenReturn(null);
        when(request.getAttribute(ApplicationConfig.PROPERTY_USE_STREAM)).thenReturn(true);
        when(response.getOutputStream()).thenReturn(outputStream);
        when(response.getCharacterEncoding()).thenReturn("UTF-8");
        when(event.isCancelled()).thenReturn(true);

        handler.onStateChange(event);

        verify(resource, never()).resume();
    }
}
