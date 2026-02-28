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
package org.atmosphere.samples.springboot.aiclassroom;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collaborative AI classroom endpoint. All clients connected to this path
 * share the same broadcaster — when one student asks a question, every
 * student sees the AI response stream token-by-token in real time.
 *
 * <p>The {@link RoomContextInterceptor} reads the {@code ?room=} query
 * parameter and sets a room-specific system prompt (math tutor, code mentor,
 * science educator).</p>
 */
@AiEndpoint(path = "/atmosphere/classroom",
        systemPromptResource = "prompts/classroom-prompt.md",
        interceptors = { RoomContextInterceptor.class })
public class AiClassroom {

    private static final Logger logger = LoggerFactory.getLogger(AiClassroom.class);

    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        var room = (String) resource.getRequest().getAttribute("classroom.room");
        logger.info("Classroom prompt in room '{}': {}", room, message);

        var settings = AiConfig.get();
        if (settings == null || settings.client().apiKey() == null
                || settings.client().apiKey().isBlank()) {
            DemoResponseProducer.stream(message, session, room != null ? room : "general");
            return;
        }

        session.stream(message);
    }
}
