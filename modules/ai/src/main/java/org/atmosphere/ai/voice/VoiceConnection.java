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
 * A live connection to a realtime voice provider, returned by
 * {@link RealtimeVoiceProvider#connect}. The client→provider direction; results
 * flow back through the {@link VoiceCallbacks} supplied at connect time.
 *
 * <p>Implementations own the upstream resource (provider socket); the caller
 * that opened the connection ({@link VoiceBridge}) owns calling {@link #close()}.</p>
 */
public interface VoiceConnection extends AutoCloseable {

    /** Forward a chunk of captured user audio to the provider. */
    void sendAudio(byte[] audioChunk);

    /** Send a text turn (e.g. a typed message in a mixed voice/text session). */
    void sendText(String text);

    /**
     * Signal that the user has finished their current utterance so the provider
     * may begin responding (the realtime "commit" / end-of-turn marker).
     */
    void commitInput();

    /** Close the provider connection. Idempotent. */
    @Override
    void close();
}
