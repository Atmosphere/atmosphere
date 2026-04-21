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
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;

/**
 * Demonstrates the multi-modal {@link Content.Image} wire protocol.
 *
 * <p>Protocol: the client sends a text prompt. When the prompt starts with
 * {@code "image:"} the rest of the payload is decoded as base64 image bytes,
 * wrapped in a {@link Content.Image}, and forwarded through the injected
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
 * <p>The injected {@code session} is an {@code AiStreamingSession} that
 * forwards {@link StreamingSession#sendContent(Content)} to the underlying
 * leaf session, so the handler calls {@code session.sendContent(...)}
 * directly — no secondary session or manual framing is needed.</p>
 *
 * @see org.atmosphere.integrationtests.ai.MultiModalTestHandler
 */
@AiEndpoint(path = "/atmosphere/ai-chat-multimodal")
@AgentScope(unrestricted = true,
        justification = "Multi-modal demo — accepts arbitrary text + image prompts to showcase multimodal streaming.")
public class MultiModalChat {

    private static final Logger logger = LoggerFactory.getLogger(MultiModalChat.class);

    private static final String IMAGE_PREFIX = "image:";
    private static final String DEFAULT_MIME_TYPE = "image/png";

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
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

        // AiStreamingSession now forwards sendContent() to the leaf session,
        // so we can emit the binary frame directly on the same session the
        // @AiEndpoint lifecycle owns — no secondary DefaultStreamingSession,
        // no racing complete() frames.
        session.sendContent(new Content.Image(bytes, mimeType));

        logger.info("Echoed {} bytes of {} via multi-modal session {}",
                bytes.length, mimeType, session.sessionId());

        session.send("Received " + bytes.length + " bytes of " + mimeType);
        session.complete();
    }
}
