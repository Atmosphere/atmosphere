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

import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.cpr.AtmosphereResource;

import java.util.Map;

/**
 * Sets the AI system prompt based on the {@code ?room=} query parameter.
 * Each room gets a different persona — math tutor, code mentor, or science educator.
 *
 * <p>This demonstrates the {@link AiInterceptor} pattern: cross-cutting concerns
 * (persona selection, guardrails, RAG) without modifying the {@code @Prompt} method.</p>
 */
public class RoomContextInterceptor implements AiInterceptor {

    private static final Map<String, String> ROOM_PROMPTS = Map.of(
            "math", "You are an expert mathematics tutor. "
                    + "Explain concepts clearly with step-by-step examples. "
                    + "Use precise mathematical notation. Keep responses under 300 words.",
            "code", "You are an expert software engineer and programming mentor. "
                    + "Provide clear, idiomatic code examples with explanations. "
                    + "Favor readability over cleverness. Keep responses under 300 words.",
            "science", "You are a science educator specializing in physics, chemistry, and biology. "
                    + "Use analogies and real-world examples to make concepts accessible. "
                    + "Keep responses under 300 words."
    );

    private static final String DEFAULT_PROMPT =
            "You are a helpful classroom assistant. "
                    + "Answer questions clearly and concisely. Keep responses under 300 words.";

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        var room = resource.getRequest().getParameter("room");
        if (room == null || room.isBlank()) {
            room = "general";
        }
        resource.getRequest().setAttribute("classroom.room", room);
        var systemPrompt = ROOM_PROMPTS.getOrDefault(room, DEFAULT_PROMPT);
        return request.withSystemPrompt(systemPrompt);
    }
}
