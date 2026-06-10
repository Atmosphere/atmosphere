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

import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges a connected client's {@link StreamingSession} (audio frames over the
 * existing WebSocket broadcaster) to a {@link RealtimeVoiceProvider}: client mic
 * audio flows up via {@link #onClientAudio}, and the provider's synthesized audio
 * + transcripts flow back down the session as {@link Content.Audio} frames and
 * text. This is the speech-to-speech loop — the transport half is already shipped,
 * so the bridge is intentionally thin.
 *
 * <p>Metadata key for a user-speech transcript pushed to the client.</p>
 *
 * <p><strong>Ownership:</strong> the bridge owns the {@link VoiceConnection} it
 * opened and closes it on {@link #close()} (Correctness Invariant #1). Close is
 * idempotent and shared with the provider's {@code onClose} callback so a
 * provider-initiated end and a client disconnect both settle exactly once.</p>
 */
public final class VoiceBridge implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(VoiceBridge.class);

    /** Metadata key under which a user-speech transcript is pushed to the client. */
    public static final String USER_TRANSCRIPT_METADATA = "voice.transcript.user";

    private final StreamingSession client;
    private final VoiceConnection connection;
    private final AtomicBoolean closed;

    private VoiceBridge(StreamingSession client, VoiceConnection connection, AtomicBoolean closed) {
        this.client = client;
        this.connection = connection;
        this.closed = closed;
    }

    /**
     * Open a voice bridge: connect to {@code provider} and wire its output to
     * {@code client}.
     */
    public static VoiceBridge open(RealtimeVoiceProvider provider, VoiceSessionConfig config,
                                   StreamingSession client) {
        if (provider == null || config == null || client == null) {
            throw new IllegalArgumentException("provider, config, and client must not be null");
        }
        var closed = new AtomicBoolean();
        var callbacks = new ClientForwardingCallbacks(client, config.outputMimeType(), closed);
        var connection = provider.connect(config, callbacks);
        logger.debug("Voice bridge opened (provider={}, session={})",
                provider.name(), client.sessionId());
        return new VoiceBridge(client, connection, closed);
    }

    /** Forward a chunk of captured client audio to the provider. */
    public void onClientAudio(byte[] audioChunk) {
        if (closed.get() || audioChunk == null || audioChunk.length == 0) {
            return;
        }
        connection.sendAudio(audioChunk);
    }

    /** Forward a typed text turn to the provider. */
    public void onClientText(String text) {
        if (!closed.get() && text != null && !text.isEmpty()) {
            connection.sendText(text);
        }
    }

    /** Signal the end of the user's current utterance. */
    public void commitInput() {
        if (!closed.get()) {
            connection.commitInput();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                connection.close();
            } catch (RuntimeException e) {
                logger.debug("Voice connection close failed: {}", e.toString());
            }
            client.complete();
        }
    }

    /** Fans provider output out to the client session. */
    private record ClientForwardingCallbacks(StreamingSession client, String outputMimeType,
                                             AtomicBoolean closed) implements VoiceCallbacks {

        @Override
        public void onAudio(byte[] audioChunk) {
            if (closed.get() || audioChunk == null || audioChunk.length == 0) {
                return;
            }
            client.sendContent(Content.audio(audioChunk, outputMimeType));
        }

        @Override
        public void onTranscript(String text, VoiceRole role, boolean isFinal) {
            if (closed.get() || text == null || text.isEmpty()) {
                return;
            }
            if (role == VoiceRole.ASSISTANT) {
                // Assistant transcript is plain text on the stream so existing
                // text-rendering clients show captions alongside the audio.
                client.send(text);
            } else if (isFinal) {
                client.sendMetadata(USER_TRANSCRIPT_METADATA, text);
            }
        }

        @Override
        public void onError(Throwable error) {
            if (closed.compareAndSet(false, true)) {
                client.error(error);
            }
        }

        @Override
        public void onClose() {
            if (closed.compareAndSet(false, true)) {
                client.complete();
            }
        }
    }
}
