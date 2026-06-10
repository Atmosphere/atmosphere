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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dependency-free reference {@link RealtimeVoiceProvider} that echoes client
 * audio straight back and turns text turns into assistant transcripts. It lets
 * the whole {@link VoiceBridge} loop run end-to-end — and be tested — without an
 * API key or network, the same way the in-process sandbox lets the sandbox SPI
 * run without Docker.
 *
 * <p>It is <strong>not</strong> a real speech model: production deployments
 * register an OpenAI Realtime / Gemini Live provider implementing the same SPI.</p>
 */
public final class LoopbackVoiceProvider implements RealtimeVoiceProvider {

    @Override
    public String name() {
        return "loopback";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public VoiceConnection connect(VoiceSessionConfig config, VoiceCallbacks callbacks) {
        return new LoopbackConnection(callbacks);
    }

    private static final class LoopbackConnection implements VoiceConnection {

        private final VoiceCallbacks callbacks;
        private final AtomicBoolean open = new AtomicBoolean(true);

        LoopbackConnection(VoiceCallbacks callbacks) {
            this.callbacks = callbacks;
        }

        @Override
        public void sendAudio(byte[] audioChunk) {
            if (open.get() && audioChunk != null && audioChunk.length > 0) {
                // Echo the captured audio straight back as assistant audio.
                callbacks.onAudio(audioChunk);
            }
        }

        @Override
        public void sendText(String text) {
            if (open.get() && text != null && !text.isEmpty()) {
                callbacks.onTranscript(text, VoiceCallbacks.VoiceRole.ASSISTANT, true);
            }
        }

        @Override
        public void commitInput() {
            if (open.get()) {
                callbacks.onTranscript("(loopback turn complete)",
                        VoiceCallbacks.VoiceRole.ASSISTANT, true);
            }
        }

        @Override
        public void close() {
            if (open.compareAndSet(true, false)) {
                callbacks.onClose();
            }
        }
    }
}
