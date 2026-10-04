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
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A prompt POST over long-polling or SSE is read up to
 * {@value AiEndpointHandler#MAX_PROMPT_BYTES_PARAM} bytes: a prompt of exactly
 * the limit runs, a larger one is answered {@code 413} and a body that is not
 * valid text {@code 400}, neither of them dispatched.
 */
class AiEndpointHandlerPromptSizeTest {

    private static final String PATH = "/atmosphere/ai";
    private static final int LIMIT = 32;

    private final Map<String, AtmosphereResource> registered = new ConcurrentHashMap<>();
    private final List<String> dispatched = new CopyOnWriteArrayList<>();
    private AtmosphereConfig config;
    private AiEndpointHandler handler;

    private void setUp() throws Exception {
        config = mock(AtmosphereConfig.class);
        var factory = mock(AtmosphereResourceFactory.class);
        when(config.resourcesFactory()).thenReturn(factory);
        when(factory.findResource(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(registered.get(inv.<String>getArgument(0))));
        when(config.getInitParameter(anyString())).thenAnswer(inv ->
                AiEndpointHandler.MAX_PROMPT_BYTES_PARAM.equals(inv.getArgument(0)) ? String.valueOf(LIMIT) : null);
        when(config.getInitParameter(anyString(), anyBoolean())).thenReturn(false);
        when(config.properties()).thenReturn(new ConcurrentHashMap<>());
        var promptMethod = StubEndpoint.class.getDeclaredMethod("onPrompt", String.class, StreamingSession.class);
        handler = spy(new AiEndpointHandler(new StubEndpoint(), promptMethod, 30_000L, "",
                mock(AgentRuntime.class), List.<AiInterceptor>of()));
        doAnswer(inv -> dispatched.add(inv.getArgument(1))).when(handler).dispatchPrompt(any(), any());
    }

    /** The client's suspended connection, which a prompt naming its id resolves to. */
    private void connected(String trackingId, AtmosphereResource.TRANSPORT transport) throws Exception {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var broadcaster = mock(Broadcaster.class);
        when(broadcaster.getBroadcasterConfig()).thenReturn(mock(BroadcasterConfig.class));
        when(broadcaster.getID()).thenReturn(PATH);
        when(resource.uuid()).thenReturn(trackingId);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getAtmosphereConfig()).thenReturn(config);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(resource.transport()).thenReturn(transport);
        when(resource.isSuspended()).thenReturn(true);
        when(request.getMethod()).thenReturn("GET");
        var stamp = new AtomicReference<Object>();
        doAnswer(inv -> {
            stamp.set(inv.getArgument(1));
            return null;
        }).when(request).setAttribute(eq(AiEndpointHandler.ENDPOINT_HANDLER_ATTRIBUTE), any());
        when(request.getAttribute(AiEndpointHandler.ENDPOINT_HANDLER_ATTRIBUTE)).thenAnswer(inv -> stamp.get());
        when(resource.suspend(anyLong())).thenAnswer(inv -> {
            registered.put(trackingId, resource);
            return resource;
        });
        handler.onRequest(resource);
    }

    /** A prompt POST of {@code body} naming {@code trackingId}; returns its response. */
    private AtmosphereResponse post(String trackingId, AtmosphereResource.TRANSPORT transport, byte[] body)
            throws Exception {
        var request = new AtmosphereRequestImpl.Builder().method("POST").pathInfo(PATH)
                .headers(Map.of(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, trackingId,
                        HeaderConfig.X_ATMOSPHERE_TRANSPORT, transport.name().toLowerCase().replace('_', '-')))
                .inputStream(new ByteArrayInputStream(body)).build();
        var resource = mock(AtmosphereResource.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(resource.getAtmosphereConfig()).thenReturn(config);
        when(resource.transport()).thenReturn(transport);
        when(resource.uuid()).thenReturn("post-" + trackingId);
        handler.onRequest(resource);
        return response;
    }

    @ParameterizedTest
    @EnumSource(value = AtmosphereResource.TRANSPORT.class, names = {"LONG_POLLING", "SSE"})
    void aPromptOfExactlyTheLimitRuns(AtmosphereResource.TRANSPORT transport) throws Exception {
        setUp();
        connected("client-a", transport);
        var prompt = "é".repeat(LIMIT / 2); // LIMIT UTF-8 bytes

        var response = post("client-a", transport, prompt.getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of(prompt), dispatched);
        verify(response, never()).setStatus(413);
        verify(response, never()).setStatus(400);
    }

    @ParameterizedTest
    @EnumSource(value = AtmosphereResource.TRANSPORT.class, names = {"LONG_POLLING", "SSE"})
    void aPromptOneByteOverTheLimitIsAnswered413(AtmosphereResource.TRANSPORT transport) throws Exception {
        setUp();
        connected("client-a", transport);

        var response = post("client-a", transport, "x".repeat(LIMIT + 1).getBytes(StandardCharsets.UTF_8));

        verify(response).setStatus(413);
        assertTrue(dispatched.isEmpty(), "an oversize prompt must not run");
    }

    @ParameterizedTest
    @EnumSource(value = AtmosphereResource.TRANSPORT.class, names = {"LONG_POLLING", "SSE"})
    void aPromptThatIsNotValidTextIsAnswered400(AtmosphereResource.TRANSPORT transport) throws Exception {
        setUp();
        connected("client-a", transport);

        var response = post("client-a", transport, new byte[]{'h', 'i', (byte) 0xC3, (byte) 0x28});

        verify(response).setStatus(400);
        assertTrue(dispatched.isEmpty(), "a malformed prompt must not run");
    }

    @ParameterizedTest
    @EnumSource(value = AtmosphereResource.TRANSPORT.class, names = {"LONG_POLLING", "SSE"})
    void aPromptInAnUnknownEncodingIsAnswered400(AtmosphereResource.TRANSPORT transport) throws Exception {
        setUp();
        connected("client-a", transport);
        var request = new AtmosphereRequestImpl.Builder().method("POST").pathInfo(PATH).encoding("no-such-charset")
                .headers(Map.of(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, "client-a",
                        HeaderConfig.X_ATMOSPHERE_TRANSPORT, transport.name().toLowerCase().replace('_', '-')))
                .inputStream(new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8))).build();
        var resource = mock(AtmosphereResource.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(resource.getAtmosphereConfig()).thenReturn(config);
        when(resource.transport()).thenReturn(transport);

        handler.onRequest(resource);

        verify(response).setStatus(400);
        assertTrue(dispatched.isEmpty());
    }

    @AiEndpoint(path = PATH)
    static class StubEndpoint {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
        }
    }
}
