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

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.PathParam;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collaborative AI classroom endpoint. All clients connected to the same
 * room share a broadcaster — when one student asks a question, every
 * student in that room sees the AI response stream text-by-text.
 *
 * <p>Demonstrates standard Atmosphere annotations with {@code @AiEndpoint}:</p>
 * <ul>
 *   <li>{@link PathParam @PathParam} — injects the {@code {room}} path variable</li>
 *   <li>{@link Ready @Ready} — invoked when a client connects and is suspended</li>
 *   <li>{@link Disconnect @Disconnect} — invoked when a client disconnects</li>
 * </ul>
 *
 * <p>The {@link RoomContextInterceptor} reads the {@code {room}} path
 * parameter and sets a room-specific system prompt (math tutor, code mentor,
 * science educator).</p>
 */
@AiEndpoint(path = "/atmosphere/classroom/{room}",
        systemPromptResource = "skill:classroom",
        interceptors = { RoomContextInterceptor.class })
@AgentScope(unrestricted = true,
        justification = "Endpoint-level scope is intentionally unrestricted — the actual scope is "
                + "installed per request by RoomContextInterceptor via "
                + "ScopePolicy.REQUEST_SCOPE_METADATA_KEY. Each of the four rooms (math / code / "
                + "science / general) carries its own ScopeConfig, so a prompt in the math room is "
                + "classified against 'mathematics tutoring' while the same prompt in the code room "
                + "is classified against 'software engineering mentoring'. One static @AgentScope "
                + "can't express this variance; per-request install is the framework-level answer.")
public class AiClassroom {

    private static final Logger logger = LoggerFactory.getLogger(AiClassroom.class);

    @PathParam("room")
    private String room;

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Student {} joined room '{}' (broadcaster: {})",
                resource.uuid(), room, resource.getBroadcaster().getID());
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("Student {} left room '{}'", event.getResource().uuid(), room);
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        logger.info("Classroom prompt in room '{}': {}", room, message);
        // Always through the pipeline: DemoAgentRuntime takes over when no
        // LLM_API_KEY is configured, otherwise the real runtime streams. The
        // per-request ScopeConfig installed by RoomContextInterceptor rides
        // on AiRequest.metadata() and is picked up by AiPipeline /
        // AiStreamingSession, so the demo path honours per-room scope
        // without a sample-local PolicyAdmissionGate hop.
        session.stream(message);
    }
}
