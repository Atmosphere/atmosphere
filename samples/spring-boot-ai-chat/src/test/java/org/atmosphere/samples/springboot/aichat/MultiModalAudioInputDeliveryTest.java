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
package org.atmosphere.samples.springboot.aichat;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiStreamingSession;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Delivery proof for the {@code "make the Atmosphere-4 blog true"} claim that an
 * agent can "take vision and audio input". Vision is already exercised by
 * {@link MultiModalAgent}'s {@code image:} branch; this test proves the matching
 * <em>audio input</em> claim end-to-end.
 *
 * <p>It drives {@link MultiModalAgent#onPrompt(String, StreamingSession)} with an
 * {@code audio:<base64>} prompt and asserts the decoded clip actually
 * <b>reaches the runtime</b> — i.e. the {@link AgentExecutionContext} the runtime
 * executes carries the bytes as a {@link Content.Audio} input part with the
 * correct media type. This is materially stronger than "the endpoint accepted
 * bytes": the assertion is on what the {@link AgentRuntime} received, captured by
 * a recording runtime wired behind a real {@link AiStreamingSession}.</p>
 *
 * <p>Offline + deterministic: the capturing runtime is injected directly, so the
 * test needs no API key and makes no network call. Mirrors the assertion style
 * of the framework-level vision proof
 * {@code OpenAiCompatibleClientMultiModalTest}.</p>
 */
class MultiModalAudioInputDeliveryTest {

    /** A small, non-trivial audio payload (a minimal WAV/RIFF byte prefix). */
    private static final byte[] AUDIO_BYTES = new byte[]{
            'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00,
            'W', 'A', 'V', 'E', 'f', 'm', 't', ' '
    };

    @Test
    void audioPromptDeliversAudioPartToRuntime() {
        // A blank api key keeps AiConfig in a no-key state; the recording runtime
        // we inject below is what actually receives the request, not a provider.
        AiConfig.configure("fake", "gpt-4o-audio-preview", null, null);

        var runtime = new RecordingRuntime();
        var delegate = mock(StreamingSession.class);
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);

        var session = new AiStreamingSession(delegate, runtime,
                "You are a transcription assistant.", "gpt-4o-audio-preview",
                List.of(), resource);

        var prompt = "audio:" + Base64.getEncoder().encodeToString(AUDIO_BYTES);
        new MultiModalAgent().onPrompt(prompt, session);
        session.close();

        // The runtime was invoked exactly once with the audio-bearing context.
        assertEquals(1, runtime.contexts.size(),
                "MultiModalAgent must dispatch the audio prompt to the runtime exactly once");

        var context = runtime.contexts.get(0);

        // The audio rode the request as a multi-modal INPUT part — not dropped at
        // the endpoint, not downgraded to a text breadcrumb.
        assertFalse(context.parts().isEmpty(),
                "audio input must reach the runtime as a Content part, but parts() was empty");
        assertEquals(1, context.parts().size());

        var part = context.parts().get(0);
        var audio = assertInstanceOf(Content.Audio.class, part,
                "the input part the runtime received must be Content.Audio");
        assertEquals("audio/wav", audio.mimeType(),
                "the audio media type must reach the runtime intact");
        assertEquals(AUDIO_BYTES.length, audio.data().length,
                "the audio byte length must round-trip to the runtime");
        for (int i = 0; i < AUDIO_BYTES.length; i++) {
            assertEquals(AUDIO_BYTES[i], audio.data()[i], "audio byte " + i + " mismatch");
        }

        // And the carrier text prompt the model is asked to act on is present.
        assertEquals("Transcribe and describe this audio clip.", context.message());
    }

    /** {@link AgentRuntime} that records every executed context for inspection. */
    private static final class RecordingRuntime implements AgentRuntime {
        final List<AgentExecutionContext> contexts = new ArrayList<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
            // No-op: this runtime captures requests; it never calls a provider.
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            contexts.add(context);
            session.complete();
        }
    }
}
