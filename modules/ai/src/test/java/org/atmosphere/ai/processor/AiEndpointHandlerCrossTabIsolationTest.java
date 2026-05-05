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
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.HeaderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the cross-tab isolation guarantee for {@link AiEndpointHandler}.
 *
 * <p>Two tabs subscribed to the same {@code @AiEndpoint} path must receive each
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
 *   <li>Only when neither hint is present do we fall back to broadcast-all
 *       (and emit a warning log so non-conformant clients are visible).</li>
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

    @Test
    void noHintsTriggersFanoutFallback() throws Exception {
        // Legacy / non-conformant client: no SUSPENDED_ATMOSPHERE_RESOURCE_UUID
        // attribute, tracking-id header is "0" (fresh). We should fall back to
        // the per-path broadcast-all path so message delivery isn't silently
        // dropped — but the warning log makes it visible (not asserted here,
        // but documented in AiEndpointHandler).
        var fallbackBroadcaster = mock(Broadcaster.class);

        var tempResource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);

        when(tempResource.getRequest()).thenReturn(request);
        when(tempResource.getAtmosphereConfig()).thenReturn(config);
        when(tempResource.getBroadcaster()).thenReturn(fallbackBroadcaster);
        when(request.getMethod()).thenReturn("POST");
        when(request.body()).thenReturn(new AtmosphereRequestImpl.Body.StringBody("legacy-prompt"));
        when(request.getAttribute(ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID))
                .thenReturn(null);
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID)).thenReturn("0");

        handler.onRequest(tempResource);

        // Fallback fans out: the legacy path is preserved as a safety net.
        verify(fallbackBroadcaster).broadcast(eq("legacy-prompt"));
    }

    @Test
    void unknownUuidFallsThroughToFanout() throws Exception {
        // Suspended UUID was published but the resource has since gone away
        // (disconnect race). We must not silently drop the prompt — fall back
        // to broadcast-all rather than leave the user with no response.
        var fallbackBroadcaster = mock(Broadcaster.class);

        when(resourcesFactory.findResource("ghost-uuid")).thenReturn(Optional.empty());

        var tempResource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);

        when(tempResource.getRequest()).thenReturn(request);
        when(tempResource.getAtmosphereConfig()).thenReturn(config);
        when(tempResource.getBroadcaster()).thenReturn(fallbackBroadcaster);
        when(request.getMethod()).thenReturn("POST");
        when(request.body()).thenReturn(new AtmosphereRequestImpl.Body.StringBody("orphan-prompt"));
        when(request.getAttribute(ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID))
                .thenReturn("ghost-uuid");
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID)).thenReturn(null);

        handler.onRequest(tempResource);

        verify(fallbackBroadcaster).broadcast(eq("orphan-prompt"));
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
