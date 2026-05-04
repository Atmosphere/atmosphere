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
package org.atmosphere.samples.quarkus.aichat;

import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;

/**
 * Quarkus port of {@code spring-boot-ai-chat#MultiModalChat}. Demonstrates
 * the multi-modal {@link Content.Image} wire protocol identically across
 * Servlet containers.
 *
 * <p>Protocol: prompts of the form {@code "image:<base64>"} (with optional
 * {@code "image:image/<subtype>:<base64>"} prefix to override mime-type) are
 * decoded into a {@link Content.Image} and forwarded through
 * {@link StreamingSession#sendContent(Content)} as a content frame. The
 * handler then streams a text acknowledgement so the client observes both a
 * binary content frame and a streaming-text frame on the same exchange.
 * Plain text prompts get a plain text fallback.</p>
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

        session.sendContent(new Content.Image(bytes, mimeType));

        logger.info("Echoed {} bytes of {} via multi-modal session {}",
                bytes.length, mimeType, session.sessionId());

        session.send("Received " + bytes.length + " bytes of " + mimeType);
        session.complete();
    }
}
