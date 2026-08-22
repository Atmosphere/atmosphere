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

import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The framework's voice-mode endpoint — the production driver of the
 * speech-to-speech loop (registre#25). Mount it on a path (the Spring /
 * Quarkus integrations register it behind {@code atmosphere.ai.voice.enabled})
 * and a connecting WebSocket client gets a {@link VoiceBridge} to the
 * highest-priority available {@link RealtimeVoiceProvider} resolved via its
 * {@link RealtimeVoiceProvider#resolve() ServiceLoader SPI}:
 *
 * <ul>
 *   <li><b>connect (GET)</b> — suspend, resolve the provider, open the
 *       bridge over the resource's streaming session;</li>
 *   <li><b>binary frame</b> — mic audio, forwarded to the provider;</li>
 *   <li><b>text frame</b> — {@code /voice:commit} ends the current
 *       utterance; any other text is a typed turn;</li>
 *   <li><b>disconnect</b> — the bridge (and its provider connection)
 *       closes exactly once.</li>
 * </ul>
 *
 * <p>Provider audio, captions, and user transcripts flow back down the same
 * session as the {@code VoiceBridge} documents. Without a registered
 * provider the SPI's loopback fallback echoes audio, which makes a missing
 * provider obvious rather than silent (Correctness Invariant #5).</p>
 *
 * <p>Ownership: bridges opened here are closed here — on client disconnect
 * and on {@link #destroy()} (Correctness Invariant #1). The bridge map is
 * bounded by the number of connected voice clients.</p>
 */
public final class VoiceEndpointHandler extends AbstractReflectorAtmosphereHandler {

    /** Control frame ending the user's current utterance. */
    public static final String COMMIT_CONTROL = "/voice:commit";

    private static final Logger logger = LoggerFactory.getLogger(VoiceEndpointHandler.class);

    private final VoiceSessionConfig config;
    private final Map<String, VoiceBridge> bridges = new ConcurrentHashMap<>();

    public VoiceEndpointHandler(VoiceSessionConfig config) {
        this.config = config != null ? config : VoiceSessionConfig.of("", "");
    }

    @Override
    public void onRequest(AtmosphereResource resource) {
        var request = resource.getRequest();
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            resource.suspend();
            var provider = RealtimeVoiceProvider.resolve();
            var bridge = VoiceBridge.open(provider, config, StreamingSessions.start(resource));
            var previous = bridges.put(resource.uuid(), bridge);
            if (previous != null) {
                previous.close();
            }
            logger.info("Voice session opened (provider={}, uuid={})",
                    provider.name(), resource.uuid());
            return;
        }
        var bridge = bridges.get(resource.uuid());
        if (bridge == null) {
            logger.debug("Voice frame from {} without an open bridge — dropped", resource.uuid());
            return;
        }
        var body = request.body();
        if (body.hasBytes()) {
            bridge.onClientAudio(Arrays.copyOfRange(body.asBytes(),
                    body.byteOffset(), body.byteOffset() + body.byteLength()));
        } else if (body.hasString()) {
            var text = body.asString();
            if (COMMIT_CONTROL.equals(text.strip())) {
                bridge.commitInput();
            } else if (!text.isBlank()) {
                bridge.onClientText(text);
            }
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        var resource = event.getResource();
        if (event.isCancelled() || event.isClosedByClient() || event.isClosedByApplication()) {
            var bridge = bridges.remove(resource.uuid());
            if (bridge != null) {
                bridge.close();
                logger.debug("Voice session closed (uuid={})", resource.uuid());
            }
            return;
        }
        // RawMessage = the bridge's session broadcasting provider audio /
        // captions back to this client; unwrap and let the reflector write
        // it through the AsyncIOWriter chain.
        if (event.getMessage() instanceof RawMessage raw) {
            event.setMessage(raw.message());
        }
        super.onStateChange(event);
    }

    @Override
    public void destroy() {
        bridges.values().forEach(VoiceBridge::close);
        bridges.clear();
    }
}
