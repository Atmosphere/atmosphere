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

import java.util.ServiceLoader;

/**
 * SPI for a speech-to-speech realtime voice backend. An implementation opens a
 * live session to a provider's realtime API and streams audio both ways.
 *
 * <p>Atmosphere's existing WebSocket broadcaster already moves binary frames, so
 * {@link VoiceBridge} is a thin adapter between that transport and this SPI —
 * the heavy lifting (a bidirectional binary transport) is already shipped. A
 * provider implementation only has to translate the realtime wire protocol of
 * its API.</p>
 *
 * <p>{@link LoopbackVoiceProvider} ships in-tree as a dependency-free reference
 * (it echoes audio back) so the bridge is runnable and testable without an API
 * key. Production providers — OpenAI Realtime, Gemini Live — implement this same
 * interface and are discovered via {@link ServiceLoader}.</p>
 */
public interface RealtimeVoiceProvider {

    /** Provider name for logging / selection (e.g. {@code "openai-realtime"}). */
    String name();

    /**
     * Whether this provider can currently open sessions (credentials present,
     * endpoint reachable). Reported honestly per Correctness Invariant #5 —
     * capability is advertised only when the backend is actually usable.
     */
    boolean isAvailable();

    /**
     * Open a realtime session. Results stream back through {@code callbacks};
     * the returned {@link VoiceConnection} is the client→provider direction.
     *
     * @param config    session parameters (model, voice, audio formats)
     * @param callbacks where provider audio / transcripts / errors are delivered
     */
    VoiceConnection connect(VoiceSessionConfig config, VoiceCallbacks callbacks);

    /**
     * Resolve the highest-priority available provider via {@link ServiceLoader}.
     * Returns the {@link LoopbackVoiceProvider} fallback when no other provider
     * is registered or available, so a bridge can always be opened (the loopback
     * makes that obvious to the operator rather than failing silently).
     */
    static RealtimeVoiceProvider resolve() {
        for (var provider : ServiceLoader.load(RealtimeVoiceProvider.class)) {
            if (provider.isAvailable() && !(provider instanceof LoopbackVoiceProvider)) {
                return provider;
            }
        }
        return new LoopbackVoiceProvider();
    }
}
