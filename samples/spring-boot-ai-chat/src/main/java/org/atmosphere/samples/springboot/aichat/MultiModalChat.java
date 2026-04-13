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

import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;

/**
 * Demonstrates the multi-modal {@link Content.Image} wire protocol.
 *
 * <p>Protocol: the client sends a text prompt. When the prompt starts with
 * {@code "image:"} the rest of the payload is decoded as base64 image bytes,
 * wrapped in a {@link Content.Image}, and forwarded through a
 * {@link StreamingSession} as a {@code {"type":"content","contentType":"image",...}}
 * frame. The handler then streams a short acknowledgement text describing
 * the received image so the client observes both a binary content frame and
 * a {@code streaming-text} frame in one exchange. Any other prompt falls
 * back to a plain text response so the endpoint stays useful for manual
 * exploration.</p>
 *
 * <p>The {@code mimeType} defaults to {@code image/png}; clients that send
 * a different format can prepend {@code image/<subtype>:} in front of the
 * base64 payload (e.g. {@code image:image/jpeg:<base64>}).</p>
 *
 * <p>Because the {@code @AiEndpoint}-provided
 * {@link org.atmosphere.ai.AiStreamingSession} wrapper does not override
 * {@link StreamingSession#sendContent(Content)}, the handler obtains a
 * {@link org.atmosphere.ai.DefaultStreamingSession} directly from
 * {@link StreamingSessions#start(AtmosphereResource)} and uses it for binary
 * content. The provided {@code session} parameter is still used for metadata
 * so all lifecycle observers registered by {@code @AiEndpoint} see the
 * request.</p>
 *
 * @see org.atmosphere.integrationtests.ai.MultiModalTestHandler
 */
@AiEndpoint(path = "/atmosphere/ai-chat-multimodal")
public class MultiModalChat {

    private static final Logger logger = LoggerFactory.getLogger(MultiModalChat.class);

    private static final String IMAGE_PREFIX = "image:";
    private static final String DEFAULT_MIME_TYPE = "image/png";

    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        if (!message.startsWith(IMAGE_PREFIX)) {
            session.send("MultiModalChat accepts 'image:<base64>' prompts. Got plain text: "
                    + message);
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

        // AiStreamingSession does not override sendContent(Content), so obtain
        // a DefaultStreamingSession directly from the resource to deliver the
        // binary content frame on the same wire. Do NOT call close() on the
        // binary session — it would emit a second "complete" frame that could
        // race the @AiEndpoint-scoped session's completion and confuse clients
        // that treat "complete" as a terminal signal.
        var binarySession = StreamingSessions.start(resource);
        binarySession.sendContent(new Content.Image(bytes, mimeType));

        logger.info("Echoed {} bytes of {} via multi-modal session {}",
                bytes.length, mimeType, binarySession.sessionId());

        session.send("Received " + bytes.length + " bytes of " + mimeType);
        session.complete();
    }
}
