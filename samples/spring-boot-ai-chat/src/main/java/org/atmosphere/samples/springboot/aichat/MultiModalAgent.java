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

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;

/**
 * The blog headline made literal: <em>drop {@code @Agent} on a class and that
 * class is a running, streaming, multi-modal agent.</em> A single
 * {@code @Agent} class — its persona supplied by a {@code SKILL.md}
 * ({@code skill:multimodal-assistant}) instead of an inline system prompt —
 * registers a streaming WebSocket endpoint at {@code /atmosphere/agent/multimodal}
 * and accepts both {@link Content.Image} (vision) and {@link Content.Audio}
 * (audio) input.
 *
 * <p>{@code @Agent} desugars to the same {@code AiEndpointHandler} a plain
 * {@code @AiEndpoint} uses, so the injected {@link StreamingSession}, the
 * {@code @Prompt} method, and the multi-modal input path behave identically —
 * the only thing that changed is the headline annotation a reader sees first.</p>
 *
 * <p><b>Vision (output protocol):</b> when the prompt starts with {@code "image:"}
 * the rest of the payload is decoded as base64 image bytes, wrapped in a
 * {@link Content.Image}, and forwarded through the injected
 * {@link StreamingSession} as a {@code {"type":"content","contentType":"image",...}}
 * frame. The handler then streams a short acknowledgement text describing the
 * received image so the client observes both a binary content frame and a
 * {@code streaming-text} frame in one exchange. The {@code mimeType} defaults to
 * {@code image/png}; clients that send a different format can prepend
 * {@code image/<subtype>:} in front of the base64 payload (e.g.
 * {@code image:image/jpeg:<base64>}).</p>
 *
 * <p><b>Audio (model input):</b> when the prompt starts with {@code "audio:"} the
 * rest of the payload is decoded as base64 audio bytes, wrapped in a
 * {@link Content.Audio}, and forwarded to the resolved AI runtime as a
 * multi-modal <em>input</em> part via
 * {@link StreamingSession#stream(String, java.util.List)}. The runtime threads
 * the audio onto the provider wire request — the built-in OpenAI-compatible
 * client encodes it as an {@code "input_audio"} content block — so a vision +
 * audio capable model (e.g. {@code gpt-4o-audio-preview}) can transcribe or
 * describe the clip. With no API key configured the demo runtime answers with a
 * canned placeholder, but the audio still reaches the runtime context. The
 * {@code mimeType} defaults to {@code audio/wav}; clients can prepend
 * {@code audio/<subtype>:} to override (e.g. {@code audio:audio/mp3:<base64>}).</p>
 *
 * <p>The injected {@code session} is an {@code AiStreamingSession} that forwards
 * {@link StreamingSession#sendContent(Content)} to the underlying leaf session
 * and dispatches {@link StreamingSession#stream(String, java.util.List)} to the
 * resolved runtime, so the handler uses those calls directly — no secondary
 * session or manual framing is needed.</p>
 *
 * @see org.atmosphere.integrationtests.ai.MultiModalTestHandler
 */
@AgentScope(unrestricted = true,
        justification = "Multimodal demo; accepts arbitrary image and audio prompts to exercise Content parts")
@Agent(name = "multimodal",
        skillFile = "skill:multimodal-assistant",
        description = "Multi-modal assistant — accepts image (vision) and audio input over a streaming session.")
public class MultiModalAgent {

    private static final Logger logger = LoggerFactory.getLogger(MultiModalAgent.class);

    private static final String IMAGE_PREFIX = "image:";
    private static final String DEFAULT_MIME_TYPE = "image/png";

    private static final String AUDIO_PREFIX = "audio:";
    private static final String DEFAULT_AUDIO_MIME_TYPE = "audio/wav";
    private static final String AUDIO_PROMPT = "Transcribe and describe this audio clip.";

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        if (message.startsWith(AUDIO_PREFIX)) {
            onAudioPrompt(message, session);
            return;
        }
        if (!message.startsWith(IMAGE_PREFIX)) {
            session.send("MultiModalAgent accepts 'image:<base64>' (vision) or "
                    + "'audio:<base64>' (audio) prompts. Got plain text: " + message);
            session.complete();
            return;
        }

        var payload = message.substring(IMAGE_PREFIX.length());
        var mimeType = DEFAULT_MIME_TYPE;
        // Optional leading 'image/<subtype>:' lets callers override the mime type.
        var colon = payload.indexOf(':');
        if (colon > 0 && payload.startsWith("image/")) {
            mimeType = payload.substring(0, colon);
            payload = payload.substring(colon + 1);
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid base64 image payload: {}", e.getMessage());
            session.error(new IllegalArgumentException("Invalid base64 image payload", e));
            return;
        }

        if (bytes.length == 0) {
            session.error(new IllegalArgumentException("Empty image payload"));
            return;
        }

        session.sendMetadata("multimodal.accepted", true);
        session.sendMetadata("multimodal.mimeType", mimeType);
        session.sendMetadata("multimodal.bytes", bytes.length);

        // AiStreamingSession now forwards sendContent() to the leaf session,
        // so we can emit the binary frame directly on the same session the
        // @Agent lifecycle owns — no secondary DefaultStreamingSession,
        // no racing complete() frames.
        session.sendContent(new Content.Image(bytes, mimeType));

        logger.info("Echoed {} bytes of {} via multi-modal session {}",
                bytes.length, mimeType, session.sessionId());

        session.send("Received " + bytes.length + " bytes of " + mimeType);
        session.complete();
    }

    /**
     * Decode an {@code audio:<base64>} prompt and forward the bytes to the
     * resolved AI runtime as a {@link Content.Audio} <em>input</em> part. Unlike
     * the image branch (which echoes a content frame to the client), the audio
     * rides {@link StreamingSession#stream(String, java.util.List)} so it lands
     * in the {@code AgentExecutionContext} the runtime executes — the audio
     * actually reaches the model, not just the endpoint.
     */
    private void onAudioPrompt(String message, StreamingSession session) {
        var payload = message.substring(AUDIO_PREFIX.length());
        var mimeType = DEFAULT_AUDIO_MIME_TYPE;
        // Optional leading 'audio/<subtype>:' lets callers override the mime type.
        var colon = payload.indexOf(':');
        if (colon > 0 && payload.startsWith("audio/")) {
            mimeType = payload.substring(0, colon);
            payload = payload.substring(colon + 1);
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid base64 audio payload: {}", e.getMessage());
            session.error(new IllegalArgumentException("Invalid base64 audio payload", e));
            return;
        }

        if (bytes.length == 0) {
            session.error(new IllegalArgumentException("Empty audio payload"));
            return;
        }

        session.sendMetadata("multimodal.audio.accepted", true);
        session.sendMetadata("multimodal.audio.mimeType", mimeType);
        session.sendMetadata("multimodal.audio.bytes", bytes.length);

        logger.info("Forwarding {} bytes of {} as audio input to the runtime via session {}",
                bytes.length, mimeType, session.sessionId());

        // stream(message, parts) threads the Content.Audio into the
        // AgentExecutionContext; the runtime encodes it onto the provider wire
        // request so an audio-capable model receives the clip as input.
        session.stream(AUDIO_PROMPT, List.of(Content.audio(bytes, mimeType)));
    }
}
