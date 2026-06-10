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
 * Configuration for a realtime voice session opened through a
 * {@link RealtimeVoiceProvider}.
 *
 * @param model          the realtime model (provider-specific, e.g. a speech
 *                       model id)
 * @param voice          the synthesized voice name, or {@code ""} for the
 *                       provider default
 * @param systemPrompt   instructions for the assistant, or {@code ""}
 * @param inputMimeType  MIME type / format of the audio the client sends
 *                       (e.g. {@code "audio/pcm;rate=16000"})
 * @param outputMimeType MIME type / format of the audio the provider returns,
 *                       used to frame outbound {@link org.atmosphere.ai.Content.Audio}
 */
public record VoiceSessionConfig(
        String model,
        String voice,
        String systemPrompt,
        String inputMimeType,
        String outputMimeType) {

    public VoiceSessionConfig {
        model = model != null ? model : "";
        voice = voice != null ? voice : "";
        systemPrompt = systemPrompt != null ? systemPrompt : "";
        inputMimeType = inputMimeType != null && !inputMimeType.isBlank()
                ? inputMimeType : "audio/pcm;rate=16000";
        outputMimeType = outputMimeType != null && !outputMimeType.isBlank()
                ? outputMimeType : "audio/pcm;rate=24000";
    }

    /** Sensible defaults (PCM in/out) with the given model and system prompt. */
    public static VoiceSessionConfig of(String model, String systemPrompt) {
        return new VoiceSessionConfig(model, "", systemPrompt, null, null);
    }
}
