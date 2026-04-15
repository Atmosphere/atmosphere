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
package org.atmosphere.mcp;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolResponseHandlerTest {

    private BiDirectionalToolBridge bridge;
    private ToolResponseHandler handler;

    @BeforeEach
    void setUp() {
        bridge = mock(BiDirectionalToolBridge.class);
        handler = new ToolResponseHandler(bridge);
    }

    @Test
    void onRequestDelegatesToBridgeWhenBodyPresent() throws IOException {
        var json = "{\"id\":\"abc\",\"result\":\"ok\"}";
        var resource = mockResourceWithBody(json);

        handler.onRequest(resource);

        verify(bridge).completePendingCall(json);
        verify(resource).resume();
    }

    @Test
    void onRequestSkipsBridgeWhenBodyBlank() throws IOException {
        var resource = mockResourceWithBody("   ");

        handler.onRequest(resource);

        verify(bridge, never()).completePendingCall(org.mockito.ArgumentMatchers.anyString());
        verify(resource).resume();
    }

    @Test
    void onRequestSkipsBridgeWhenBodyEmpty() throws IOException {
        var resource = mockResourceWithBody("");

        handler.onRequest(resource);

        verify(bridge, never()).completePendingCall(org.mockito.ArgumentMatchers.anyString());
        verify(resource).resume();
    }

    @Test
    void onStateChangeHandlesStringMessage() throws IOException {
        var event = mock(AtmosphereResourceEvent.class);
        when(event.isCancelled()).thenReturn(false);
        when(event.isClosedByClient()).thenReturn(false);
        when(event.getMessage()).thenReturn("{\"id\":\"x1\",\"result\":\"done\"}");

        handler.onStateChange(event);

        verify(bridge).completePendingCall("{\"id\":\"x1\",\"result\":\"done\"}");
    }

    @Test
    void onStateChangeIgnoresCancelledEvent() throws IOException {
        var event = mock(AtmosphereResourceEvent.class);
        when(event.isCancelled()).thenReturn(true);

        handler.onStateChange(event);

        verify(bridge, never()).completePendingCall(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void onStateChangeIgnoresClosedByClient() throws IOException {
        var event = mock(AtmosphereResourceEvent.class);
        when(event.isClosedByClient()).thenReturn(true);

        handler.onStateChange(event);

        verify(bridge, never()).completePendingCall(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void onStateChangeIgnoresNonStringMessage() throws IOException {
        var event = mock(AtmosphereResourceEvent.class);
        when(event.isCancelled()).thenReturn(false);
        when(event.isClosedByClient()).thenReturn(false);
        when(event.getMessage()).thenReturn(42);

        handler.onStateChange(event);

        verify(bridge, never()).completePendingCall(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void onStateChangeIgnoresBlankStringMessage() throws IOException {
        var event = mock(AtmosphereResourceEvent.class);
        when(event.isCancelled()).thenReturn(false);
        when(event.isClosedByClient()).thenReturn(false);
        when(event.getMessage()).thenReturn("   ");

        handler.onStateChange(event);

        verify(bridge, never()).completePendingCall(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void destroyIsNoOp() {
        handler.destroy();
        // Should not throw
    }

    private AtmosphereResource mockResourceWithBody(String body) throws IOException {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var inputStream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        when(resource.getRequest()).thenReturn(request);
        when(request.getInputStream()).thenReturn(new jakarta.servlet.ServletInputStream() {
            @Override
            public boolean isFinished() {
                return inputStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(jakarta.servlet.ReadListener readListener) { }

            @Override
            public int read() throws IOException {
                return inputStream.read();
            }
        });
        return resource;
    }
}
