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

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnMessageTest {

    @Test
    void onRequestGetCallsOnOpen() throws IOException {
        var opened = new AtomicReference<AtmosphereResource>();

        var handler = new OnMessage<String>() {
            @Override
            public void onMessage(AtmosphereResponse response, String message) {
            }

            @Override
            public void onOpen(AtmosphereResource resource) {
                opened.set(resource);
            }
        };

        var resource = mock(AtmosphereResource.class);
        var request = mock(org.atmosphere.cpr.AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getMethod()).thenReturn("GET");

        handler.onRequest(resource);
        assertEquals(resource, opened.get());
    }

    @Test
    void onRequestPostDoesNotCallOnOpen() throws IOException {
        var opened = new AtomicReference<AtmosphereResource>();

        var handler = new OnMessage<String>() {
            @Override
            public void onMessage(AtmosphereResponse response, String message) {
            }

            @Override
            public void onOpen(AtmosphereResource resource) {
                opened.set(resource);
            }
        };

        var resource = mock(AtmosphereResource.class);
        var request = mock(org.atmosphere.cpr.AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getMethod()).thenReturn("POST");

        handler.onRequest(resource);
        assertNull(opened.get());
    }

    @Test
    void onStateChangeCancelledCallsOnDisconnect() throws IOException {
        var disconnected = new AtomicReference<AtmosphereResponse>();

        var handler = new OnMessage<String>() {
            @Override
            public void onMessage(AtmosphereResponse response, String message) {
            }

            @Override
            public void onDisconnect(AtmosphereResponse response) {
                disconnected.set(response);
            }
        };

        var resource = mock(AtmosphereResourceImpl.class);
        var response = mock(AtmosphereResponse.class);
        var event = mock(AtmosphereResourceEvent.class);
        when(event.getResource()).thenReturn(resource);
        when(event.isCancelled()).thenReturn(true);
        when(resource.getResponse(false)).thenReturn(response);

        handler.onStateChange(event);
        assertEquals(response, disconnected.get());
    }

    @Test
    void onStateChangeSuspendedCallsOnMessage() throws IOException {
        var received = new AtomicReference<String>();

        var handler = new OnMessage<String>() {
            @Override
            public void onMessage(AtmosphereResponse response, String message) {
                received.set(message);
            }
        };

        var resource = mock(AtmosphereResourceImpl.class);
        var response = mock(AtmosphereResponse.class);
        var request = mock(org.atmosphere.cpr.AtmosphereRequest.class);
        var event = mock(AtmosphereResourceEvent.class);
        when(event.getResource()).thenReturn(resource);
        when(event.isSuspended()).thenReturn(true);
        when(event.getMessage()).thenReturn("hello");
        when(resource.getResponse(false)).thenReturn(response);
        when(resource.getRequest(false)).thenReturn(request);

        handler.onStateChange(event);
        assertEquals("hello", received.get());
    }

    @Test
    void onStateChangeListCallsOnMessageForEach() throws IOException {
        var received = new ArrayList<String>();

        var handler = new OnMessage<String>() {
            @Override
            public void onMessage(AtmosphereResponse response, String message) {
                received.add(message);
            }
        };

        var resource = mock(AtmosphereResourceImpl.class);
        var response = mock(AtmosphereResponse.class);
        var request = mock(org.atmosphere.cpr.AtmosphereRequest.class);
        var event = mock(AtmosphereResourceEvent.class);
        when(event.getResource()).thenReturn(resource);
        when(event.getMessage()).thenReturn(List.of("a", "b", "c"));
        when(resource.getResponse(false)).thenReturn(response);
        when(resource.getRequest(false)).thenReturn(request);

        handler.onStateChange(event);
        assertEquals(List.of("a", "b", "c"), received);
    }

    @Test
    void onStateChangeResumingCallsOnResume() throws IOException {
        var resumed = new AtomicReference<AtmosphereResponse>();

        var handler = new OnMessage<String>() {
            @Override
            public void onMessage(AtmosphereResponse response, String message) {
            }

            @Override
            public void onResume(AtmosphereResponse response) {
                resumed.set(response);
            }
        };

        var resource = mock(AtmosphereResourceImpl.class);
        var response = mock(AtmosphereResponse.class);
        var event = mock(AtmosphereResourceEvent.class);
        when(event.getResource()).thenReturn(resource);
        when(event.isResuming()).thenReturn(true);
        when(resource.getResponse(false)).thenReturn(response);

        handler.onStateChange(event);
        assertEquals(response, resumed.get());
    }

    @Test
    void onStateChangeTimedOutCallsOnTimeout() throws IOException {
        var timedOut = new AtomicReference<AtmosphereResponse>();

        var handler = new OnMessage<String>() {
            @Override
            public void onMessage(AtmosphereResponse response, String message) {
            }

            @Override
            public void onTimeout(AtmosphereResponse response) {
                timedOut.set(response);
            }
        };

        var resource = mock(AtmosphereResourceImpl.class);
        var response = mock(AtmosphereResponse.class);
        var request = mock(org.atmosphere.cpr.AtmosphereRequest.class);
        var event = mock(AtmosphereResourceEvent.class);
        when(event.getResource()).thenReturn(resource);
        when(event.isResumedOnTimeout()).thenReturn(true);
        when(resource.getResponse(false)).thenReturn(response);
        when(resource.getRequest(false)).thenReturn(request);

        handler.onStateChange(event);
        assertEquals(response, timedOut.get());
    }

    @Test
    void destroyDoesNotThrow() {
        var handler = new OnMessage<String>() {
            @Override
            public void onMessage(AtmosphereResponse response, String message) {
            }
        };
        handler.destroy();
    }
}
