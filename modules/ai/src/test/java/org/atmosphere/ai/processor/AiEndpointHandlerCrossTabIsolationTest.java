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
package org.atmosphere.ai.processor;

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the cross-tab isolation guarantee for {@link AiEndpointHandler}.
 *
 * <p>Two tabs subscribed to the same {@code @AiEndpoint} path must never receive each
 * other's prompts. Before the targeted-dispatch fix, the prompt POST handler
 * called {@code broadcaster.broadcast(msg)} which fanned the prompt out to every
 * suspended resource on the per-path broadcaster — driving N redundant LLM
 * calls and leaking responses across tabs.</p>
 *
 * <p>The contract these tests pin:</p>
 * <ul>
 *   <li>{@link ApplicationConfig#SUSPENDED_ATMOSPHERE_RESOURCE_UUID} (set by
 *       {@code DefaultWebSocketProcessor} when the WebSocket upgrades) routes
 *       the prompt to the originating suspended resource only.</li>
 *   <li>{@link HeaderConfig#X_ATMOSPHERE_TRACKING_ID} (carried by SSE and
 *       long-polling clients on every prompt POST) is the SSE/LP fallback.</li>
 *   <li>The {@code broadcast(msg, target)} overload is used so the broadcaster's
 *       {@code onStateChange} fires for the target only — never the all-resources
 *       fanout.</li>
 *   <li>A prompt is never broadcast to all: with neither hint it is refused
 *       with {@code 400}, and with a hint that resolves to no connection it is
 *       refused with a retryable {@code 503} (see
 *       {@link AiEndpointHandlerPromptRepollTest} for the bounded wait).</li>
 * </ul>
 */
class AiEndpointHandlerCrossTabIsolationTest {

    private AiEndpointHandler handler;
    private AtmosphereConfig config;
    private AtmosphereResourceFactory resourcesFactory;
    private Broadcaster originatingBroadcaster;

    @BeforeEach
    void setUp() throws Exception {
        var promptMethod = StubEndpoint.class.getDeclaredMethod(
                "onPrompt", String.class, StreamingSession.class);
        handler = new AiEndpointHandler(
                new StubEndpoint(),
                promptMethod,
                30_000L,
                "",
                mock(AgentRuntime.class),
                List.<AiInterceptor>of());

        config = mock(AtmosphereConfig.class);
        resourcesFactory = mock(AtmosphereResourceFactory.class);
        originatingBroadcaster = mock(Broadcaster.class);
        when(config.resourcesFactory()).thenReturn(resourcesFactory);
    }

    @Test
    void webSocketFrameRoutesToSuspendedResourceUuidOnly() throws Exception {
        var originatingResource = mock(AtmosphereResource.class);
        when(originatingResource.uuid()).thenReturn("ws-suspended-uuid-A");
        when(originatingResource.getBroadcaster()).thenReturn(originatingBroadcaster);

        when(resourcesFactory.findResource("ws-suspended-uuid-A"))
                .thenReturn(Optional.of(originatingResource));

        var tempResource = postResourceWith(
                ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID, "ws-suspended-uuid-A",
                /* trackingHeader */ null,
                "tab-A-prompt");

        handler.onRequest(tempResource);

        var msgCaptor = ArgumentCaptor.forClass(Object.class);
        var targetCaptor = ArgumentCaptor.forClass(AtmosphereResource.class);
        verify(originatingBroadcaster).broadcast(msgCaptor.capture(), targetCaptor.capture());
        assertSame(originatingResource, targetCaptor.getValue(),
                "WebSocket prompt must dispatch to the suspended resource recorded in "
                        + "SUSPENDED_ATMOSPHERE_RESOURCE_UUID, not be broadcast to all subscribers.");

        // The fanout overload (the bug path) must NEVER be called when the suspended
        // UUID resolves cleanly — that is the whole point of this regression pin.
        verify(originatingBroadcaster, never()).broadcast(any());
    }

    @Test
    void sseLongPollingPostRoutesViaTrackingIdHeader() throws Exception {
        var originatingResource = mock(AtmosphereResource.class);
        when(originatingResource.uuid()).thenReturn("sse-tracking-uuid-B");
        when(originatingResource.getBroadcaster()).thenReturn(originatingBroadcaster);

        when(resourcesFactory.findResource("sse-tracking-uuid-B"))
                .thenReturn(Optional.of(originatingResource));

        var tempResource = postResourceWith(
                /* suspendedUuidAttr */ null, /* attrValue */ null,
                "sse-tracking-uuid-B",
                "tab-B-prompt");

        handler.onRequest(tempResource);

        verify(originatingBroadcaster).broadcast(eq("tab-B-prompt"), eq(originatingResource));
        verify(originatingBroadcaster, never()).broadcast(any());
    }

