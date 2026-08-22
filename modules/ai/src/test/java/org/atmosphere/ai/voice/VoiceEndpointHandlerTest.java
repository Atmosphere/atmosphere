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
package org.atmosphere.ai.voice;

import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression (registre#25): {@code RealtimeVoiceProvider.resolve()} had no
 * in-tree caller — the framework shipped no voice-mode endpoint. The
 * {@link VoiceEndpointHandler} now drives the full loop; through the
 * loopback provider a mic frame must round-trip back to the client as an
 * audio content frame.
 */
class VoiceEndpointHandlerTest {

    private static AtmosphereResource resource(Broadcaster broadcaster, String method, byte[] body) {
        var builder = new AtmosphereRequestImpl.Builder().method(method);
        if (body != null) {
            builder.body(body);
        }
        var resource = mock(AtmosphereResource.class);
        when(resource.getRequest()).thenReturn(builder.build());
        when(resource.uuid()).thenReturn("voice-uuid-1");
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        return resource;
    }

    @Test
    void connectSuspendsAndMicAudioRoundTripsThroughTheResolvedProvider() {
        var handler = new VoiceEndpointHandler(VoiceSessionConfig.of("realtime", "Be brief."));
        var broadcaster = mock(Broadcaster.class);

        handler.onRequest(resource(broadcaster, "GET", null));

        var mic = "PCMDATA".getBytes(StandardCharsets.UTF_8);
        handler.onRequest(resource(broadcaster, "POST", mic));

        var messages = ArgumentCaptor.forClass(Object.class);
        verify(broadcaster, atLeastOnce()).broadcast(messages.capture(), anySet());
        var sawAudio = messages.getAllValues().stream()
                .filter(m -> m instanceof RawMessage)
                .map(m -> ((RawMessage) m).message().toString())
                .anyMatch(json -> json.contains("\"contentType\":\"audio\""));
        assertTrue(sawAudio, "the loopback provider's echoed audio must reach the client "
                + "as an audio content frame: " + messages.getAllValues());
        handler.destroy();
    }

    @Test
    void connectSuspendsTheResource() {
        var handler = new VoiceEndpointHandler(VoiceSessionConfig.of("realtime", ""));
        var resource = resource(mock(Broadcaster.class), "GET", null);

        handler.onRequest(resource);

        verify(resource).suspend();
        handler.destroy();
    }

    @Test
    void disconnectClosesTheBridgeAndLaterFramesAreDropped() throws Exception {
        var handler = new VoiceEndpointHandler(VoiceSessionConfig.of("realtime", ""));
        var broadcaster = mock(Broadcaster.class);
        handler.onRequest(resource(broadcaster, "GET", null));

        var event = mock(AtmosphereResourceEvent.class);
        var disconnected = resource(broadcaster, "GET", null);
        when(event.getResource()).thenReturn(disconnected);
        when(event.isCancelled()).thenReturn(true);
        handler.onStateChange(event);

        var lateBroadcaster = mock(Broadcaster.class);
        handler.onRequest(resource(lateBroadcaster, "POST",
                "late".getBytes(StandardCharsets.UTF_8)));
        verify(lateBroadcaster, never()).broadcast(any(), anySet());
        handler.destroy();
    }

    @Test
    void frameWithoutABridgeIsDroppedNotFatal() {
        var handler = new VoiceEndpointHandler(VoiceSessionConfig.of("realtime", ""));
        var broadcaster = mock(Broadcaster.class);

        handler.onRequest(resource(broadcaster, "POST",
                "orphan".getBytes(StandardCharsets.UTF_8)));

        verify(broadcaster, never()).broadcast(any(), anySet());
    }
}
