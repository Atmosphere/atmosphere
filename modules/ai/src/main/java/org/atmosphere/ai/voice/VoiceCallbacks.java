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

/**
 * Callbacks a {@link RealtimeVoiceProvider} invokes as the provider streams
 * results back: synthesized audio chunks, incremental transcripts, errors, and
 * session close. {@link VoiceBridge} implements these to fan provider output out
 * to the connected client over the existing streaming transport.
 */
public interface VoiceCallbacks {

    /** Who produced a transcript fragment. */
    enum VoiceRole {
        USER,
        ASSISTANT
    }

    /** A chunk of synthesized assistant audio to play to the client. */
    void onAudio(byte[] audioChunk);

    /**
     * An incremental transcript fragment.
     *
     * @param text    the transcript text
     * @param role    who spoke
     * @param isFinal {@code true} when this completes an utterance
     */
    void onTranscript(String text, VoiceRole role, boolean isFinal);

    /** The provider session failed. */
    default void onError(Throwable error) {
    }

    /** The provider session ended normally. */
    default void onClose() {
    }
}