    /**
     * A long-polling / SSE prompt POST is a plain HTTP request: nothing caches
     * its entity on the request body the way the WebSocket processor caches a
     * frame. The handler must read it — otherwise the POST answers 200 and the
     * prompt is dropped without a trace (measured on spring-boot-dentist-agent
     * and quarkus-ai-chat through the Console's long-polling fallback).
     */
    @Test
    void httpTransportPostReadsThePromptFromTheRequestEntity() throws Exception {
        var originatingResource = mock(AtmosphereResource.class);
        when(originatingResource.uuid()).thenReturn("lp-tracking-uuid-C");
        when(originatingResource.getBroadcaster()).thenReturn(originatingBroadcaster);
        when(resourcesFactory.findResource("lp-tracking-uuid-C"))
                .thenReturn(Optional.of(originatingResource));

        var postResource = mock(AtmosphereResourceImpl.class);
        var request = mock(AtmosphereRequest.class);
        when(postResource.getRequest()).thenReturn(request);
        when(postResource.getRequest(false)).thenReturn(request);
        when(postResource.getAtmosphereConfig()).thenReturn(config);
        when(request.getMethod()).thenReturn("POST");
        when(request.body()).thenReturn(new AtmosphereRequestImpl.Body.EmptyBody());
        when(request.getInputStream()).thenReturn(servletInputStream("tab-C-prompt"));
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID)).thenReturn("lp-tracking-uuid-C");

        handler.onRequest(postResource);

        verify(originatingBroadcaster).broadcast(eq("tab-C-prompt"), eq(originatingResource));
        verify(originatingBroadcaster, never()).broadcast(any());
        // Cached back for any later reader of the same request.
        verify(request).body("tab-C-prompt");
    }

    private static ServletInputStream servletInputStream(String content) {
        var bytes = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        return new ServletInputStream() {
            @Override
            public int read() {
                return bytes.read();
            }

            @Override
            public boolean isFinished() {
                return bytes.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("blocking test stream");
            }
        };
    }

    @Test
    void noHintsIsRejectedWith400AndNeverFansOut() throws Exception {
        // No SUSPENDED_ATMOSPHERE_RESOURCE_UUID attribute and a "0" (pre-handshake)
        // tracking id: nothing identifies the sender. The prompt used to fan out
        // to every subscriber of the path; it is now refused.
        var fallbackBroadcaster = mock(Broadcaster.class);
        var response = mock(AtmosphereResponse.class);

        var tempResource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);

        when(tempResource.getRequest()).thenReturn(request);
        when(tempResource.getResponse()).thenReturn(response);
        when(tempResource.getAtmosphereConfig()).thenReturn(config);
        when(tempResource.getBroadcaster()).thenReturn(fallbackBroadcaster);
        when(request.getMethod()).thenReturn("POST");
        when(request.body()).thenReturn(new AtmosphereRequestImpl.Body.StringBody("legacy-prompt"));
        when(request.getAttribute(ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID))
                .thenReturn(null);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID)).thenReturn("0");

        handler.onRequest(tempResource);

        verify(response).setStatus(400);
        verify(fallbackBroadcaster, never()).broadcast(any());
        verify(fallbackBroadcaster, never()).broadcast(any(), any(AtmosphereResource.class));
    }

    @Test
    void unknownUuidIsAnsweredWithAnErrorFrameAndNeverFansOut() throws Exception {
        // A WebSocket frame whose suspended UUID was published but whose resource
        // has since gone away: answer it with an error frame over its own socket
        // (a status would never reach a WebSocket client) rather than fan the
        // prompt out to every subscriber.
        var fallbackBroadcaster = mock(Broadcaster.class);
        var response = mock(AtmosphereResponse.class);

        when(resourcesFactory.findResource("ghost-uuid")).thenReturn(Optional.empty());

        var tempResource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);

        when(tempResource.getRequest()).thenReturn(request);
        when(tempResource.getResponse()).thenReturn(response);
        when(tempResource.getAtmosphereConfig()).thenReturn(config);
        when(tempResource.getBroadcaster()).thenReturn(fallbackBroadcaster);
        when(request.getMethod()).thenReturn("POST");
        when(request.body()).thenReturn(new AtmosphereRequestImpl.Body.StringBody("orphan-prompt"));
        when(request.getAttribute(ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID))
                .thenReturn("ghost-uuid");
        when(request.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE)).thenReturn("true");
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID)).thenReturn(null);

        handler.onRequest(tempResource);

        verify(tempResource).write(AiEndpointHandler.WEBSOCKET_REFUSAL_FRAME);
        verify(response, never()).setStatus(anyInt());
        verify(fallbackBroadcaster, never()).broadcast(any());
        verify(fallbackBroadcaster, never()).broadcast(any(), any(AtmosphereResource.class));
    }

    private AtmosphereResource postResourceWith(String suspendedUuidAttr,
                                                String suspendedUuidValue,
                                                String trackingHeader,
                                                String body) {
        var tempResource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);

        when(tempResource.getRequest()).thenReturn(request);
        when(tempResource.getAtmosphereConfig()).thenReturn(config);
        when(request.getMethod()).thenReturn("POST");
        when(request.body()).thenReturn(new AtmosphereRequestImpl.Body.StringBody(body));
        when(request.getAttribute(ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID))
                .thenReturn(suspendedUuidAttr != null ? suspendedUuidValue : null);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID))
                .thenReturn(trackingHeader);
        return tempResource;
    }

    @AiEndpoint(path = "/atmosphere/test")
    static class StubEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            // Test-only stub; never invoked because onRequest's POST branch
            // returns before dispatching.
        }
    }
}
